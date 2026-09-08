'use strict';

const crypto = require('crypto');
const { db, getSetting, setSetting } = require('./db');

const SESSION_TTL_MS = 30 * 86400000; // 30 ngay
const SECURE_COOKIE = process.env.SECURE_COOKIE === '1';

/* ---------- mat khau web (scrypt) ---------- */

function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const key = crypto.scryptSync(password, salt, 64);
  return `scrypt$${salt.toString('hex')}$${key.toString('hex')}`;
}

function verifyPassword(password, stored) {
  if (!stored) return false;
  const [algo, saltHex, keyHex] = stored.split('$');
  if (algo !== 'scrypt' || !saltHex || !keyHex) return false;
  const expected = Buffer.from(keyHex, 'hex');
  let actual;
  try {
    actual = crypto.scryptSync(password, Buffer.from(saltHex, 'hex'), expected.length);
  } catch {
    return false;
  }
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

function setWebPassword(password) {
  setSetting('password_hash', hashPassword(password));
  // Doi mat khau thi huy het phien dang dang nhap.
  db.prepare('DELETE FROM sessions').run();
}

function hasWebPassword() {
  return Boolean(getSetting('password_hash'));
}

/* ---------- phien dang nhap web ---------- */

const insertSession = db.prepare('INSERT INTO sessions (id, created_at, expires_at) VALUES (?, ?, ?)');
const findSession = db.prepare('SELECT * FROM sessions WHERE id = ? AND expires_at > ?');
const deleteSession = db.prepare('DELETE FROM sessions WHERE id = ?');

function createSession() {
  const id = crypto.randomBytes(32).toString('hex');
  const now = Date.now();
  insertSession.run(id, now, now + SESSION_TTL_MS);
  return id;
}

function sessionIsValid(id) {
  return Boolean(id) && Boolean(findSession.get(id, Date.now()));
}

function destroySession(id) {
  if (id) deleteSession.run(id);
}

function cookieOptions() {
  return {
    httpOnly: true,
    sameSite: 'lax',
    secure: SECURE_COOKIE,
    maxAge: SESSION_TTL_MS,
    path: '/',
  };
}

/** Middleware: chan neu chua dang nhap. */
function requireWebAuth(req, res, next) {
  if (sessionIsValid(req.cookies?.sid)) return next();
  res.status(401).json({ error: 'unauthorized', message: 'Chua dang nhap' });
}

/* ---------- token cua dien thoai ---------- */

function newDeviceToken() {
  return crypto.randomBytes(32).toString('hex');
}

function hashToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

const findDeviceByTokenHash = db.prepare('SELECT * FROM devices WHERE token_hash = ?');

/** Middleware: doi header `Authorization: Bearer <token>` lay ra thiet bi. */
function requireDeviceAuth(req, res, next) {
  const header = req.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token) {
    return res.status(401).json({ error: 'unauthorized', message: 'Thieu token' });
  }
  const device = findDeviceByTokenHash.get(hashToken(token));
  if (!device) {
    return res.status(401).json({ error: 'unauthorized', message: 'Token khong hop le' });
  }
  if (!device.enabled) {
    return res.status(403).json({ error: 'disabled', message: 'Thiet bi da bi tat' });
  }
  req.device = device;
  next();
}

module.exports = {
  hashPassword,
  verifyPassword,
  setWebPassword,
  hasWebPassword,
  createSession,
  sessionIsValid,
  destroySession,
  cookieOptions,
  requireWebAuth,
  newDeviceToken,
  hashToken,
  requireDeviceAuth,
};
