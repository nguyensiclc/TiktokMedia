#!/usr/bin/env bash
# Installs Media Studio as a LaunchAgent: starts when you log in, restarts if it crashes.
# (A LaunchAgent, not a daemon: Chrome for Google Flow / TikTok Studio needs the logged-in user's session.)
# Usage: ./deploy/mac/install-service.sh            install / reinstall and start
#        ./deploy/mac/install-service.sh --remove   stop and remove
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LABEL="com.mediastudio.server"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/MediaStudio/server.log"
DOMAIN="gui/$(id -u)"

launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
if [[ "${1:-}" == "--remove" ]]; then
  rm -f "$PLIST"
  echo "✔ Đã gỡ dịch vụ $LABEL"
  exit 0
fi

mkdir -p "$HOME/Library/LaunchAgents" "$HOME/MediaStudio"
cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$ROOT/deploy/mac/run.sh</string>
  </array>
  <key>WorkingDirectory</key><string>$ROOT</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>15</integer>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
PLIST

launchctl bootstrap "$DOMAIN" "$PLIST"
launchctl kickstart -k "$DOMAIN/$LABEL"
echo "✔ Đã cài và chạy $LABEL"
echo "  Log:      tail -f $LOG"
echo "  Khởi động lại: launchctl kickstart -k $DOMAIN/$LABEL"
echo "  Gỡ:       $0 --remove"
