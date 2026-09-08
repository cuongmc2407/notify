'use strict';

require('dotenv').config();

const http = require('http');
const path = require('path');
const express = require('express');
const cookieParser = require('cookie-parser');

const { DB_PATH, startCleanupTimer } = require('./db');
const { hasWebPassword, sessionIsValid } = require('./auth');
const hub = require('./hub');
const deviceRoutes = require('./routes/device');
const webRoutes = require('./routes/web');

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '127.0.0.1';
const PUBLIC_DIR = path.resolve(__dirname, '..', 'public');

const app = express();

if (process.env.TRUST_PROXY === '1') app.set('trust proxy', 1);
app.disable('x-powered-by');

app.use(express.json({ limit: '1mb' }));
app.use(cookieParser());

app.get('/healthz', (req, res) => res.json({ ok: true, t: Date.now() }));

// API cua dien thoai (xac thuc bang Bearer token) - phai dat truoc router web.
app.use('/api', deviceRoutes.router);

// API + dang nhap cua dashboard (xac thuc bang cookie phien).
app.use(webRoutes);

// Trang chu: chua dang nhap thi tra ve trang dang nhap.
app.get('/', (req, res) => {
  const file = sessionIsValid(req.cookies?.sid) ? 'index.html' : 'login.html';
  res.sendFile(path.join(PUBLIC_DIR, file));
});

app.use(
  express.static(PUBLIC_DIR, {
    index: false,
    setHeaders(res, filePath) {
      if (filePath.endsWith('.html')) res.setHeader('Cache-Control', 'no-store');
    },
  })
);

app.use((req, res) => res.status(404).json({ error: 'not_found', message: 'Khong tim thay duong dan' }));

// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  if (res.headersSent) return;
  // Body khong phai JSON hop le / qua lon: loi cua client, khong phai cua server.
  if (err.type === 'entity.parse.failed') {
    return res.status(400).json({ error: 'bad_json', message: 'Body khong phai JSON hop le' });
  }
  if (err.type === 'entity.too.large') {
    return res.status(413).json({ error: 'too_large', message: 'Body qua lon' });
  }
  console.error('[loi]', err);
  res.status(500).json({ error: 'internal', message: 'Loi server' });
});

const server = http.createServer(app);
hub.attach(server);
startCleanupTimer();

server.listen(PORT, HOST, () => {
  console.log('');
  console.log('  Notify Bridge server');
  console.log('  ---------------------------------------------');
  console.log('  Dang nghe   : http://' + HOST + ':' + PORT);
  console.log('  CSDL        : ' + DB_PATH);
  console.log('  Giu lai     : ' + (process.env.RETENTION_DAYS || 30) + ' ngay');
  if (!hasWebPassword()) {
    console.log('');
    console.log('  !! CHUA DAT MAT KHAU WEB. Chay lenh sau roi khoi dong lai:');
    console.log('     npm run set-password');
  }
  console.log('');
});

function shutdown(signal) {
  console.log('\nNhan ' + signal + ', dang dong server...');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
