#!/usr/bin/env bash
#
# Cai Notify Bridge server len may Linux (Debian/Ubuntu).
#
#   sudo bash deploy/install-linux.sh
#
# Script se: kiem tra Node, tao user `notify`, chep code vao /opt/notify/server,
# cai dependency, hoi mat khau web, roi bat systemd service.

set -euo pipefail

APP_DIR=/opt/notify/server
SERVICE_USER=notify
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mLoi: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Phai chay bang sudo/root."

say "Kiem tra Node.js"
if ! command -v node >/dev/null 2>&1; then
  die "Chua co Node.js. Cai bang:
    curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
    sudo apt-get install -y nodejs"
fi

NODE_MAJOR="$(node -p "process.versions.node.split('.')[0]")"
[ "$NODE_MAJOR" -ge 18 ] || die "Can Node.js 18 tro len, dang co $(node -v)."
echo "Node $(node -v) OK"

say "Tao user he thong '$SERVICE_USER'"
id -u "$SERVICE_USER" >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"

say "Chep code vao $APP_DIR"
mkdir -p "$APP_DIR"
if [ "$SRC_DIR" != "$APP_DIR" ]; then
  cp -r "$SRC_DIR/src" "$SRC_DIR/public" "$SRC_DIR/scripts" "$SRC_DIR/package.json" "$APP_DIR/"
  [ -f "$SRC_DIR/package-lock.json" ] && cp "$SRC_DIR/package-lock.json" "$APP_DIR/"
fi
mkdir -p "$APP_DIR/data"

say "Tao file .env"
if [ ! -f "$APP_DIR/.env" ]; then
  cp "$SRC_DIR/.env.example" "$APP_DIR/.env"
  echo "Da tao $APP_DIR/.env tu mau. Sua neu can doi cong."
else
  echo "$APP_DIR/.env da co san, giu nguyen."
fi

say "Cai dependency"
cd "$APP_DIR"
# better-sqlite3 co san file .node bien dich truoc cho linux-x64/arm64.
# Neu may ban kien truc khac, can: apt-get install -y build-essential python3
if [ -f package-lock.json ]; then
  npm ci --omit=dev
else
  npm install --omit=dev
fi

say "Kiem tra better-sqlite3"
# better-sqlite3 tai file .node bien dich san trong buoc `npm install`.
# npm >= 11 co the chan install script -> phai duyet thu cong roi cai lai.
if ! node -e "require('better-sqlite3')" 2>/dev/null; then
  echo "Chua co ban bien dich, dang thu tai lai..."
  npm rebuild better-sqlite3 || true
fi
node -e "require('better-sqlite3'); console.log('better-sqlite3 OK')" || die \
  "better-sqlite3 khong nap duoc. Thu lan luot:
    cd $APP_DIR
    npm approve-scripts --allow-scripts-pending   # neu npm >= 11 chan install script
    npm rebuild better-sqlite3
    apt-get install -y build-essential python3 && npm rebuild better-sqlite3"

say "Dat mat khau dang nhap web"
if [ -n "${NOTIFY_PASSWORD:-}" ]; then
  node scripts/set-password.js "$NOTIFY_PASSWORD"
else
  node scripts/set-password.js </dev/tty
fi

say "Phan quyen"
chown -R "$SERVICE_USER:$SERVICE_USER" "$APP_DIR"
chmod 640 "$APP_DIR/.env"

say "Cai systemd service"
cp "$SRC_DIR/deploy/notify-server.service" /etc/systemd/system/notify-server.service
systemctl daemon-reload
systemctl enable --now notify-server
sleep 2
systemctl --no-pager status notify-server || true

PORT="$(grep -E '^PORT=' "$APP_DIR/.env" | cut -d= -f2 | tr -d '[:space:]')"
PORT="${PORT:-8787}"

cat <<EOF

============================================================
 Xong. Server dang chay o http://localhost:$PORT

 Buoc tiep theo - mo ra internet bang Cloudflare Tunnel:

   cloudflared tunnel login
   cloudflared tunnel create notify
   sudo mkdir -p /etc/cloudflared
   sudo cp ~/.cloudflared/<TUNNEL-UUID>.json /etc/cloudflared/
   sudo cp $SRC_DIR/deploy/cloudflared-config.yml /etc/cloudflared/config.yml
   sudo nano /etc/cloudflared/config.yml      # sua UUID va ten mien
   cloudflared tunnel route dns notify notify.tenmien.com
   sudo cloudflared service install

 Xem log server:  journalctl -u notify-server -f
 Doi mat khau  :  cd $APP_DIR && sudo -u $SERVICE_USER node scripts/set-password.js
============================================================
EOF
