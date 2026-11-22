# Media Studio

Ứng dụng desktop tạo video: **Claude CLI** viết kịch bản và điều khiển các MCP để lấy hình từ **Google Flow** / **Canva**, **Voicebox** đọc lời thoại, **FFmpeg** ghép thành video MP4 + phụ đề SRT.

```
desktop/   Electron: tự chạy backend.jar ở cổng trống rồi mở UI
frontend/  Vue 3 + Vite (build vào backend/src/main/resources/static)
backend/   Spring Boot 3 / Java 21: dự án, job nền, Claude CLI, Voicebox, FFmpeg
deploy/mac Script cài & chạy trên MacBook làm máy chủ
```

💬 **Trợ lý chat**: nút tròn ở góc phải dưới. Bạn trò chuyện bằng tiếng Việt, Claude CLI điều khiển app thay bạn (tạo dự án, viết kịch bản, tạo hình, giọng, xuất video, đăng TikTok). Trợ lý chỉ gọi REST API của app, qua `backend/src/main/resources/assistant/app-mcp.mjs`.

🖥 **Chạy trên MacBook làm máy chủ**: xem [docs/MAC-SERVER.md](docs/MAC-SERVER.md). Tóm tắt: `./deploy/mac/setup.sh`, rồi `claude setup-token`, rồi `./deploy/mac/install-service.sh`.

## Luồng làm video

⚡ **Bắt đầu từ hình ảnh** (nhanh nhất): dán (Ctrl+V) hoặc kéo thả mọi hình liên quan (trang sách, bài thơ, tranh, ảnh sản phẩm) rồi bấm **✨ Phân tích & tạo kịch bản**. Claude đọc hình rồi:
- chép lại chữ nguyên văn, bỏ nhãn sách và số trang;
- tự nhận diện nhân vật, kể cả nhân vật chỉ được nhắc trong chữ, và cắt ảnh tham chiếu cho nhân vật có trong tranh bằng FFmpeg;
- chọn phong cách hình và số cảnh;
- viết kịch bản. Với thơ hoặc truyện, lời thoại dùng đúng nguyên văn và giữ ngắt dòng.

0. **Nhân vật tham chiếu** (tuỳ chọn): tải lên ảnh nhân vật, linh vật hoặc sản phẩm có sẵn và đặt tên. Claude **xem ảnh** khi viết bộ quy tắc hình ảnh. Nếu ô phong cách để trống, Claude theo phong cách của ảnh. Canva tải ảnh lên một lần (`create-upload-url`) rồi gửi kèm làm `imageReferences` cho mọi cảnh. Flow đưa ảnh vào Ingredients bằng `browser_file_upload`. Tối đa 3 ảnh được dùng làm tham chiếu.
1. **Kịch bản**: nhập chủ đề, Claude CLI (`claude -p`) ghi `storyboard.json` gồm lời thoại + prompt hình cho từng cảnh.
2. **Hình ảnh** cho từng cảnh:
   - 🌀 **Google Flow**: Claude dùng **Playwright MCP** mở `labs.google/fx/tools/flow` bằng profile Chrome riêng, tạo ảnh/video rồi lưu vào `assets/`.
   - 🎨 **Canva**: mỗi dự án có **một thư mục Canva** và **một design storyboard** (mỗi cảnh là một trang, đúng kích thước video). Với mỗi cảnh, Claude chạy `generate-image`, đặt ảnh lên trang của cảnh, export PNG đúng độ phân giải rồi tải về. Bước đặt ảnh lên trang là bắt buộc, vì riêng `generate-image` chỉ trả về ảnh xem trước khoảng 200px. Nút **"Canva tất cả"** tạo lần lượt từng cảnh.

   **Giữ nhân vật đồng bộ:** khi tạo kịch bản, Claude viết một **bộ quy tắc hình ảnh** (mô tả cố định từng nhân vật và phong cách chung). Bộ quy tắc này được chèn vào mọi prompt hình (Canva và Flow). Ảnh Canva đầu tiên trở thành **⭐ ảnh tham chiếu** và được gửi kèm (`imageReferences`) khi tạo các cảnh sau. Prompt từng cảnh ghi rõ nhân vật nào có mặt và nhân vật nào vắng mặt.
   - ⬆ Hoặc tự tải file lên.
