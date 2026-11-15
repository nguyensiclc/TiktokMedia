# Publishes docs/site to a public GitHub repo with GitHub Pages enabled.
# Result: https://<your-github-user>.github.io/media-studio-site/  (+ terms.html, privacy.html)
$ErrorActionPreference = 'Stop'
$repo = 'media-studio-site'
$src  = Join-Path $PSScriptRoot 'site'
$user = gh api user --jq .login
$work = Join-Path $env:TEMP $repo

Write-Host "GitHub account: $user"
Write-Host "Will create PUBLIC repo $user/$repo containing:" -ForegroundColor Yellow
Get-ChildItem $src | ForEach-Object { Write-Host "  - $($_.Name)" }
$ok = Read-Host "Publish these files publicly? (y/N)"
if ($ok -ne 'y') { Write-Host 'Cancelled.'; exit 1 }

if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory $work | Out-Null
Copy-Item (Join-Path $src '*') $work
Set-Content (Join-Path $work '.nojekyll') '' -Encoding ascii

Push-Location $work
git init -q -b main
git add -A
if (-not (git config user.name))  { git config user.name $user }
if (-not (git config user.email)) { git config user.email "$user@users.noreply.github.com" }
git commit -q -m "Media Studio website: home, terms of service, privacy policy"
gh repo create "$user/$repo" --public --description "Website, Terms of Service and Privacy Policy for Media Studio" --source . --remote origin --push
Pop-Location

# Turn on GitHub Pages from the main branch root.
gh api -X POST "repos/$user/$repo/pages" -f "source[branch]=main" -f "source[path]=/" | Out-Null

$base = "https://$user.github.io/$repo"
Write-Host ""
Write-Host "Done. GitHub Pages can take 1-2 minutes to go live:" -ForegroundColor Green
Write-Host "  Web/Desktop URL      : $base/"
Write-Host "  Terms of Service URL : $base/terms.html"
Write-Host "  Privacy Policy URL   : $base/privacy.html"
