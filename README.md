# Notify Bridge

Đẩy thông báo từ **nhiều điện thoại Android** về **một web dashboard** trên máy tính.
Server chạy trên Linux, mở ra ngoài bằng **Cloudflare Tunnel**.

```
┌─────────────┐   HTTPS POST      ┌──────────────┐   WebSocket   ┌───────────────┐
│ Android #1  │ ────────────────► │              │ ────────────► │               │
│ Android #2  │ ────────────────► │  Node server │               │ Web dashboard │
│ Android #3  │ ────────────────► │   + SQLite   │ ◄──────────── │  (máy tính)   │
└─────────────┘                   └──────────────┘   REST        └───────────────┘
                    ▲                     ▲
              Cloudflare Tunnel     systemd, /opt/notify
```

**Tính năng**

- Bắt mọi thông báo trên điện thoại (kể cả Zalo/Messenger/Telegram qua `MessagingStyle`)
- Feed realtime trên web — thông báo hiện ra trong khoảng 1 giây
- Lịch sử, lọc theo máy / theo app, tìm kiếm toàn văn, cuộn vô hạn
- Thông báo desktop + tiếng chuông khi có tin mới
- Hàng đợi offline: mất mạng thì giữ lại, có mạng gửi đủ, không trùng
- Bộ lọc bật/tắt từng ứng dụng, kèm nút bật/tắt hàng loạt (theo cả kết quả tìm kiếm)
- Chế độ ngủ: màn hình luôn sáng ở mức tối nhất — để một máy cũ cắm sạc làm trạm trung chuyển
- Mỗi máy một token riêng; web đăng nhập bằng mật khẩu

---

## 1. Cài server trên Linux

### Cách nhanh

```bash
# Cần Node.js 18 trở lên
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs

git clone <repo> notify && cd notify
sudo bash server/deploy/install-linux.sh
```

Script sẽ tạo user `notify`, chép code vào `/opt/notify/server`, cài dependency,
hỏi mật khẩu đăng nhập web, rồi bật systemd service.

### Cách thủ công

```bash
cd server
npm ci --omit=dev
cp .env.example .env          # sửa PORT nếu cần
node scripts/set-password.js  # đặt mật khẩu web
npm start
```

Mở `http://localhost:8787` để kiểm tra.

### Biến môi trường (`server/.env`)

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `PORT` | `8787` | Cổng server lắng nghe |
| `HOST` | `127.0.0.1` | Đổi thành `0.0.0.0` nếu muốn truy cập trực tiếp từ LAN |
| `RETENTION_DAYS` | `30` | Giữ thông báo bao nhiêu ngày (`0` = mãi mãi) |
| `MAX_ROWS` | `200000` | Trần số bản ghi trong DB |
| `TRUST_PROXY` | `1` | Bật khi chạy sau Cloudflare Tunnel |
| `SECURE_COOKIE` | `1` | Đặt `0` khi test qua `http://localhost` |
| `DB_PATH` | `./data/notify.db` | Đường dẫn file SQLite |

---

## 2. Mở ra internet bằng Cloudflare Tunnel

```bash
cloudflared tunnel login
cloudflared tunnel create notify          # in ra <TUNNEL-UUID>

sudo mkdir -p /etc/cloudflared
sudo cp ~/.cloudflared/<TUNNEL-UUID>.json /etc/cloudflared/
sudo cp server/deploy/cloudflared-config.yml /etc/cloudflared/config.yml
sudo nano /etc/cloudflared/config.yml     # điền UUID + tên miền của bạn

cloudflared tunnel route dns notify notify.tenmien.com
sudo cloudflared service install
```

Sau đó mở `https://notify.tenmien.com`.

> **WebSocket** đi qua Cloudflare Tunnel bình thường, không cần bật gì thêm.
> Server và trình duyệt tự ping nhau 30 giây một lần nên kết nối không bị Cloudflare
> đóng vì idle.

**Test nhanh không cần tên miền** — URL sẽ đổi mỗi lần chạy lại:

```bash
cloudflared tunnel --url http://localhost:8787
```

---

## 3. Cài app lên điện thoại

File APK: `android/app/build/outputs/apk/debug/app-debug.apk`

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Hoặc chép file APK sang điện thoại rồi bấm cài (phải bật "Cài từ nguồn không xác định").

### Ghép đôi

