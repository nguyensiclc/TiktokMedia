# Chạy Media Studio trên MacBook làm máy chủ

MacBook chạy app 24/7. Bạn mở app (và **Trợ lý chat**) từ laptop/điện thoại khác qua trình duyệt.

```
[Laptop / iPhone] ──Wi-Fi hoặc Tailscale──▶ [MacBook: Media Studio :8787]
                                              ├─ Claude CLI (trợ lý, viết kịch bản)
                                              ├─ Chrome (Google Flow, TikTok Studio)
                                              ├─ VieNeu-TTS (giọng đọc)
                                              └─ FFmpeg (xuất video)
```

## 1. Cài đặt (một lần)

Mở **Terminal** trên Mac:

```bash
git clone https://github.com/nguyensiclc/TiktokMedia.git ~/TiktokMedia
cd ~/TiktokMedia
./deploy/mac/setup.sh
```

Script này sẽ:
- cài Homebrew (nếu chưa có), Java 21, Maven, Node.js, FFmpeg, Google Chrome và Claude Code CLI;
- build giao diện + backend → `backend/target/backend.jar`;
- cài VieNeu-TTS (giọng tiếng Việt) vào `~/MediaStudio/vieneu-venv`. Bỏ bước này bằng `./deploy/mac/setup.sh --no-tts`;
- tạo `~/.mediastudio/settings.json` (cài đặt mặc định cho macOS);
- tạo `~/.mediastudio/server.env` gồm token, PIN và cổng. **File này chứa bí mật, không đưa lên git.**

## 2. Đăng nhập Claude

Trợ lý và các bước viết đều chạy bằng Claude CLI. Hãy dùng token dài hạn để server không bị đăng xuất giữa chừng:

```bash
claude setup-token
```

Dán token vừa nhận vào `~/.mediastudio/server.env`, ở dòng `CLAUDE_CODE_OAUTH_TOKEN=`.

## 3. Chạy

Chạy thử (dừng bằng Ctrl+C):

```bash
./deploy/mac/run.sh
```

Chạy nền, tự khởi động khi đăng nhập và tự chạy lại nếu bị lỗi:

```bash
./deploy/mac/install-service.sh
tail -f ~/MediaStudio/server.log        # xem log
```

Server dùng `caffeinate`, nên Mac không ngủ khi server đang chạy. Nếu dùng MacBook gập màn hình, hãy cắm sạc. Bạn cũng nên bật:
**Cài đặt hệ thống → Pin → Tuỳ chọn → "Ngăn tự động ngủ khi cắm sạc và màn hình tắt"**.

## 4. Mở từ máy khác

1. Xem IP của Mac: **Cài đặt hệ thống → Wi-Fi → Chi tiết**, hoặc chạy `ipconfig getifaddr en0`.
2. Trên laptop/điện thoại, mở `http://<IP-của-Mac>:8787`.
3. Nhập mã PIN. PIN nằm ở dòng `MEDIASTUDIO_LAN_PIN` trong `~/.mediastudio/server.env`.

Muốn dùng **ngoài nhà**: cài [Tailscale](https://tailscale.com) trên Mac và trên thiết bị của bạn, rồi mở `http://<IP-Tailscale-của-Mac>:8787`. App chấp nhận dải IP 100.x của Tailscale.

Khi `MEDIASTUDIO_REMOTE_ADMIN=true` (mặc định trong `server.env`), thiết bị đã nhập PIN làm được mọi việc như ngồi trên Mac: đổi Cài đặt, đăng nhập dịch vụ, ra lệnh tự do. Đổi thành `false` nếu muốn thiết bị khác chỉ có quyền hạn chế.

## 5. Trợ lý chat

Bấm nút tròn 💬 ở góc phải dưới của bất kỳ trang nào, rồi nhắn bằng tiếng Việt. Ví dụ:

- "Làm video lịch sử về trận Bạch Đằng năm 938"
- "Dự án này còn cảnh nào chưa có hình? Tạo nốt đi"
- "Tạo giọng đọc rồi xuất video cho dự án này"
- "Lên lịch đăng 3 truyện cổ tích, mỗi ngày 1 bài lúc 20h"

Cách hoạt động: mỗi tin nhắn chạy `claude -p --resume <phiên>` trên Mac, với một MCP server riêng (`app-mcp.mjs`). MCP server này **chỉ gọi được REST API của app**: không có Bash, không ghi file. Trợ lý tự chờ các tác vụ dài và báo kết quả. Trang đang mở tự cập nhật khi trợ lý thay đổi dự án. Trước khi đăng công khai, xoá dữ liệu hay đổi Cài đặt, trợ lý luôn hỏi lại bạn.

Cuộc trò chuyện được lưu ở `~/MediaStudio/assistant/chat.json`, nên mọi thiết bị đều thấy cùng một cuộc trò chuyện. Nút ↺ bắt đầu cuộc trò chuyện mới, bản cũ được giữ lại thành `chat-<thời gian>.json`.
Model của trợ lý mặc định là `sonnet`. Đổi trong **Cài đặt → Công cụ & thư mục → Model của Trợ lý chat**.

## 6. Đăng nhập Google Flow / TikTok Studio (cần màn hình Mac một lần)

Hai dịch vụ này chạy bằng Chrome trên Mac, với profile riêng ở `~/MediaStudio/browser-profile`. Lần đầu, bạn cần thấy màn hình Mac để đăng nhập:

- Ngồi trực tiếp tại Mac, **hoặc** bật **Cài đặt hệ thống → Chung → Chia sẻ → Chia sẻ màn hình** rồi kết nối từ máy khác: trên Mac khác dùng Finder → Đi → Kết nối máy chủ → `vnc://<IP>`; trên Windows dùng ứng dụng VNC Viewer.
- Trong app, vào **Cài đặt**, bấm **"Mở Chrome để đăng nhập Google Flow"** (hoặc nút đăng nhập TikTok). Đăng nhập xong thì **đóng Chrome**.

Canva MCP: chạy `claude` trong Terminal trên Mac, gõ `/mcp`, chọn `canva`, rồi chọn Authenticate.

## 7. Cập nhật phiên bản mới

```bash
cd ~/TiktokMedia
./deploy/mac/update.sh      # git pull + build + khởi động lại dịch vụ
```

## Xử lý sự cố

| Hiện tượng | Cách xử lý |
|---|---|
| Trợ lý báo "Không chạy được Claude CLI" | Kiểm tra token trong `server.env`, rồi chạy `claude -p "hi"` trong Terminal |
| Mở từ máy khác báo "Truy cập từ thiết bị khác đang tắt" | `MEDIASTUDIO_LAN_PIN` đang trống, hoặc máy khác không cùng mạng LAN/Tailscale |
| Trợ lý báo lỗi công cụ / không gọi được app | Chạy `node --version` (cần Node 18+). Đường dẫn node đặt trong Cài đặt → "Node.js" |
| Giọng đọc lỗi | Xem `~/MediaStudio/vieneu-server.log`; đường dẫn python của venv đặt trong Cài đặt → Giọng đọc |
| Server không lên | Xem `tail -100 ~/MediaStudio/server.log` |
