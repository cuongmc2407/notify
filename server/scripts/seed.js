#!/usr/bin/env node
'use strict';

/**
 * Tao du lieu mau de xem thu giao dien (khong can dien thoai that).
 *
 *   node scripts/seed.js [so-luong]
 */

require('dotenv').config();

const crypto = require('crypto');
const { db } = require('../src/db');

const COUNT = Number(process.argv[2] || 60);

const DEVICES = [
  { name: 'Redmi Note 13', model: 'Xiaomi 23129RAA4G', android: '14' },
  { name: 'Samsung A54', model: 'SM-A546E', android: '14' },
];

const SAMPLES = [
  ['com.zing.zalo', 'Zalo', 'Mẹ', 'Con ăn cơm chưa?'],
  ['com.zing.zalo', 'Zalo', 'Nhóm lớp 12A1', 'Tuấn: mai 7h tập trung ở cổng trường nhé mọi người'],
  ['com.facebook.orca', 'Messenger', 'Lan Anh', 'Ok anh, em gửi file trong mail rồi nha'],
  ['org.telegram.messenger', 'Telegram', 'Dev Team', 'Build #482 passed — deploy lên staging chưa?'],
  ['com.google.android.gm', 'Gmail', 'GitHub', '[notify-bridge] Run failed: CI on main'],
  ['com.google.android.gm', 'Gmail', 'Vietcombank', 'Thông báo biến động số dư tài khoản'],
  ['com.shopee.vn', 'Shopee', 'Đơn hàng đang giao', 'Đơn #250918ABCD sẽ được giao trong hôm nay'],
  ['com.grabtaxi.passenger', 'Grab', 'Tài xế đã đến', 'Anh Hùng - 59H1 234.56 đang đợi bạn'],
  ['com.vng.mb', 'MB Bank', 'Nhận tiền', 'TK 0123456789 (+) 2.500.000 VND lúc 09:42'],
  ['com.android.chrome', 'Chrome', 'Tin mới', 'Cập nhật thời tiết: chiều nay có mưa rào'],
  ['com.spotify.music', 'Spotify', 'Discover Weekly', 'Playlist tuần này của bạn đã sẵn sàng'],
  ['com.whatsapp', 'WhatsApp', 'John Doe', 'Sent you a document'],
];

function upsertDevice(spec) {
  const existing = db.prepare('SELECT * FROM devices WHERE name = ?').get(spec.name);
  if (existing) return existing.id;

  const id = crypto.randomUUID();
  db.prepare(
    `INSERT INTO devices (id, name, model, android_version, token_hash, created_at, last_seen_at, enabled)
     VALUES (?, ?, ?, ?, ?, ?, ?, 1)`
  ).run(
    id,
    spec.name,
    spec.model,
    spec.android,
    crypto.createHash('sha256').update('seed-' + id).digest('hex'),
    Date.now() - 86400000,
    Date.now() - 60000,
    );
  return id;
}

const deviceIds = DEVICES.map(upsertDevice);

const insert = db.prepare(
  `INSERT OR IGNORE INTO notifications
     (uid, device_id, package, app_name, title, body, category, posted_at, received_at, is_read)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
);

// Rai deu trong 3 ngay gan nhat, chen theo thu tu thoi gian tang dan
// de id trong DB cung thu tu voi thoi gian (giong thuc te).
const times = Array.from({ length: COUNT }, () => Date.now() - Math.floor(Math.random() * 3 * 86400000)).sort(
  (a, b) => a - b
);

let added = 0;
db.transaction(() => {
  for (let i = 0; i < COUNT; i += 1) {
    const [pkg, appName, title, body] = SAMPLES[Math.floor(Math.random() * SAMPLES.length)];
    const deviceId = deviceIds[Math.floor(Math.random() * deviceIds.length)];
    const postedAt = times[i];
    const info = insert.run(
      crypto.randomUUID(),
      deviceId,
      pkg,
      appName,
      title,
      body,
      'msg',
      postedAt,
      postedAt,
      Math.random() < 0.6 ? 1 : 0
    );
    added += info.changes;
  }
})();

console.log('Da them ' + added + ' thong bao mau cho ' + deviceIds.length + ' may.');