3. **Giọng đọc** (chọn trong Cài đặt):
   - **VieNeu-TTS 3.8.x** (mặc định, tiếng Việt): backend tự chạy `python -m apps.openai_speech` từ venv đã cài `vieneu` (cổng 8100), rồi gọi `POST /v1/audio/speech` (PCM 48 kHz, đóng gói lại thành WAV). Có 25 giọng sẵn, mặc định là "Hải Đăng". Log của server nằm ở `~/MediaStudio/vieneu-server.log`.
   - **Voicebox**: `POST /generate` → `/generate/{id}/status` → `/audio/{id}`.
   - **Tốc độ đọc** (×0.5–×1.5): đổi bằng FFmpeg `atempo` sau khi tạo audio, giữ nguyên cao độ giọng.
   - **Script cho người đọc** (khung "Lời & nhạc nền"): không tạo audio, lời hiện ngay trên hình. Mỗi cảnh, app tìm vùng ít chi tiết nhất của hình (ưu tiên mép trên/dưới), vẽ chữ không nền, màu lấy từ ảnh (tông sáng của màu chủ đạo trên vùng tối, tông đậm trên vùng sáng, viền ngược tông). Thời lượng cảnh = số từ / 3 giây (tối thiểu 2 giây). Chữ của mọi cảnh trừ cảnh cuối kết thúc bằng "..." để người đọc biết còn tiếp (áp dụng cả cho video script và bộ ảnh xuất ra).
   - **Nhạc nền** (tuỳ chọn, mọi chế độ): một file nhạc không lời phát suốt video, tự lặp, vào/ra mờ dần, âm lượng chỉnh được; ở chế độ giọng đọc nhạc tự nhỏ lại khi có giọng.
   - **Mỗi dự án có giọng và tốc độ riêng** (khung "🎙 Giọng đọc của dự án"). Dự án chưa cài riêng thì dùng mặc định trong Cài đặt.
4. **Render**: FFmpeg tạo một đoạn cho mỗi cảnh (hiệu ứng zoom Ken Burns cho ảnh tĩnh, lặp clip cho video), rồi nối lại → `output/video-*.mp4` + `.srt`.
5. **Đăng TikTok**:
   - **Mô tả:** Claude CLI viết caption và hashtag từ kịch bản (sửa được, tối đa 2200 ký tự).
   - **Kết nối tài khoản:** dùng TikTok Login Kit for Desktop (OAuth + PKCE, redirect `http://127.0.0.1:*/api/tiktok/callback/`).
   - **Đăng video:** dùng Content Posting API, theo một trong hai cách:
     - *Gửi vào hộp nháp* (`video.upload`): bạn mở app TikTok để hoàn tất và đăng.
     - *Đăng thẳng* (`video.publish`): form theo đúng quy định UX của TikTok (tên tài khoản, quyền riêng tư không có mặc định, các công tắc mặc định tắt, khai báo nội dung thương mại, đồng ý Music Usage, nhãn AI).
   - Token lưu ở `~/.mediastudio/tiktok.json`.
   - Bạn cần tự tạo app trên developers.tiktok.com. Khi app chưa được TikTok duyệt, video đăng thẳng chỉ ở chế độ "Chỉ mình tôi".
   **Công cụ AI:** viết kịch bản và viết mô tả TikTok chạy được bằng Claude CLI hoặc **Antigravity CLI (`agy`)**, chọn trong Cài đặt → Công cụ. Phân tích hình, và viết kịch bản cho dự án có ảnh nhân vật, luôn dùng Claude (agy mở được file ảnh nhưng không nhìn được nội dung). Canva/Flow luôn dùng Claude vì cần MCP.