1. Trên web dashboard → bấm **＋ Thêm điện thoại** → hiện địa chỉ server và mã 6 số.
2. Mở app trên điện thoại → nhập địa chỉ + mã + tên máy → bấm **Kết nối**.
3. Tab **Trạng thái** → bấm **Cấp quyền** ở ô *Quyền đọc thông báo* → bật Notify Bridge
   trong danh sách hệ thống.
4. Bấm **Mở cài đặt** ở ô *Bỏ tối ưu pin* → chọn "Cho phép".
5. Bấm **Gửi thử** để kiểm tra. Thông báo phải hiện trên web ngay.

Lặp lại cho từng điện thoại — mỗi máy cần một mã ghép đôi riêng (mã dùng một lần,
hết hạn sau 10 phút).

### Chế độ ngủ

Dành cho trường hợp bạn để hẳn một máy cũ cắm sạc làm trạm trung chuyển. Bật công tắc
**Chế độ ngủ** trong tab Trạng thái thì:

- Màn hình **không bao giờ tự tắt** → hệ thống không ngủ, thông báo về gần như tức thì
- Độ sáng ép xuống **mức thấp nhất** → để trong phòng tối gần như không thấy

Chỉ có tác dụng khi app đang mở; thoát app là màn hình trở lại bình thường. **Nhớ cắm sạc** —
màn hình sáng liên tục rất tốn pin.

### Cảnh báo khi cài (Play Protect)

Nói thẳng: **không thể đảm bảo Play Protect im lặng hoàn toàn.** App này đọc *toàn bộ*
thông báo trên máy rồi gửi ra một server ngoài — đúng bằng hành vi mà Play Protect được
thiết kế để phát hiện. Không có mẹo nào làm Google ngừng coi đó là hành vi đáng cảnh báo,
trừ khi đưa app lên Play Store và qua kiểm duyệt.

Từ **v1.1** app đã được ký bằng khoá release thật thay vì khoá debug mặc định của Android.
Đây là yếu tố tác động lớn nhất: APK ký khoá debug bị Play Protect và phần mềm bảo mật của
các hãng đánh dấu nặng hơn hẳn. Nếu vẫn còn hiện cảnh báo thì bấm:

> **More details / Chi tiết** → **Install anyway / Vẫn cài**

### Android 13 trở lên: "Cài đặt bị hạn chế"

Đây mới là thứ hay chặn bạn nhất, và **không liên quan tới Play Protect**. Với app cài từ
file APK (không qua cửa hàng), Android 13+ khoá luôn ô bật quyền đọc thông báo và hiện:

> *"Để bảo mật, cài đặt này hiện không dùng được."*

Cách mở:

**Cài đặt → Ứng dụng → Notify Bridge → dấu ⋮ (góc trên phải) → Cho phép cài đặt bị hạn chế**

Xong bước đó mới quay lại app bấm **Cấp quyền** được.

### Máy Xiaomi / Oppo / Vivo / Realme

Các hãng này diệt tiến trình nền rất mạnh. Ngoài 2 bước trên, cần làm thêm:

- **Cài đặt → Ứng dụng → Notify Bridge → Tự khởi động (Autostart)**: bật
- Mở màn hình đa nhiệm, **ghim / khoá** Notify Bridge lại
- Xiaomi: **Tiết kiệm pin** cho app này chọn **Không giới hạn**
- Oppo/Realme: tắt **Ngủ đông ứng dụng** cho app này

Nếu không làm, hệ thống có thể ngắt kết nối service và thông báo sẽ đến chậm
(tối đa 15 phút, do WorkManager quét lại định kỳ).

---

## 4. Build lại app Android

```bash
cd android
./gradlew assembleRelease        # ban chinh, ky bang khoa that
./gradlew assembleDebug          # ban de gan loi
```

### Khoá ký release

Khoá ký **không nằm trong git**. Muốn build bản release, thư mục `android/` cần 2 file:

| File | Nội dung |
|---|---|
| `notify-release.jks` | Kho khoá (RSA 4096, hạn 10.000 ngày) |
| `keystore.properties` | `storeFile` / `storePassword` / `keyAlias` / `keyPassword` |

Nếu chưa có, tự tạo:

```bash
keytool -genkeypair -v -keystore android/notify-release.jks \
  -alias notify -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Notify Bridge, OU=Personal, O=Notify Bridge, C=VN"
```

rồi tạo `android/keystore.properties` trỏ tới nó.

