Tôi muốn app này dùng được extension Chrome "Media Studio → Flow" (đã cài sẵn trong Chrome của tôi) để tạo ảnh
bằng Google Flow. Extension không đổi gì: app này chỉ cần làm một máy chủ HTTP cục bộ theo đúng giao thức dưới
đây. Hãy đọc code của app, chọn chỗ hợp lý để thêm (backend + nút trên giao diện), rồi làm và tự kiểm tra.

## Cách extension hoạt động
- Extension chạy trong Chrome của người dùng (đã đăng nhập Google Flow). Nó gọi app qua HTTP ở `http://localhost:<port>`
  hoặc `http://127.0.0.1:<port>` — CHỈ hai host này (không dùng IP LAN, không https).
- App xếp một HÀNG ĐỢI các ảnh cần tạo. Extension lấy hàng đợi, với mỗi việc: (gắn ảnh tham chiếu nếu có) → gõ prompt
  vào Flow → bấm Tạo → chờ ảnh xong → tải ảnh về → gửi lại app. Nó làm tuần tự, nghỉ `pauseSeconds` giữa các ảnh.
- Nếu project chưa có `flowProjectUrl`, extension tự tạo project mới trong Flow và báo lại URL để app lưu dùng lần sau.
- Gặp "hoạt động bất thường"/CAPTCHA của Google thì extension dừng hẳn (không tìm cách vượt qua).
- Gọi từ service worker của extension nên KHÔNG cần CORS. Mọi body JSON là UTF-8.

## API app phải có (đường dẫn giữ nguyên đúng như sau)
Lỗi: trả HTTP 4xx/5xx với JSON `{"error": "<thông báo tiếng Việt>"}`.

1. `GET /api/flow-ext/ping` → `{"app": "<tên app>", "ok": true, "downloadDir": "<đường dẫn tuyệt đối>"}`
   - `downloadDir`: thư mục (tạo sẵn, nằm trong thư mục home của user) để Chrome lưu ảnh tải từ Flow mà không hiện
     hộp thoại Save. Ví dụ `C:\Users\<user>\<TenApp>\flow-downloads`.

2. `GET /api/flow-ext/session` → hàng đợi hiện tại. Khi không có gì: `{"active": false}`. Khi có:
   ```json
   {
     "active": true,              // còn việc pending/working và chưa bị dừng
     "projectId": "abc",
     "projectName": "Tên hiển thị", // dùng đặt tên project Flow mới: "Media Studio - <projectName>"
     "flowProjectUrl": null,      // hoặc "https://flow.google.com/project/<id>" đã lưu
     "aspect": "9:16",            // một trong 16:9, 4:3, 1:1, 3:4, 9:16 — extension chỉnh Flow theo giá trị này
     "pauseSeconds": 5,
     "stopped": false,
     "stopReason": null,
     "jobId": "id-duy-nhat-cho-moi-lan-xep-hang", // extension dùng để không mở Flow hai lần cho cùng một hàng đợi
     "tasks": [
       {
         "sceneId": "s1",         // id ổn định, dùng trong URL bên dưới
         "n": 1,                  // số thứ tự hiển thị
         "prompt": "toàn bộ câu lệnh tạo ảnh",
         "refs": [],              // ảnh tham chiếu (có thể để rỗng — nhanh hơn nhiều)
         "state": "pending",      // pending | working | done | failed
         "message": null
       }
     ]
   }
   ```
   - Mỗi phần tử `refs` (nếu dùng): `{"name": "ten-file.png", "role": "CHARACTER Lan", "url": "/duong-dan/tai/anh",
     "path": "<đường dẫn tuyệt đối trên máy>"}`. `url` là đường dẫn tương đối trên app (extension GET nó để lấy ảnh),
     `path` để extension đưa thẳng vào hộp chọn tệp của Flow. `name` nên duy nhất.
   - Mẹo nhất quán khi không dùng ảnh tham chiếu: mọi prompt mở đầu bằng CÙNG một khối mô tả phong cách + từng nhân vật
     (mặt, tóc, tuổi, quần áo, màu), rồi mới tới phần riêng của ảnh; thêm luật "không có chữ trong ảnh", bố cục
     full-bleed đúng tỉ lệ, giải phẫu đúng.

