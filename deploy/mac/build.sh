#!/usr/bin/env bash
# Builds the Vue UI into the Spring Boot jar: backend/target/backend.jar
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/deploy/mac/env.sh"

cd "$ROOT/frontend"
npm ci --no-audit --no-fund
npm run build:app

cd "$ROOT/backend"
mvn -q -DskipTests package
echo "✔ $ROOT/backend/target/backend.jar"
