'use strict';

const crypto = require('crypto');
const express = require('express');
const { db, getSetting } = require('../db');
const {
  verifyPassword,
  hasWebPassword,
  createSession,
  sessionIsValid,
  destroySession,
  cookieOptions,
  requireWebAuth,
} = require('../auth');
const hub = require('../hub');
const filters = require('../filters');
const { publicNotification, publicDevice } = require('./device');

const router = express.Router();

const ONLINE_WINDOW_MS = 20 * 60000; // coi la online neu vua bao cao trong 20 phut
const PAIRING_TTL_MS = 10 * 60000;

/* ---------- dang nhap ---------- */

const loginAttempts = new Map();

function loginThrottle(req, res, next) {
  const key = req.ip || 'unknown';
  const now = Date.now();
  const entry = loginAttempts.get(key);
  if (entry && entry.blockedUntil > now) {
    const seconds = Math.ceil((entry.blockedUntil - now) / 1000);
    return res
      .status(429)
      .json({ error: 'rate_limited', message: 'Sai qua nhieu lan, thu lai sau ' + seconds + ' giay' });
  }
  next();
}

function noteFailedLogin(ip) {
  const entry = loginAttempts.get(ip) || { count: 0, blockedUntil: 0 };
  entry.count += 1;
  if (entry.count >= 5) {
    entry.blockedUntil = Date.now() + 60000;
    entry.count = 0;
  }
  loginAttempts.set(ip, entry);
}

router.post('/auth/login', loginThrottle, (req, res) => {
  const password = String(req.body?.password ?? '');
  if (!hasWebPassword()) {
    return res
      .status(503)
      .json({ error: 'no_password', message: 'Server chua dat mat khau. Chay: npm run set-password' });
  }
  if (!verifyPassword(password, getSetting('password_hash'))) {
    noteFailedLogin(req.ip || 'unknown');
    return res.status(401).json({ error: 'bad_password', message: 'Mat khau khong dung' });
  }
  loginAttempts.delete(req.ip || 'unknown');
  res.cookie('sid', createSession(), cookieOptions());
  res.json({ ok: true });
});

router.post('/auth/logout', (req, res) => {
  destroySession(req.cookies?.sid);
  res.clearCookie('sid', { path: '/' });
  res.json({ ok: true });
});

router.get('/auth/me', (req, res) => {
  res.json({ authenticated: sessionIsValid(req.cookies?.sid), hasPassword: hasWebPassword() });
});

/* ---------- moi route /api ben duoi deu can dang nhap ---------- */

router.use('/api', requireWebAuth);

/* ---------- feed ---------- */

router.get('/api/feed', (req, res) => {
  const limit = Math.min(Math.max(Number(req.query.limit) || 50, 1), 200);
  const before = Number(req.query.before);
  const device = req.query.device ? String(req.query.device) : null;
  const pkg = req.query.package ? String(req.query.package) : null;
  const unread = req.query.unread === '1';
  const q = req.query.q ? String(req.query.q).trim() : '';

  const where = [];
  const params = [];

  if (Number.isFinite(before) && before > 0) {
    where.push('n.id < ?');
    params.push(before);
  }
  if (device) {
    where.push('n.device_id = ?');
    params.push(device);
  }
  if (pkg) {
    where.push('n.package = ?');
    params.push(pkg);
  }
  if (unread) {
    where.push('n.is_read = 0');
  }
  if (q) {
    where.push("(n.title LIKE ? ESCAPE '\\' OR n.body LIKE ? ESCAPE '\\' OR n.app_name LIKE ? ESCAPE '\\')");
    const like = '%' + q.replace(/[\\%_]/g, (m) => '\\' + m) + '%';
    params.push(like, like, like);
  }

  const sql =
    'SELECT n.*, d.name AS device_name FROM notifications n ' +
    'LEFT JOIN devices d ON d.id = n.device_id ' +
    (where.length ? 'WHERE ' + where.join(' AND ') + ' ' : '') +
    'ORDER BY n.id DESC LIMIT ?';

  const rows = db.prepare(sql).all(...params, limit + 1);
  const hasMore = rows.length > limit;
  const items = rows.slice(0, limit).map(publicNotification);

  res.json({
    items,
    hasMore,
    nextCursor: hasMore && items.length ? items[items.length - 1].id : null,
  });
});

/* ---------- thiet bi ---------- */

router.get('/api/devices', (req, res) => {
  const now = Date.now();
  const rows = db
    .prepare(
      'SELECT d.*, ' +
        '(SELECT COUNT(*) FROM notifications n WHERE n.device_id = d.id) AS total, ' +
        '(SELECT COUNT(*) FROM notifications n WHERE n.device_id = d.id AND n.is_read = 0) AS unread ' +
        'FROM devices d ORDER BY d.created_at ASC'
    )
    .all();

  res.json({
    devices: rows.map((d) => ({
      ...publicDevice(d),
      total: d.total,
      unread: d.unread,
      online: Boolean(d.last_seen_at && now - d.last_seen_at < ONLINE_WINDOW_MS),
    })),
  });
});

router.patch('/api/devices/:id', (req, res) => {
  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(req.params.id);
  if (!device) return res.status(404).json({ error: 'not_found', message: 'Khong tim thay thiet bi' });

  const name = req.body?.name !== undefined ? String(req.body.name).trim().slice(0, 80) : null;
  const enabled = req.body?.enabled !== undefined ? (req.body.enabled ? 1 : 0) : null;

  if (name) db.prepare('UPDATE devices SET name = ? WHERE id = ?').run(name, device.id);
  if (enabled !== null) db.prepare('UPDATE devices SET enabled = ? WHERE id = ?').run(enabled, device.id);

  const updated = db.prepare('SELECT * FROM devices WHERE id = ?').get(device.id);
  hub.broadcast('device_updated', publicDevice(updated));
  res.json(publicDevice(updated));
});

