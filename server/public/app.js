/* Notify Bridge - dashboard (vanilla JS, khong can build) */
'use strict';

/* ============ trang thai ============ */

const state = {
  items: [],
  cursor: null,
  hasMore: true,
  loading: false,
  filter: { device: null, package: null, unread: false, q: '' },
  devices: [],
  apps: [],
  totals: { total: 0, unread: 0 },
  fresh: new Set(),
};

const prefs = {
  get sound() {
    return localStorage.getItem('nb.sound') !== '0';
  },
  set sound(v) {
    localStorage.setItem('nb.sound', v ? '1' : '0');
  },
  get desktop() {
    return localStorage.getItem('nb.desktop') === '1';
  },
  set desktop(v) {
    localStorage.setItem('nb.desktop', v ? '1' : '0');
  },
  get theme() {
    return localStorage.getItem('nb.theme') || 'auto';
  },
  set theme(v) {
    localStorage.setItem('nb.theme', v);
  },
};

const $ = (id) => document.getElementById(id);

/**
 * Ghi chu vao mot phan tu, bo qua neu khong tim thay.
 * Neu trinh duyet dang chay app.js cu voi index.html moi (hoac nguoc lai) thi
 * mot phan tu thieu se nem loi giua chung ham ve, lam hong ca thanh ben.
 * Bo qua yen lang van tot hon la mat toan bo giao dien.
 */
function setText(id, value) {
  const el = $(id);
  if (el) el.textContent = value;
}

/* ============ tien ich ============ */

function toast(message, isError) {
  const el = document.createElement('div');
  el.className = 'toast' + (isError ? ' err' : '');
  el.textContent = message;
  $('toasts').appendChild(el);
  setTimeout(() => el.remove(), 4000);
}

async function api(path, options) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (res.status === 401) {
    location.replace('/');
    throw new Error('unauthorized');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.message || 'Lỗi ' + res.status);
  return data;
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function relTime(ms) {
  const diff = Date.now() - ms;
  if (diff < 45000) return 'vừa xong';
  const m = Math.round(diff / 60000);
  if (m < 60) return m + ' phút trước';
  const h = Math.round(diff / 3600000);
  if (h < 24) return h + ' giờ trước';
  const d = Math.round(diff / 86400000);
  if (d < 30) return d + ' ngày trước';
  return new Date(ms).toLocaleDateString('vi-VN');
}

function clockTime(ms) {
  return new Date(ms).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
}

function dayKey(ms) {
  const d = new Date(ms);
  return d.getFullYear() + '-' + d.getMonth() + '-' + d.getDate();
}

function dayLabel(ms) {
  const today = dayKey(Date.now());
  const yesterday = dayKey(Date.now() - 86400000);
  const key = dayKey(ms);
  if (key === today) return 'Hôm nay';
  if (key === yesterday) return 'Hôm qua';
  return new Date(ms).toLocaleDateString('vi-VN', {
    weekday: 'long',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  });
}

const AVATAR_COLORS = [
  '#2f6df6', '#17994f', '#b7791f', '#d33b3b', '#7c3aed',
  '#0891b2', '#db2777', '#65a30d', '#ea580c', '#4f46e5',
];

function avatarFor(pkg, name) {
  let hash = 0;
  for (let i = 0; i < pkg.length; i += 1) hash = (hash * 31 + pkg.charCodeAt(i)) >>> 0;
  const letter = (name || pkg).trim().charAt(0).toUpperCase() || '?';
  return { color: AVATAR_COLORS[hash % AVATAR_COLORS.length], letter };
}

/* ============ am thanh (WebAudio, khong can file mp3) ============ */

let audioCtx = null;

function unlockAudio() {
  if (!audioCtx) {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (Ctx) audioCtx = new Ctx();
  }
  if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
}