3. `POST /api/flow-ext/tasks/{sceneId}/start` → `{"ok": true}`: đặt state = "working" (nếu đang pending).
4. `POST /api/flow-ext/tasks/{sceneId}/fail` body `{"message": "..."}` → `{"ok": true}`: state = "failed", lưu message.
   Nếu message chứa "unusual", "bất thường" hoặc "captcha" → dừng cả hàng đợi (stopped = true, stopReason).
5. `POST /api/flow-ext/tasks/{sceneId}/result` multipart, field `file` (jpg/png/webp) → `{"file": "<tên đã lưu>"}`:
   kiểm tra ≥ 1000 byte, đoán đuôi từ magic bytes, lưu ảnh, gán cho đúng mục trong app, state = "done".
6. `POST /api/flow-ext/tasks/{sceneId}/result-path` body `{"path": "<đường dẫn tuyệt đối>"}` → `{"file": "..."}`:
   giống (5) nhưng đọc từ file Chrome vừa tải. BẢO MẬT: chỉ chấp nhận file ảnh (jpg/png/webp) nằm trong thư mục home
   của user, ≤ 60 MB; còn lại trả 400. Sao chép (đừng di chuyển) file vào nơi lưu của app.
7. `POST /api/flow-ext/project` body `{"url": "https://flow.google.com/project/<id>/..."}` → `{"ok": true}`:
   bỏ phần `/edit/...` nếu có, lưu làm `flowProjectUrl` của project đang chạy (dùng lại lần sau).
8. `POST /api/flow-ext/stop` body `{"reason": "..."}` → `{"ok": true}`: stopped = true, lưu lý do; các việc pending
   chuyển failed (hoặc giữ nguyên nhưng `active` phải thành false).

Quy tắc hàng đợi: mỗi lúc chỉ MỘT hàng đợi; xếp hàng mới khi hàng cũ còn việc mở thì trả lỗi 409. Hàng đợi giữ trong
bộ nhớ là đủ. Nếu app có trang theo dõi tiến độ, hiển thị state/message của từng việc.

## Giao diện
- Nút "🌀 Tạo bằng Flow (extension)" (cho tất cả mục chưa có ảnh, và cho từng mục): gọi API nội bộ để xếp hàng, xong
  thì báo extension mở Flow ngay bằng đúng dòng này trên trang web của app:
  ```js
  window.postMessage({ source: 'media-studio', type: 'flow-ext-queued' }, window.location.origin)
  ```
  (Giữ nguyên chữ 'media-studio' — đó là tên tín hiệu của extension, không phải tên app.) Extension sẽ tự chuyển sang
  làm việc với app đang gửi tín hiệu (theo `location.origin` của trang).
- Phát hiện đã cài extension: `document.documentElement.dataset.msFlowExt` có giá trị (số phiên bản) sau khi trang tải.
  Nếu không có, hiện hướng dẫn: cài extension từ thư mục `D:\Code_Carrer\ClaudeCer\Media\flow-extension`
  (chrome://extensions → Developer mode → Load unpacked) và mở app trong Chrome qua `http://localhost:<port>`.
- Extension trả lời tín hiệu bằng `window.postMessage({source:'media-studio-ext', type:'flow-ext-woken', ok, data, error})`
  — có thể nghe để báo "Đã mở Flow".
- Trang phải mở trong Chrome ở localhost/127.0.0.1 thì tín hiệu mới tới extension. Nếu app là desktop (Electron…),
  extension vẫn tự thấy hàng đợi mới trong khoảng 30 giây (nó hỏi `/session` của app được dùng gần nhất), hoặc người
  dùng bấm biểu tượng extension → Bắt đầu (trong popup có ô đổi địa chỉ app).

## Kiểm tra trước khi báo xong
Viết một script nhỏ (hoặc test) gọi lần lượt: ping → xếp hàng 1 mục → session (thấy task pending) → start → result
(gửi một ảnh jpg thật) → session (task done, ảnh đã gán) → stop. Thử thêm result-path với file ngoài home → phải bị 400.
Không tự mở Flow hay tạo ảnh thật; tôi sẽ thử bằng Chrome.