router.delete('/api/devices/:id', (req, res) => {
  const info = db.transaction((id) => {
    db.prepare('DELETE FROM notifications WHERE device_id = ?').run(id);
    filters.clearDevice(id);
    return db.prepare('DELETE FROM devices WHERE id = ?').run(id);
  })(req.params.id);

  if (!info.changes) return res.status(404).json({ error: 'not_found', message: 'Khong tim thay thiet bi' });
  hub.broadcast('device_removed', { id: req.params.id });
  res.json({ ok: true });
});

/* ---------- ma ghep doi ---------- */

router.post('/api/pairing-code', (req, res) => {
  const now = Date.now();
  db.prepare('DELETE FROM pairing_codes WHERE expires_at < ? OR used_at IS NOT NULL').run(now);

  let code = null;
  for (let i = 0; i < 20 && !code; i += 1) {
    const candidate = String(crypto.randomInt(100000, 1000000));
    if (!db.prepare('SELECT 1 FROM pairing_codes WHERE code = ?').get(candidate)) code = candidate;
  }
  if (!code) return res.status(500).json({ error: 'internal', message: 'Khong sinh duoc ma' });

  const expiresAt = now + PAIRING_TTL_MS;
  db.prepare('INSERT INTO pairing_codes (code, expires_at, used_at) VALUES (?, ?, NULL)').run(code, expiresAt);
  res.json({ code, expiresAt, ttlSeconds: Math.round(PAIRING_TTL_MS / 1000) });
});

/* ---------- bo loc ung dung (sua tu web, khong can dung toi dien thoai) ---------- */

router.get('/api/filter', (req, res) => {
  const devices = db.prepare('SELECT id, name FROM devices ORDER BY created_at ASC').all();
  res.json({
    apps: filters.appCatalog().map((a) => ({
      package: a.package,
      appName: a.app_name || a.package,
      total: a.total,
      lastAt: a.last_at || null,
    })),
    devices: devices.map((d) => ({
      id: d.id,
      name: d.name,
      blocked: filters.blockedFor(d.id),
    })),
  });
});

router.post('/api/filter', (req, res) => {
  const pkg = String(req.body?.package || '').trim().slice(0, 200);
  if (!pkg) {
    return res.status(400).json({ error: 'bad_request', message: 'Thieu package' });
  }

  const blocked = Boolean(req.body?.blocked);
  // device = null nghia la ap dung cho moi may.
  const target = req.body?.device ? String(req.body.device) : null;

  const ids = target
    ? [target]
    : db.prepare('SELECT id FROM devices').all().map((d) => d.id);

  if (target && !db.prepare('SELECT 1 FROM devices WHERE id = ?').get(target)) {
    return res.status(404).json({ error: 'not_found', message: 'Khong tim thay thiet bi' });
  }

  let changed = 0;
  for (const id of ids) {
    if (filters.setBlocked(id, pkg, blocked)) changed += 1;
  }

  if (changed) hub.broadcast('filter_changed', { package: pkg, blocked, device: target });

  res.json({ ok: true, changed, package: pkg, blocked });
});

/* ---------- thong ke cho sidebar ---------- */

router.get('/api/stats', (req, res) => {
  const apps = db
    .prepare(
      'SELECT package, MAX(app_name) AS app_name, COUNT(*) AS total, ' +
        'SUM(CASE WHEN is_read = 0 THEN 1 ELSE 0 END) AS unread ' +
        'FROM notifications GROUP BY package ORDER BY total DESC LIMIT 40'
    )
    .all();

  const totals =
    db
      .prepare(
        'SELECT COUNT(*) AS total, SUM(CASE WHEN is_read = 0 THEN 1 ELSE 0 END) AS unread FROM notifications'
      )
      .get() || {};

  res.json({ apps, total: totals.total || 0, unread: totals.unread || 0, wsClients: hub.clientCount() });
});

/* ---------- danh dau da doc / xoa ---------- */

router.post('/api/read-all', (req, res) => {
  const device = req.body?.device ? String(req.body.device) : null;
  const info = device
    ? db.prepare('UPDATE notifications SET is_read = 1 WHERE is_read = 0 AND device_id = ?').run(device)
    : db.prepare('UPDATE notifications SET is_read = 1 WHERE is_read = 0').run();
  hub.broadcast('read_all', { device, changed: info.changes });
  res.json({ ok: true, changed: info.changes });
});

router.post('/api/notifications/:id/read', (req, res) => {
  const info = db.prepare('UPDATE notifications SET is_read = 1 WHERE id = ?').run(Number(req.params.id));
  res.json({ ok: true, changed: info.changes });
});

router.delete('/api/notifications/:id', (req, res) => {
  const id = Number(req.params.id);
  const info = db.prepare('DELETE FROM notifications WHERE id = ?').run(id);
  if (!info.changes) return res.status(404).json({ error: 'not_found', message: 'Khong tim thay' });
  hub.broadcast('deleted', { id });
  res.json({ ok: true });
});

router.delete('/api/notifications', (req, res) => {
  const device = req.query.device ? String(req.query.device) : null;
  const info = device
    ? db.prepare('DELETE FROM notifications WHERE device_id = ?').run(device)
    : db.prepare('DELETE FROM notifications').run();
  hub.broadcast('cleared', { device, changed: info.changes });
  res.json({ ok: true, changed: info.changes });
});

module.exports = router;