function beep() {
  if (!prefs.sound) return;
  unlockAudio();
  if (!audioCtx || audioCtx.state !== 'running') return;
  const now = audioCtx.currentTime;
  const gain = audioCtx.createGain();
  gain.connect(audioCtx.destination);
  gain.gain.setValueAtTime(0.0001, now);
  gain.gain.exponentialRampToValueAtTime(0.16, now + 0.015);
  gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.36);

  [880, 1320].forEach((freq, i) => {
    const osc = audioCtx.createOscillator();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(freq, now + i * 0.09);
    osc.connect(gain);
    osc.start(now + i * 0.09);
    osc.stop(now + 0.4);
  });
}

/* ============ thong bao desktop ============ */

function desktopNotify(item) {
  if (!prefs.desktop || !('Notification' in window) || Notification.permission !== 'granted') return;
  try {
    const n = new Notification(item.appName || item.package, {
      body: [item.title, item.body].filter(Boolean).join('\n').slice(0, 240),
      tag: 'nb-' + item.id,
      silent: true, // tieng bip da do WebAudio lo
    });
    n.onclick = () => {
      window.focus();
      n.close();
    };
  } catch (err) {
    /* trinh duyet co the tu choi */
  }
}

/* ============ ve giao dien ============ */

let renderQueued = false;

// Gom nhieu lan ve lam mot. Dung setTimeout chu khong dung
// requestAnimationFrame: rAF khong chay khi tab bi an, nen dashboard de o
// tab nen se khong cap nhat gi cho toi khi nguoi dung quay lai.
function scheduleRender() {
  if (renderQueued) return;
  renderQueued = true;
  setTimeout(() => {
    renderQueued = false;
    renderFeed();
  }, 16);
}

function renderFeed() {
  const feed = $('feed');

  if (!state.items.length) {
    feed.innerHTML = state.loading
      ? ''
      : '<div class="empty"><h2>Chưa có thông báo nào</h2><p>' +
        (state.devices.length
          ? 'Khi điện thoại nhận thông báo mới, nó sẽ hiện ở đây ngay lập tức.'
          : 'Bấm <b>＋ Thêm điện thoại</b> để ghép đôi máy đầu tiên.') +
        '</p></div>';
    return;
  }

  const parts = [];
  let lastDay = null;

  for (const item of state.items) {
    const key = dayKey(item.postedAt);
    if (key !== lastDay) {
      lastDay = key;
      parts.push('<div class="day-sep">' + escapeHtml(dayLabel(item.postedAt)) + '</div>');
    }

    const av = avatarFor(item.package, item.appName);
    const classes = ['card'];
    if (!item.isRead) classes.push('unread');
    if (state.fresh.has(item.id)) classes.push('fresh');

    parts.push(
      '<article class="' + classes.join(' ') + '" data-id="' + item.id + '">' +
        '<div class="avatar" style="background:' + av.color + '">' + escapeHtml(av.letter) + '</div>' +
        '<div class="card-body">' +
          '<div class="card-meta">' +
            '<span class="app-name">' + escapeHtml(item.appName || item.package) + '</span>' +
            '<span class="sep">·</span>' +
            '<span class="chip">' + escapeHtml(item.deviceName || 'Không rõ máy') + '</span>' +
          '</div>' +
          (item.title ? '<div class="card-title">' + escapeHtml(item.title) + '</div>' : '') +
          (item.body ? '<div class="card-text">' + escapeHtml(item.body) + '</div>' : '') +
        '</div>' +
        '<div class="card-side">' +
          '<span class="card-time" title="' + escapeHtml(new Date(item.postedAt).toLocaleString('vi-VN')) + '">' +
            escapeHtml(relTime(item.postedAt)) + ' · ' + escapeHtml(clockTime(item.postedAt)) +
          '</span>' +
          '<button class="card-del" data-del="' + item.id + '" title="Xoá">✕</button>' +
        '</div>' +
      '</article>'
    );
  }

  feed.innerHTML = parts.join('');
  if (state.fresh.size) setTimeout(() => state.fresh.clear(), 1800);
}

