'use strict';

const crypto = require('crypto');
const express = require('express');
const { db } = require('../db');
const { requireDeviceAuth, newDeviceToken, hashToken } = require('../auth');
const hub = require('../hub');

const router = express.Router();

const MAX_EVENTS_PER_REQUEST = 100;
const MAX_FIELD_LEN = 4000;

/* ---------- gioi han so request (trong RAM) ---------- */

const RATE_LIMIT = 240; // request / phut / thiet bi
const buckets = new Map();

function rateLimit(req, res, next) {
  const key = req.device ? req.device.id : req.ip;
  const minute = Math.floor(Date.now() / 60000);
  const entry = buckets.get(key);
  if (!entry || entry.minute !== minute) {
    buckets.set(key, { minute, count: 1 });
    if (buckets.size > 5000) buckets.clear();
    return next();
  }
  entry.count += 1;
  if (entry.count > RATE_LIMIT) {
    return res.status(429).json({ error: 'rate_limited', message: 'Gui qua nhanh' });
  }
  next();
}

/* ---------- helper ---------- */

function str(value, maxLen = MAX_FIELD_LEN) {
  if (value === null || value === undefined) return null;
  const s = String(value).trim();
  if (!s) return null;
  return s.length > maxLen ? s.slice(0, maxLen) : s;
}

const touchDevice = db.prepare('UPDATE devices SET last_seen_at = ? WHERE id = ?');

/* ---------- POST /api/pair ---------- */

const findCode = db.prepare('SELECT * FROM pairing_codes WHERE code = ?');
const useCode = db.prepare('UPDATE pairing_codes SET used_at = ? WHERE code = ?');
const insertDevice = db.prepare(`
  INSERT INTO devices (id, name, model, android_version, token_hash, hardware_id, created_at, last_seen_at, enabled)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
`);

const findByHardware = db.prepare('SELECT * FROM devices WHERE hardware_id = ?');

const reissueToken = db.prepare(`
  UPDATE devices
     SET name = ?, model = ?, android_version = ?, token_hash = ?, last_seen_at = ?, enabled = 1
   WHERE id = ?
`);

router.post('/pair', (req, res) => {
  const code = str(req.body?.code, 16);
  if (!code) {
    return res.status(400).json({ error: 'bad_request', message: 'Thieu ma ghep doi' });
  }

  const row = findCode.get(code);
  const now = Date.now();
  if (!row || row.used_at || row.expires_at < now) {
    return res.status(400).json({ error: 'bad_code', message: 'Ma khong dung hoac da het han' });
  }

  const token = newDeviceToken();
  const name = str(req.body?.deviceName, 80) || str(req.body?.model, 80) || 'Dien thoai';
  const model = str(req.body?.model, 80);
  const androidVersion = str(req.body?.androidVersion, 40);

  // Dinh danh phan cung (bam tu ANDROID_ID) giup nhan ra van la may cu sau khi
  // go app cai lai. Khong co no thi moi lan ghep doi lai se de ra mot may trung lap.
  const hardwareId = str(req.body?.hardwareId, 128);
  const existing = hardwareId ? findByHardware.get(hardwareId) : null;

  if (existing) {
    db.transaction(() => {
      useCode.run(now, code);
      reissueToken.run(name, model, androidVersion, hashToken(token), now, existing.id);
    })();

    const updated = db.prepare('SELECT * FROM devices WHERE id = ?').get(existing.id);
    hub.broadcast('device_updated', publicDevice(updated));

    // reused = true: token cu da bi vo hieu, lich su thong bao van giu nguyen.
    return res.json({ deviceId: existing.id, token, deviceName: name, reused: true, serverTime: now });
  }

  const id = crypto.randomUUID();

  db.transaction(() => {
    useCode.run(now, code);
    insertDevice.run(id, name, model, androidVersion, hashToken(token), hardwareId, now, now);
  })();

  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(id);
  hub.broadcast('device_added', publicDevice(device));

  res.json({ deviceId: id, token, deviceName: name, reused: false, serverTime: now });
});