> **Sao lưu cả hai file này.** Mất khoá là không cập nhật đè lên bản đã cài được nữa —
> phải gỡ app ra cài lại từ đầu, mất hết cấu hình ghép đôi trên mọi máy.
>
> Thiếu 2 file này thì `assembleRelease` vẫn chạy nhưng cho ra APK **chưa ký**, không cài được.
> Lúc đó dùng `assembleDebug`.

Yêu cầu: JDK 17+, Android SDK có platform **android-36**.
Sửa `android/local.properties` cho đúng đường dẫn SDK của bạn:

```properties
sdk.dir=/home/ban/Android/Sdk
```

Phiên bản đã dùng: Gradle 9.1.0 · AGP 8.11.1 · Kotlin 2.2.20 · compileSdk 36 · minSdk 24.

---

## 5. Cấu trúc dự án

```
server/
  src/index.js          bootstrap express + http + websocket
  src/db.js             schema SQLite, dọn dẹp định kỳ
  src/auth.js           scrypt, phiên đăng nhập, token thiết bị
  src/hub.js            WebSocket hub, ping/pong 30s
  src/routes/device.js  API cho điện thoại  (Bearer token)
  src/routes/web.js     API cho dashboard   (session cookie)
  public/               dashboard vanilla JS, không có bước build
  scripts/set-password.js, scripts/seed.js
  deploy/               systemd, cloudflared, install-linux.sh

android/app/src/main/java/com/notifybridge/
  service/NotifyListenerService.kt   bắt + lọc + chống trùng thông báo
  data/Outbox.kt                     hàng đợi SQLite
  data/Prefs.kt                      cài đặt
  net/Api.kt, net/Uploader.kt        gọi server, gom lô, thử lại
  work/UploadWorker.kt               lưới an toàn 15 phút + backoff
  ui/SleepMode.kt                    ép độ sáng tối thiểu + giữ màn hình sáng
  ui/                                Compose Material3
```

### Cách chống trùng hoạt động

Android bắn lại `onNotificationPosted` mỗi lần một thông báo được cập nhật.

- **Trên điện thoại**: nhớ `sbn.key → hash(tiêu đề + nội dung)` trong LRU 500 mục.
  Nội dung không đổi thì bỏ qua.
- **Trên server**: cột `uid` (`sha256(deviceId|key|postedAt|title|body)`) là UNIQUE,
  dùng `INSERT OR IGNORE`. Nên gửi lại sau khi mất mạng cũng không sinh bản ghi thừa.

### Vì sao app không dùng foreground service

`NotificationListenerService` được hệ thống *bind*, nên tiến trình đã được giữ sống và
tự khởi động lại. Ngược lại, foreground service kiểu `dataSync` trên Android 14+ bị
giới hạn 6 tiếng/ngày — hại nhiều hơn lợi. App dùng `requestRebind()` + WorkManager
định kỳ thay thế.

---

## 6. Vận hành

```bash
journalctl -u notify-server -f                  # xem log
systemctl restart notify-server                 # khởi động lại
cd /opt/notify/server && sudo -u notify node scripts/set-password.js   # đổi mật khẩu
node scripts/seed.js 60                         # tạo dữ liệu mẫu để xem thử giao diện
```

Đổi mật khẩu sẽ huỷ toàn bộ phiên đăng nhập đang mở.
Xoá một máy trên dashboard sẽ xoá luôn thông báo của máy đó và vô hiệu token của nó.

Sao lưu: chỉ cần copy `server/data/notify.db` (kèm `-wal`, `-shm` nếu có).

---

## 7. Bảo mật

- Web đăng nhập bằng mật khẩu, băm bằng `scrypt`; phiên lưu trong cookie `httpOnly`,
  `SameSite=Lax`, `Secure`. Sai 5 lần liên tiếp bị khoá 60 giây.
- Mỗi điện thoại có token 32 byte riêng; server **chỉ lưu SHA-256** của token.
- WebSocket kiểm tra cookie phiên ngay ở bước upgrade.
- Giới hạn 240 request/phút và 100 thông báo mỗi request cho mỗi máy.
- Toàn bộ đường truyền ra ngoài đi qua HTTPS của Cloudflare Tunnel; server chỉ
  lắng nghe trên `127.0.0.1`.

> Nội dung thông báo được lưu **dạng chữ thường** trong SQLite. Nếu máy Linux dùng
> chung với người khác, nên đặt máy ở nơi tin cậy hoặc bật mã hoá ổ đĩa.