function renderSidebar() {
  setText('allCount', state.totals.total ? String(state.totals.total) : '');

  $('deviceList').innerHTML = state.devices.length
    ? state.devices
        .map((d) => {
          const sel = state.filter.device === d.id ? ' sel' : '';
          return (
            '<button class="side-item' + sel + '" data-device="' + escapeHtml(d.id) + '">' +
            '<span class="status-dot' + (d.online ? ' online' : '') + '"></span>' +
            '<span class="label" title="' + escapeHtml((d.model || '') + ' · ' + (d.online ? 'đang hoạt động' : 'ngoại tuyến')) + '">' +
            escapeHtml(d.name) + '</span>' +
            (d.unread ? '<span class="badge">' + d.unread + '</span>' : '<span class="count">' + d.total + '</span>') +
            '</button>'
          );
        })
        .join('')
    : '<div class="loader" style="padding:8px 14px;text-align:left">Chưa ghép máy nào</div>';

  $('appList').innerHTML = state.apps
    .map((a) => {
      const sel = state.filter.package === a.package ? ' sel' : '';
      const av = avatarFor(a.package, a.app_name);
      return (
        '<button class="side-item' + sel + '" data-package="' + escapeHtml(a.package) + '">' +
        '<span class="status-dot" style="background:' + av.color + '"></span>' +
        '<span class="label" title="' + escapeHtml(a.package) + '">' + escapeHtml(a.app_name || a.package) + '</span>' +
        '<span class="count">' + a.total + '</span>' +
        '</button>'
      );
    })
    .join('');

  document.querySelector('.side-item[data-filter="all"]').classList.toggle(
    'sel',
    !state.filter.device && !state.filter.package
  );

  const unread = state.totals.unread || 0;
  document.title = unread ? '(' + unread + ') Notify Bridge' : 'Notify Bridge';
}

/* ============ tai du lieu ============ */

function feedQuery(extra) {
  const p = new URLSearchParams({ limit: '50' });
  if (state.filter.device) p.set('device', state.filter.device);
  if (state.filter.package) p.set('package', state.filter.package);
  if (state.filter.unread) p.set('unread', '1');
  if (state.filter.q) p.set('q', state.filter.q);
  if (extra?.before) p.set('before', String(extra.before));
  return p.toString();
}

// Moi lan goi tang so thu tu; response cu hon se bi bo qua.
// Nho vay doi bo loc lien tuc khong bi ket qua cu ghi de len.
let feedSeq = 0;

async function loadFeed(reset) {
  // Tai them trang ke tiep thi phai xep hang; nhung "tai lai tu dau"
  // (doi bo loc) luon duoc chay va huy ket qua cua request dang cho.
  if (!reset && (state.loading || !state.hasMore)) return;

  const seq = (feedSeq += 1);
  state.loading = true;
  $('loader').hidden = false;

  try {
    const data = await api('/api/feed?' + feedQuery(reset ? null : { before: state.cursor }));
    if (seq !== feedSeq) return; // da co request moi hon

    if (reset) {
      state.items = data.items;
      $('main').scrollTop = 0;
    } else {
      const seen = new Set(state.items.map((i) => i.id));
      state.items = state.items.concat(data.items.filter((i) => !seen.has(i.id)));
    }
    state.hasMore = data.hasMore;
    state.cursor = data.nextCursor;
    scheduleRender();
  } catch (err) {
    if (seq === feedSeq && err.message !== 'unauthorized') toast(err.message, true);
  } finally {
    if (seq === feedSeq) {
      state.loading = false;
      $('loader').hidden = true;
    }
  }
}

async function loadSidebar() {
  try {
    const [devices, stats] = await Promise.all([api('/api/devices'), api('/api/stats')]);
    state.devices = devices.devices;
    state.apps = stats.apps;
    state.totals = { total: stats.total, unread: stats.unread };
    renderSidebar();
    // Modal quan ly dang mo thi cap nhat luon (vi du may vua ghep doi lai).
    if (!$('devicesModal').hidden) renderDeviceManager();
  } catch (err) {
    if (err.message !== 'unauthorized') toast(err.message, true);
  }
}

