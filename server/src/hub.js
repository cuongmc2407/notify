'use strict';

const { WebSocketServer } = require('ws');
const { sessionIsValid } = require('./auth');

const PING_INTERVAL_MS = 30000; // Cloudflare dong WebSocket idle sau ~100s -> phai ping deu.

let wss = null;

function parseSid(req) {
  const raw = req.headers.cookie;
  if (!raw) return null;
  for (const part of raw.split(';')) {
    const idx = part.indexOf('=');
    if (idx === -1) continue;
    if (part.slice(0, idx).trim() === 'sid') {
      return decodeURIComponent(part.slice(idx + 1).trim());
    }
  }
  return null;
}

/** Gan WebSocket server vao http server, chi cho phep phien web da dang nhap. */
function attach(server) {
  wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (req, socket, head) => {
    let pathname;
    try {
      pathname = new URL(req.url, 'http://localhost').pathname;
    } catch {
      socket.destroy();
      return;
    }
    if (pathname !== '/ws') {
      socket.destroy();
      return;
    }
    if (!sessionIsValid(parseSid(req))) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });

  wss.on('connection', (ws) => {
    ws.isAlive = true;
    ws.on('pong', () => {
      ws.isAlive = true;
    });
    ws.on('message', (data) => {
      // Client gui "ping" dang text de giu ket noi khi tab bi background.
      if (String(data) === 'ping') ws.send(JSON.stringify({ type: 'pong', t: Date.now() }));
    });
    ws.on('error', () => ws.terminate());
    ws.send(JSON.stringify({ type: 'hello', t: Date.now() }));
  });

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.isAlive === false) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      try {
        ws.ping();
      } catch {
        ws.terminate();
      }
    }
  }, PING_INTERVAL_MS);
  heartbeat.unref();

  return wss;
}

/** Gui mot su kien toi moi tab dang mo dashboard. */
function broadcast(type, data) {
  if (!wss) return;
  const payload = JSON.stringify({ type, data, t: Date.now() });
  for (const ws of wss.clients) {
    if (ws.readyState === 1) {
      try {
        ws.send(payload);
      } catch {
        /* bo qua, heartbeat se don */
      }
    }
  }
}

function clientCount() {
  return wss ? wss.clients.size : 0;
}

module.exports = { attach, broadcast, clientCount };
