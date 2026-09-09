'use strict';

const { db } = require('./db');

/**
 * Bo loc ung dung theo tung may, luu o server thay vi chi nam trong dien thoai.
 *
 * Hai tang:
 *  - Server chan ngay khi nhan (POST /api/notifications bo qua package bi chan),
 *    nen doi bo loc tren web la co hieu luc tuc thi.
 *  - Dien thoai keo ve danh sach nay qua moi lan gui/heartbeat roi tu chan tu goc,
 *    de khoi ton pin va bang thong. Cham hon mot chut nhung khong anh huong ket qua.
 *
 * `filter_version` tang moi lan doi, de dien thoai biet khi nao can cap nhat.
 */

db.exec(`
CREATE TABLE IF NOT EXISTS device_filters (
  device_id TEXT NOT NULL,
  package   TEXT NOT NULL,
  PRIMARY KEY (device_id, package)
);
`);

const listBlockedStmt = db.prepare('SELECT package FROM device_filters WHERE device_id = ? ORDER BY package');
const insertBlockStmt = db.prepare('INSERT OR IGNORE INTO device_filters (device_id, package) VALUES (?, ?)');
const deleteBlockStmt = db.prepare('DELETE FROM device_filters WHERE device_id = ? AND package = ?');
const bumpVersionStmt = db.prepare('UPDATE devices SET filter_version = COALESCE(filter_version, 0) + 1 WHERE id = ?');
const versionStmt = db.prepare('SELECT COALESCE(filter_version, 0) AS v FROM devices WHERE id = ?');

/** Danh sach package bi chan cua mot may. */
function blockedFor(deviceId) {
  return listBlockedStmt.all(deviceId).map((r) => r.package);
}

function versionFor(deviceId) {
  const row = versionStmt.get(deviceId);
  return row ? row.v : 0;
}

/** Goi kem trong moi phan hoi gui cho dien thoai. */
function filterPayload(deviceId) {
  return { version: versionFor(deviceId), blocked: blockedFor(deviceId) };
}

function isBlocked(deviceId, pkg) {
  return Boolean(
    db.prepare('SELECT 1 FROM device_filters WHERE device_id = ? AND package = ?').get(deviceId, pkg)
  );
}

/**
 * Bat/tat mot package cho mot may. Tra ve true neu co thay doi that su
 * (khong doi thi khong tang version, tranh bat dien thoai dong bo vo ich).
 */
function setBlocked(deviceId, pkg, blocked) {
  const info = blocked ? insertBlockStmt.run(deviceId, pkg) : deleteBlockStmt.run(deviceId, pkg);
  if (info.changes > 0) {
    bumpVersionStmt.run(deviceId);
    return true;
  }
  return false;
}

/** Ghi de toan bo danh sach chan cua mot may (dien thoai day len khi nguoi dung sua tren may). */
function replaceBlocked(deviceId, packages) {
  const next = [...new Set(packages.filter((p) => typeof p === 'string' && p.trim()))].map((p) =>
    p.trim().slice(0, 200)
  );
  const current = blockedFor(deviceId);

  const same =
    current.length === next.length && current.every((p) => next.includes(p));
  if (same) return false;

  db.transaction(() => {
    db.prepare('DELETE FROM device_filters WHERE device_id = ?').run(deviceId);
    for (const pkg of next) insertBlockStmt.run(deviceId, pkg);
    bumpVersionStmt.run(deviceId);
  })();
  return true;
}

/** Xoa sach khi go mot may khoi he thong. */
function clearDevice(deviceId) {
  db.prepare('DELETE FROM device_filters WHERE device_id = ?').run(deviceId);
}

/**
 * Danh muc ung dung de hien tren web: lay tu nhung thong bao da nhan duoc,
 * cong them nhung package dang bi chan (de van thay va bo chan duoc ke ca khi
 * thong bao cu da bi don di).
 */
function appCatalog() {
  const seen = db
    .prepare(
      `SELECT package, MAX(app_name) AS app_name, COUNT(*) AS total, MAX(received_at) AS last_at
         FROM notifications
        GROUP BY package`
    )
    .all();

  const known = new Map(seen.map((r) => [r.package, r]));

  for (const row of db.prepare('SELECT DISTINCT package FROM device_filters').all()) {
    if (!known.has(row.package)) {
      known.set(row.package, { package: row.package, app_name: row.package, total: 0, last_at: null });
    }
  }

  return [...known.values()].sort((a, b) => {
    if (b.total !== a.total) return b.total - a.total;
    return String(a.app_name).localeCompare(String(b.app_name));
  });
}

module.exports = {
  blockedFor,
  versionFor,
  filterPayload,
  isBlocked,
  setBlocked,
  replaceBlocked,
  clearDevice,
  appCatalog,
};