function publicDevice(d) {
  return {
    id: d.id,
    name: d.name,
    model: d.model,
    androidVersion: d.android_version,
    createdAt: d.created_at,
    lastSeenAt: d.last_seen_at,
    enabled: Boolean(d.enabled),
  };
}

/* ---------- POST /api/notifications ---------- */

const insertNotif = db.prepare(`
  INSERT OR IGNORE INTO notifications
    (uid, device_id, package, app_name, title, body, category, posted_at, received_at, is_read)
  VALUES (@uid, @deviceId, @pkg, @appName, @title, @body, @category, @postedAt, @receivedAt, 0)
`);

router.post('/notifications', requireDeviceAuth, rateLimit, (req, res) => {
  const events = Array.isArray(req.body?.events) ? req.body.events : null;
  if (!events) {
    return res.status(400).json({ error: 'bad_request', message: 'Thieu mang events' });
  }
  if (events.length > MAX_EVENTS_PER_REQUEST) {
    return res.status(413).json({ error: 'too_many', message: `Toi da ${MAX_EVENTS_PER_REQUEST} su kien` });
  }

  const now = Date.now();
  const deviceId = req.device.id;
  const inserted = [];

  db.transaction(() => {
    for (const raw of events) {
      const pkg = str(raw?.package, 200);
      if (!pkg) continue;

      const title = str(raw?.title, MAX_FIELD_LEN);
      const body = str(raw?.body, MAX_FIELD_LEN);
      if (!title && !body) continue;

      // uid do dien thoai sinh; neu thieu thi tu bam de van chong trung duoc.
      let uid = str(raw?.uid, 128);
      if (!uid) {
        uid = crypto
          .createHash('sha256')
          .update(`${deviceId}|${pkg}|${raw?.postedAt ?? ''}|${title ?? ''}|${body ?? ''}`)
          .digest('hex');
      }

      const postedAt = Number(raw?.postedAt);
      const row = {
        uid,
        deviceId,
        pkg,
        appName: str(raw?.appName, 200) || pkg,
        title,
        body,
        category: str(raw?.category, 40),
        postedAt: Number.isFinite(postedAt) && postedAt > 0 ? postedAt : now,
        receivedAt: now,
      };

      const info = insertNotif.run(row);
      if (info.changes > 0) inserted.push(info.lastInsertRowid);
    }
    touchDevice.run(now, deviceId);
  })();

  if (inserted.length) {
    const placeholders = inserted.map(() => '?').join(',');
    const rows = db
      .prepare(
        `SELECT n.*, d.name AS device_name
         FROM notifications n JOIN devices d ON d.id = n.device_id
         WHERE n.id IN (${placeholders}) ORDER BY n.id ASC`
      )
      .all(...inserted);
    for (const row of rows) hub.broadcast('notification', publicNotification(row));
  }

  hub.broadcast('device_seen', { id: deviceId, lastSeenAt: now });

  res.json({ accepted: inserted.length, received: events.length, serverTime: now });
});

function publicNotification(n) {
  return {
    id: n.id,
    uid: n.uid,
    deviceId: n.device_id,
    deviceName: n.device_name,
    package: n.package,
    appName: n.app_name,
    title: n.title,
    body: n.body,
    category: n.category,
    postedAt: n.posted_at,
    receivedAt: n.received_at,
    isRead: Boolean(n.is_read),
  };
}

/* ---------- POST /api/heartbeat ---------- */

router.post('/heartbeat', requireDeviceAuth, rateLimit, (req, res) => {
  const now = Date.now();
  touchDevice.run(now, req.device.id);
  hub.broadcast('device_seen', { id: req.device.id, lastSeenAt: now });
  res.json({ ok: true, deviceName: req.device.name, serverTime: now });
});

module.exports = { router, publicNotification, publicDevice };