let sidebarTimer = null;
function loadSidebarSoon() {
  clearTimeout(sidebarTimer);
  sidebarTimer = setTimeout(loadSidebar, 800);
}

/* ============ WebSocket ============ */

let ws = null;
let wsRetry = 0;
let wsKeepalive = null;

function setConnState(on, label) {
  $('wsDot')?.classList.toggle('on', on);
  setText('wsText', label);
}

function matchesFilter(item) {
  const f = state.filter;
  if (f.device && item.deviceId !== f.device) return false;
  if (f.package && item.package !== f.package) return false;
  if (f.unread && item.isRead) return false;
  if (f.q) {
    const hay = ((item.title || '') + ' ' + (item.body || '') + ' ' + (item.appName || '')).toLowerCase();
    if (!hay.includes(f.q.toLowerCase())) return false;
  }
  return true;
}

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(proto + '//' + location.host + '/ws');

  ws.onopen = () => {
    wsRetry = 0;
    setConnState(true, 'trực tuyến');
    clearInterval(wsKeepalive);
    // Giu ket noi song qua Cloudflare (dong idle sau ~100s).
    wsKeepalive = setInterval(() => {
      if (ws && ws.readyState === 1) ws.send('ping');
    }, 30000);
    loadFeed(true);
    loadSidebar();
  };

  ws.onmessage = (event) => {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch {
      return;
    }
    handleWsEvent(msg);
  };

  ws.onclose = () => {
    clearInterval(wsKeepalive);
    ws = null;
    wsRetry += 1;
    const delay = Math.min(1000 * Math.pow(1.6, wsRetry), 30000);
    setConnState(false, 'mất kết nối · thử lại…');
    setTimeout(connectWs, delay);
  };

  ws.onerror = () => {
    if (ws) ws.close();
  };
}

function handleWsEvent(msg) {
  switch (msg.type) {
    case 'notification': {
      const item = msg.data;
      state.totals.unread = (state.totals.unread || 0) + 1;
      state.totals.total = (state.totals.total || 0) + 1;
      if (matchesFilter(item) && !state.items.some((i) => i.id === item.id)) {
        state.items.unshift(item);
        state.fresh.add(item.id);
        scheduleRender();
      }
      beep();
      desktopNotify(item);
      loadSidebarSoon();
      break;
    }
    case 'device_seen': {
      const d = state.devices.find((x) => x.id === msg.data.id);
      if (d) {
        d.lastSeenAt = msg.data.lastSeenAt;
        if (!d.online) {
          d.online = true;
          renderSidebar();
        }
      }
      break;
    }
    case 'device_added':
      toast('Đã ghép máy mới: ' + msg.data.name);
      loadSidebar();
      break;
    case 'device_updated':
    case 'device_removed':
      loadSidebar();
      break;
    case 'read_all':
      state.items.forEach((i) => {
        if (!msg.data.device || i.deviceId === msg.data.device) i.isRead = true;
      });
      scheduleRender();
      loadSidebar();
      break;
    case 'deleted':
      state.items = state.items.filter((i) => i.id !== msg.data.id);
      scheduleRender();
      loadSidebarSoon();
      break;
    case 'cleared':
      loadFeed(true);
      loadSidebar();
      break;
    default:
      break;
  }
}

/* ============ quan ly dien thoai ============ */

