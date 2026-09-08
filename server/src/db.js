'use strict';

const fs = require('fs');
const path = require('path');
const Database = require('better-sqlite3');

const DB_PATH = process.env.DB_PATH
  ? path.resolve(process.cwd(), process.env.DB_PATH)
  : path.resolve(__dirname, '..', 'data', 'notify.db');

fs.mkdirSync(path.dirname(DB_PATH), { recursive: true });

const db = new Database(DB_PATH);

db.pragma('journal_mode = WAL');
db.pragma('synchronous = NORMAL');
db.pragma('foreign_keys = ON');

db.exec(`
CREATE TABLE IF NOT EXISTS devices (
  id              TEXT PRIMARY KEY,
  name            TEXT NOT NULL,
  model           TEXT,
  android_version TEXT,
  token_hash      TEXT NOT NULL,
  hardware_id     TEXT,
  battery_level   INTEGER,
  battery_charging INTEGER,
  battery_at      INTEGER,
  created_at      INTEGER NOT NULL,
  last_seen_at    INTEGER,
  enabled         INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS notifications (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  uid         TEXT NOT NULL UNIQUE,
  device_id   TEXT NOT NULL,
  package     TEXT NOT NULL,
  app_name    TEXT,
  title       TEXT,
  body        TEXT,
  category    TEXT,
  posted_at   INTEGER NOT NULL,
  received_at INTEGER NOT NULL,
  is_read     INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_notif_id_desc  ON notifications (id DESC);
CREATE INDEX IF NOT EXISTS idx_notif_device   ON notifications (device_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_notif_package  ON notifications (package, id DESC);
CREATE INDEX IF NOT EXISTS idx_notif_posted   ON notifications (posted_at);
CREATE INDEX IF NOT EXISTS idx_notif_unread   ON notifications (is_read);

CREATE TABLE IF NOT EXISTS pairing_codes (
  code       TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL,
  used_at    INTEGER
);

CREATE TABLE IF NOT EXISTS sessions (
  id         TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT
);
`);

/* ---------- nang cap luoc do cho CSDL tao tu ban cu ---------- */

const deviceColumns = db.prepare('PRAGMA table_info(devices)').all().map((c) => c.name);

for (const [column, definition] of [
  ['hardware_id', 'TEXT'],
  ['battery_level', 'INTEGER'],
  ['battery_charging', 'INTEGER'],
  ['battery_at', 'INTEGER'],
]) {
  if (!deviceColumns.includes(column)) {
    db.exec(`ALTER TABLE devices ADD COLUMN ${column} ${definition}`);
  }
}

// Khong dat UNIQUE: ALTER TABLE cua SQLite khong them duoc rang buoc,
// va cac may ghep truoc ban 1.2 deu co hardware_id = NULL. Tinh duy nhat
// duoc bao dam o routes/device.js khi ghep doi.
db.exec('CREATE INDEX IF NOT EXISTS idx_devices_hardware ON devices (hardware_id)');

/* ---------- settings ---------- */

const getSettingStmt = db.prepare('SELECT value FROM settings WHERE key = ?');
const setSettingStmt = db.prepare(
  'INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'
);

function getSetting(key) {
  const row = getSettingStmt.get(key);
  return row ? row.value : null;
}

function setSetting(key, value) {
  setSettingStmt.run(key, value);
}

/* ---------- don dep dinh ky ---------- */

const RETENTION_DAYS = Number(process.env.RETENTION_DAYS ?? 30);
const MAX_ROWS = Number(process.env.MAX_ROWS ?? 200000);

function cleanup() {
  const now = Date.now();

  db.prepare('DELETE FROM sessions WHERE expires_at < ?').run(now);
  db.prepare('DELETE FROM pairing_codes WHERE expires_at < ? OR used_at IS NOT NULL').run(now);

  if (RETENTION_DAYS > 0) {
    const cutoff = now - RETENTION_DAYS * 86400000;
    db.prepare('DELETE FROM notifications WHERE received_at < ?').run(cutoff);
  }

  if (MAX_ROWS > 0) {
    const { n } = db.prepare('SELECT COUNT(*) AS n FROM notifications').get();
    if (n > MAX_ROWS) {
      db.prepare(
        'DELETE FROM notifications WHERE id IN (SELECT id FROM notifications ORDER BY id ASC LIMIT ?)'
      ).run(n - MAX_ROWS);
    }
  }
}

function startCleanupTimer() {
  cleanup();
  const timer = setInterval(cleanup, 3600000);
  timer.unref();
  return timer;
}

module.exports = { db, DB_PATH, getSetting, setSetting, cleanup, startCleanupTimer };