6. **Lệnh tự do**: gửi một prompt bất kỳ cho Claude, có sẵn mọi MCP đang bật (Playwright, Canva, Voicebox).

Mỗi tác vụ Claude chạy như sau:

```
claude -p --output-format stream-json --verbose --permission-mode acceptEdits \
  --mcp-config <project>/.mcp-run.json --strict-mcp-config \
  --allowedTools mcp__playwright,Bash(mv:*),... --append-system-prompt "..."   < prompt
```

`stream-json` được chuyển thành log và đẩy về UI qua SSE (`/api/jobs/{id}/stream`).

## Yêu cầu

- Java 21, Maven, Node 18+ (để chạy `npx @playwright/mcp`), FFmpeg/FFprobe trong PATH
- Claude Code CLI đã đăng nhập (`claude`)
- Một venv Python ≥ 3.10 đã `pip install vieneu` (VieNeu-TTS), hoặc ứng dụng Voicebox đang chạy
- Google Chrome (cho Google Flow)

## Chạy

**Dev** (sửa UI có hot-reload):

```bash
cd backend && mvn spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

Mở http://localhost:5173, hoặc chạy trong Electron: `cd desktop && npm install && npm run dev`.

**Chỉnh sửa timeline** (nút "🎞 Chỉnh sửa timeline" trong dự án): cảnh, chữ, giọng và nhạc trên một trục thời gian, có xem trước. Kéo mép cảnh để đổi độ dài, kéo cả cảnh để đổi thứ tự, sửa chữ/hình/giọng ở khung bên phải. "Xuất video" chỉ dựng lại cảnh đã đổi (mỗi cảnh đã render được lưu trong `output/cache`), nên sửa nhỏ xuất lại trong vài giây.

**Dùng trên điện thoại** (Wi-Fi nhà): Cài đặt → "Dùng trên điện thoại" → bật, mở địa chỉ hiện ra trên Safari và nhập PIN. Giao diện tự gọn lại cho màn hình nhỏ (iPhone Pro Max ≈ 440px).

**App desktop**:

```powershell
./build.ps1
```

```bash
cd desktop && npm install && npm start
```

**Bộ cài Windows** (kèm JRE rút gọn qua `jlink`, người dùng không cần cài Java):

```powershell
./build.ps1 -Installer
```

## Đăng nhập một lần (trong trang Cài đặt)

| Dịch vụ | Cách đăng nhập |
|---|---|
| Google Flow | Bấm **"Mở Chrome để đăng nhập Google Flow"**. Chrome mở với profile `workspace/browser-profile`, đăng nhập Google rồi **đóng Chrome** (Playwright không dùng được profile khi nó đang mở). |
| Canva | Bấm **"Mở Claude CLI để đăng nhập"**, gõ `/mcp`, chọn `canva`, chọn Authenticate. |
| Voicebox | Không cần đăng nhập. Chọn voice profile mặc định trong Cài đặt. |

## Dữ liệu

- Cài đặt: `~/.mediastudio/settings.json`
- Dự án: `~/MediaStudio/projects/<id>/` gồm `project.json`, `assets/`, `downloads/`, `output/`

## Lưu ý

- **Google Flow không có API**, nên phần này dựa vào việc Claude điều khiển trình duyệt. Nếu Google đổi giao diện thì có thể hỏng. Khi đó chỉnh prompt trong `backend/.../agent/AgentPrompts.java`.
- URL Canva MCP mặc định là `https://mcp.canva.com/mcp`. URL này chưa được kiểm chứng, đổi trong Cài đặt nếu Canva công bố địa chỉ khác.
- Voicebox chưa hỗ trợ tiếng Việt (`vi`), nên với tiếng Việt hãy dùng VieNeu-TTS. Các profile dạng *preset* cần engine `qwen_custom_voice`, và Voicebox sẽ tự tải model này ở lần dùng đầu.