function renderDeviceManager() {
  const list = $('deviceManageList');

  if (!state.devices.length) {
    list.innerHTML = '<div class="dev-empty">Chưa ghép điện thoại nào.</div>';
    return;
  }

  list.innerHTML = state.devices
    .map((d) => {
      const seen = d.lastSeenAt ? relTime(d.lastSeenAt) : 'chưa bao giờ';
      const meta = [
        d.online ? 'đang hoạt động' : 'lần cuối ' + seen,
        d.model || 'không rõ máy',
        d.total + ' thông báo',
      ].join(' · ');
      return (
        '<div class="dev-row">' +
        '<span class="status-dot' + (d.online ? ' online' : '') + '"></span>' +
        '<div class="dev-main">' +
        '<input class="dev-name" data-rename="' + escapeHtml(d.id) + '" value="' + escapeHtml(d.name) + '" maxlength="80" />' +
        '<div class="dev-meta">' + escapeHtml(meta) + '</div>' +
        '</div>' +
        '<button class="dev-del" data-remove="' + escapeHtml(d.id) + '" data-total="' + d.total + '">Xoá</button>' +
        '</div>'
      );
    })
    .join('');
}

async function openDevicesModal() {
  $('devicesModal').hidden = false;
  await loadSidebar();
  renderDeviceManager();
}

// Duoc gan trong bindDeviceManager(); goi truoc khi dong modal.
let saveAllPendingNames = () => {};

function closeDevicesModal() {
  if ($('devicesModal').hidden) return;
  saveAllPendingNames();
  $('devicesModal').hidden = true;
}

function bindDeviceManager() {
  $('manageDevicesBtn').addEventListener('click', openDevicesModal);
  $('devicesClose').addEventListener('click', closeDevicesModal);
  $('devicesModal').addEventListener('click', (e) => {
    if (e.target === $('devicesModal')) closeDevicesModal();
  });

  // Doi ten: luu khi roi o nhap hoac bam Enter.
  const saveName = async (input) => {
    const id = input.dataset.rename;
    const device = state.devices.find((d) => d.id === id);
    const name = input.value.trim();
    if (!device || !name || name === device.name) {
      if (device) input.value = device.name;
      return;
    }
    try {
      await api('/api/devices/' + encodeURIComponent(id), {
        method: 'PATCH',
        body: JSON.stringify({ name }),
      });
      device.name = name;
      toast('Đã đổi tên thành "' + name + '"');
      renderSidebar();
      // Ten may hien trong tung the thong bao -> tai lai feed cho khop.
      loadFeed(true);
    } catch (err) {
      toast(err.message, true);
      input.value = device.name;
    }
  };

  $('deviceManageList').addEventListener('blur', (e) => {
    if (e.target.dataset?.rename) saveName(e.target);
  }, true);

  $('deviceManageList').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target.dataset?.rename) e.target.blur();
    if (e.key === 'Escape') closeDevicesModal();
  });

  // Dong modal bang Esc / bam ra ngoai thi blur co the khong kip chay,
  // nen luu not moi o ten dang sua do dang - tranh mat ten ma khong bao gi.
  saveAllPendingNames = () => {
    for (const input of $('deviceManageList').querySelectorAll('[data-rename]')) {
      saveName(input);
    }
  };

  // Xoa can hai lan bam: lan dau nut doi thanh "Chac chan xoa?", lan hai moi xoa that.
  // Tranh dung confirm() cua trinh duyet - de bi chan va trong tho.
  let armed = null;
  let armedTimer = null;

  const disarm = () => {
    clearTimeout(armedTimer);
    armed = null;
    renderDeviceManager();
  };

  $('deviceManageList').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-remove]');
    if (!btn) return;

    const id = btn.dataset.remove;
    const total = Number(btn.dataset.total) || 0;

    if (armed !== id) {
      clearTimeout(armedTimer);
      armed = id;

      // Ve lai ca danh sach truoc, de nut cua may khac dang o trang thai
      // "chac chan xoa?" tro lai binh thuong.
      renderDeviceManager();

      const fresh = $('deviceManageList').querySelector('[data-remove="' + CSS.escape(id) + '"]');
      if (fresh) {
        fresh.textContent = total > 0 ? 'Xoá cả ' + total + ' thông báo?' : 'Chắc chắn xoá?';
        fresh.style.background = 'var(--red)';
        fresh.style.color = '#fff';
        fresh.style.borderColor = 'var(--red)';
      }
      armedTimer = setTimeout(disarm, 5000);
      return;
    }

    clearTimeout(armedTimer);
    armed = null;

    try {
      await api('/api/devices/' + encodeURIComponent(id), { method: 'DELETE' });
      toast('Đã xoá điện thoại');
      if (state.filter.device === id) setFilter({ device: null });
      await loadSidebar();
      renderDeviceManager();
      loadFeed(true);
    } catch (err) {
      toast(err.message, true);
      renderDeviceManager();
    }
  });
}

