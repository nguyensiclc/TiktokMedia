#!/usr/bin/env bash
# Media Studio — one-time setup of a Mac as the server.
# Installs the tools with Homebrew, builds the app, writes ~/.mediastudio/server.env and (optionally) VieNeu-TTS.
# Usage:  ./deploy/mac/setup.sh            (from the repository root)
#         ./deploy/mac/setup.sh --no-tts   (skip the Vietnamese voice engine)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WITH_TTS=1
[[ "${1:-}" == "--no-tts" ]] && WITH_TTS=0

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- Homebrew
if ! command -v brew >/dev/null 2>&1; then
  say "Cài Homebrew"
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi
if [[ -x /opt/homebrew/bin/brew ]]; then eval "$(/opt/homebrew/bin/brew shellenv)"; else eval "$(/usr/local/bin/brew shellenv)"; fi
BREW_PREFIX="$(brew --prefix)"

say "Cài Java 21, Maven, Node.js, FFmpeg"
brew install openjdk@21 maven node ffmpeg
export JAVA_HOME="$BREW_PREFIX/opt/openjdk@21"
export PATH="$JAVA_HOME/bin:$BREW_PREFIX/bin:$HOME/.local/bin:$PATH"

if [[ ! -d "/Applications/Google Chrome.app" ]]; then
  say "Cài Google Chrome (dùng cho Google Flow và đăng TikTok Studio)"
  brew install --cask google-chrome || echo "⚠ Không cài được Chrome tự động — hãy cài tay từ google.com/chrome"
fi

# ---------------------------------------------------------------- Claude Code CLI
if ! command -v claude >/dev/null 2>&1; then
  say "Cài Claude Code CLI"
  npm install -g @anthropic-ai/claude-code
fi
claude --version || true

# ---------------------------------------------------------------- Build
say "Build giao diện + backend"
"$ROOT/deploy/mac/build.sh"

# ---------------------------------------------------------------- VieNeu-TTS (Vietnamese voices)
VENV="$HOME/MediaStudio/vieneu-venv"
if [[ $WITH_TTS == 1 ]]; then
  say "Cài VieNeu-TTS (giọng đọc tiếng Việt) vào $VENV"
  brew install python@3.12
  if [[ ! -x "$VENV/bin/python" ]]; then
    "$BREW_PREFIX/opt/python@3.12/bin/python3.12" -m venv "$VENV"
  fi
  "$VENV/bin/pip" install --upgrade pip
  "$VENV/bin/pip" install vieneu || echo "⚠ Cài vieneu lỗi — xem lại sau, app vẫn chạy (chọn chế độ Script hoặc Voicebox)."
fi

# ---------------------------------------------------------------- Settings
mkdir -p "$HOME/.mediastudio" "$HOME/MediaStudio"
SETTINGS="$HOME/.mediastudio/settings.json"
if [[ ! -f "$SETTINGS" ]]; then
  say "Tạo cài đặt mặc định cho macOS"
  PY=""
  [[ -x "$VENV/bin/python" ]] && PY="$VENV/bin/python"
  cat > "$SETTINGS" <<JSON
{
  "workspaceDir": "$HOME/MediaStudio",
  "claudePath": "claude",
  "nodePath": "node",
  "ffmpegPath": "ffmpeg",
  "ffprobePath": "ffprobe",
  "vieneuPython": "$PY",
  "vieneuDevice": "auto",
  "chromePath": "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
}
JSON
fi

ENVF="$HOME/.mediastudio/server.env"
if [[ ! -f "$ENVF" ]]; then
  say "Tạo $ENVF"
  PIN="$(LC_ALL=C tr -dc '0-9' </dev/urandom | head -c 6)"
  cat > "$ENVF" <<ENV
# Media Studio server — đọc bởi deploy/mac/run.sh. File này chứa bí mật: KHÔNG đưa lên git.
# Token dài hạn của Claude: chạy  claude setup-token  rồi dán vào đây.
CLAUDE_CODE_OAUTH_TOKEN=
# Mã PIN để máy khác (laptop, điện thoại) vào app qua mạng LAN / Tailscale.
MEDIASTUDIO_LAN_PIN=$PIN
# true = thiết bị đã nhập PIN được làm mọi việc (đổi Cài đặt, đăng nhập dịch vụ…), vì Mac chạy như máy chủ.
MEDIASTUDIO_REMOTE_ADMIN=true
# Cổng web của app.
PORT=8787
ENV
  chmod 600 "$ENVF"
fi

say "Xong!"
cat <<TXT
Bước tiếp theo:
  1) Tạo token Claude:     claude setup-token
     rồi dán token vào     $ENVF   (dòng CLAUDE_CODE_OAUTH_TOKEN=)
  2) Chạy thử:             ./deploy/mac/run.sh
  3) Chạy nền tự khởi động: ./deploy/mac/install-service.sh
  Mở từ máy khác:          http://<IP-của-Mac>:8787   (PIN trong $ENVF)
TXT
