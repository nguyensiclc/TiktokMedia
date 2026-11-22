#!/usr/bin/env bash
# Runs the Media Studio server in the foreground (launchd runs this same script).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/deploy/mac/env.sh"

ENVF="$HOME/.mediastudio/server.env"
if [[ -f "$ENVF" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENVF"
  set +a
fi
[[ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]] && unset CLAUDE_CODE_OAUTH_TOKEN  # empty = use `claude` login instead

JAR="$ROOT/backend/target/backend.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Chưa có $JAR — chạy ./deploy/mac/build.sh trước" >&2
  exit 1
fi

cd "$ROOT"
# caffeinate -i: the Mac must not fall asleep while the server runs (scheduled posts, long renders).
exec caffeinate -i java -Dfile.encoding=UTF-8 -Xmx2g -jar "$JAR" --server.port="${PORT:-8787}"