/* ============ ghep doi ============ */

let pairTimer = null;

async function openPairModal() {
  $('pairModal').hidden = false;
  $('pairUrl').textContent = location.origin;
  await newPairingCode();
}

async function newPairingCode() {
  $('pairCode').textContent = '······';
  $('pairExpiry').textContent = 'đang tạo mã…';
  try {
    const data = await api('/api/pairing-code', { method: 'POST' });
    $('pairCode').textContent = data.code;
    clearInterval(pairTimer);
    pairTimer = setInterval(() => {
      const left = Math.round((data.expiresAt - Date.now()) / 1000);
      if (left <= 0) {
        clearInterval(pairTimer);
        $('pairExpiry').textContent = 'Mã đã hết hạn – bấm "Tạo mã mới"';
        $('pairCode').textContent = '······';
        return;
      }
      const mm = String(Math.floor(left / 60)).padStart(2, '0');
      const ss = String(left % 60).padStart(2, '0');
      $('pairExpiry').textContent = 'Hết hạn sau ' + mm + ':' + ss;
    }, 1000);
  } catch (err) {
    $('pairExpiry').textContent = err.message;
  }
}

function closePairModal() {
  $('pairModal').hidden = true;
  clearInterval(pairTimer);
}

/* ============ chu de sang/toi ============ */

function applyTheme() {
  const t = prefs.theme;
  if (t === 'auto') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', t);
}

/* ============ gan su kien ============ */

function setFilter(patch) {
  Object.assign(state.filter, patch);
  state.cursor = null;
  state.hasMore = true;
  renderSidebar();
  loadFeed(true);
}

