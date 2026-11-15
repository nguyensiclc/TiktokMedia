# Builds everything: Vue UI -> Spring Boot static, backend.jar, then (optionally) the Windows installer.
param([switch]$Installer)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

Push-Location "$root\frontend"
if (-not (Test-Path node_modules)) { npm install }
npm run build:app
Pop-Location

Push-Location "$root\backend"
mvn -q -DskipTests package
Pop-Location

if ($Installer) {
    Push-Location "$root\desktop"
    if (-not (Test-Path jre)) {
        # Bundle a trimmed Java runtime so end users don't need Java installed.
        jlink --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.instrument,java.sql,jdk.crypto.ec,jdk.unsupported `
              --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output jre
    }
    if (-not (Test-Path node_modules)) { npm install }
    npm run dist
    Pop-Location
}
Write-Host "Done. Run: cd desktop; npm install; npm start"
