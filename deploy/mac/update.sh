#!/usr/bin/env bash
# Pulls the latest code from git, rebuilds and restarts the server (if installed as a service).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
git pull --ff-only
"$ROOT/deploy/mac/build.sh"
LABEL="com.mediastudio.server"
if launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1; then
  launchctl kickstart -k "gui/$(id -u)/$LABEL"
  echo "✔ Đã khởi động lại $LABEL"
else
  echo "Dịch vụ chưa cài — chạy ./deploy/mac/run.sh hoặc ./deploy/mac/install-service.sh"
fi