function bind() {
  // Sidebar
  $('sidebar').addEventListener('click', (e) => {
    const btn = e.target.closest('.side-item');
    if (!btn) return;
    if (btn.dataset.filter === 'all') setFilter({ device: null, package: null });
    else if (btn.dataset.device)
      setFilter({ device: state.filter.device === btn.dataset.device ? null : btn.dataset.device, package: null });
    else if (btn.dataset.package)
      setFilter({ package: state.filter.package === btn.dataset.package ? null : btn.dataset.package, device: null });
    $('sidebar').classList.remove('open');
  });

  $('menuBtn').addEventListener('click', () => $('sidebar').classList.toggle('open'));

  // Tim kiem
  let searchTimer = null;
  $('search').addEventListener('input', (e) => {
    clearTimeout(searchTimer);
    const value = e.target.value;
    searchTimer = setTimeout(() => setFilter({ q: value.trim() }), 300);
  });

  // Xoa 1 thong bao
  $('feed').addEventListener('click', async (e) => {
    const del = e.target.closest('[data-del]');
    if (!del) return;
    const id = Number(del.dataset.del);
    try {
      await api('/api/notifications/' + id, { method: 'DELETE' });
      state.items = state.items.filter((i) => i.id !== id);
      scheduleRender();
      loadSidebarSoon();
    } catch (err) {
      toast(err.message, true);
    }
  });

  // Cuon vo han
  const io = new IntersectionObserver(
    (entries) => {
      // Chi tai them khi da co du lieu, tranh vong lap khi feed dang rong.
      if (state.items.length && entries.some((en) => en.isIntersecting)) loadFeed(false);
    },
    { root: $('main'), rootMargin: '400px' }
  );
  io.observe($('sentinel'));

  // Du phong cho IntersectionObserver: co truong hop (tab bi an, layout co lai)
  // observer khong ban, nen bat them su kien cuon.
  $('main').addEventListener(
    'scroll',
    () => {
      const el = $('main');
      if (state.items.length && el.scrollTop + el.clientHeight >= el.scrollHeight - 600) {
        loadFeed(false);
      }
    },
    { passive: true }
  );

  // Chi hien chua doc
  $('unreadBtn').addEventListener('click', () => {
    const on = !state.filter.unread;
    $('unreadBtn').classList.toggle('active', on);
    setFilter({ unread: on });
  });

  // Am thanh
  const syncSound = () => {
    $('soundBtn').classList.toggle('active', prefs.sound);
    $('soundBtn').textContent = prefs.sound ? '🔔' : '🔕';
  };
  $('soundBtn').addEventListener('click', () => {
    prefs.sound = !prefs.sound;
    syncSound();
    if (prefs.sound) {
      unlockAudio();
      beep();
    }
  });
  syncSound();

  // Thong bao desktop
  const syncNotify = () => {
    const granted = 'Notification' in window && Notification.permission === 'granted';
    $('notifyBtn').classList.toggle('active', prefs.desktop && granted);
    $('notifyBtn').textContent = prefs.desktop && granted ? '💬' : '🚫';
  };
  $('notifyBtn').addEventListener('click', async () => {
    if (!('Notification' in window)) {
      toast('Trình duyệt không hỗ trợ thông báo desktop', true);
      return;
    }
    if (prefs.desktop && Notification.permission === 'granted') {
      prefs.desktop = false;
      syncNotify();
      toast('Đã tắt thông báo desktop');
      return;
    }
    const perm = await Notification.requestPermission();
    if (perm !== 'granted') {
      toast('Bạn đã chặn quyền thông báo trong trình duyệt', true);
      syncNotify();
      return;
    }
    prefs.desktop = true;
    syncNotify();
    toast('Đã bật thông báo desktop');
  });
  syncNotify();

  // Sang / toi
  $('themeBtn').addEventListener('click', () => {
    const order = ['auto', 'light', 'dark'];
    prefs.theme = order[(order.indexOf(prefs.theme) + 1) % order.length];
    applyTheme();
    toast('Giao diện: ' + { auto: 'theo hệ thống', light: 'sáng', dark: 'tối' }[prefs.theme]);
  });

  // Danh dau da doc
  $('readAllBtn').addEventListener('click', async () => {
    try {
      await api('/api/read-all', {
        method: 'POST',
        body: JSON.stringify({ device: state.filter.device }),
      });
      state.items.forEach((i) => {
        i.isRead = true;
      });
      scheduleRender();
      loadSidebar();
    } catch (err) {
      toast(err.message, true);
    }
  });

  // Quan ly dien thoai
  bindDeviceManager();

  // Ghep doi
  $('pairBtn').addEventListener('click', openPairModal);
  $('pairClose').addEventListener('click', closePairModal);
  $('pairRegen').addEventListener('click', newPairingCode);
  $('pairModal').addEventListener('click', (e) => {
    if (e.target === $('pairModal')) closePairModal();
  });

  // Dang xuat
  $('logoutBtn').addEventListener('click', async () => {
    await fetch('/auth/logout', { method: 'POST' });
    location.replace('/');
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      closePairModal();
      closeDevicesModal();
    }
    if (e.key === '/' && document.activeElement !== $('search')) {
      e.preventDefault();
      $('search').focus();
    }
  });

  // Mo khoa AudioContext o lan tuong tac dau tien.
  ['click', 'keydown'].forEach((ev) =>
    document.addEventListener(ev, unlockAudio, { once: true })
  );

  // Cap nhat lai nhan thoi gian tuong doi moi phut.
  setInterval(scheduleRender, 60000);
  // Cap nhat trang thai online cua cac may.
  setInterval(loadSidebar, 60000);
}

/* ============ khoi dong ============ */

applyTheme();
bind();
setConnState(false, 'đang nối…');
loadSidebar();
loadFeed(true);
connectWs();
