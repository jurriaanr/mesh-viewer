#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Bumps the app version, builds the installer, commits and tags the release, and pushes it.

.DESCRIPTION
    The version lives in gradle.properties. Windows only upgrades an installed MSI when
    the new version is higher, so every installer you hand out needs a fresh number.

    Order matters: the version file is only committed and tagged after the build has
    succeeded, and it is restored if the build fails, so a broken build never burns a
    version number.

    Pushing the tag starts .github/workflows/release.yml, which publishes the GitHub
    release and submits the version to winget. Use -NoPush to stop after tagging.

.EXAMPLE
    pwsh scripts/release.ps1 -Bump patch -DryRun
#>
param(
    [ValidateSet('patch', 'minor', 'major')]
    [string]$Bump = 'patch',
    # Show what would happen without changing, building or committing anything.
    [switch]$DryRun,
    # Commit and tag locally but do not push (no GitHub release, no winget submission).
    [switch]$NoPush
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

function Fail([string]$message) {
    Write-Host "release: $message" -ForegroundColor Red
    exit 1
}

# ---- Preconditions -------------------------------------------------------------

# The tag must point at exactly the code that was built.
$dirty = git status --porcelain
if ($LASTEXITCODE -ne 0) { Fail 'not a git repository' }
if ($dirty) { Fail "uncommitted changes; commit or stash them first:`n$($dirty -join "`n")" }

$propsFile = 'gradle.properties'
$props = Get-Content $propsFile -Raw
if ($props -notmatch '(?m)^version=(\d+)\.(\d+)\.(\d+)\s*$') {
    Fail "no 'version=X.Y.Z' line in $propsFile"
}
$major = [int]$Matches[1]; $minor = [int]$Matches[2]; $patch = [int]$Matches[3]
$current = "$major.$minor.$patch"

switch ($Bump) {
    'major' { $major++; $minor = 0; $patch = 0 }
    'minor' { $minor++; $patch = 0 }
    'patch' { $patch++ }
}
$next = "$major.$minor.$patch"
$tag = "v$next"

# MSI ProductVersion limits; jpackage refuses anything larger.
if ($major -gt 255 -or $minor -gt 255 -or $patch -gt 65535) {
    Fail "$next is outside the MSI range (255.255.65535)"
}
if (git tag --list $tag) { Fail "tag $tag already exists" }

if (-not $NoPush) {
    # Check before building: a failed push after tagging would leave a release half done.
    $branch = git rev-parse --abbrev-ref HEAD
    if ($branch -ne 'main') { Fail "on branch '$branch'; releases are pushed from main (or use -NoPush)" }
    git fetch -q origin main
    if ($LASTEXITCODE -ne 0) { Fail 'git fetch failed; check the network or use -NoPush' }
    if ((git rev-list --count HEAD..origin/main) -ne '0') { Fail 'origin/main has commits you do not have; pull first' }
}

if ($IsWindows) {
    $gradle = '.\gradlew.bat'; $task = 'packageMsi'; $outDir = 'build/compose/binaries/main/msi'
} else {
    $gradle = './gradlew'; $task = 'packageDeb'; $outDir = 'build/compose/binaries/main/deb'
}

$pushNote = if ($NoPush) { 'no push' } else { 'then push' }
Write-Host "release: $current -> $next ($Bump), build with '$gradle $task', $pushNote"
if ($DryRun) {
    Write-Host 'release: dry run, nothing changed'
    exit 0
}

# ---- Bump and build ------------------------------------------------------------

# Replace only the version line; -NoNewline keeps the file's own line endings.
$props -replace '(?m)^version=.*$', "version=$next" | Set-Content $propsFile -NoNewline

& $gradle $task
if ($LASTEXITCODE -ne 0) {
    git checkout -- $propsFile
    Fail "build failed; $propsFile restored to $current"
}

# ---- Commit and tag ------------------------------------------------------------

git add $propsFile
git commit -q -m "chore(release): $next"
if ($LASTEXITCODE -ne 0) { Fail 'git commit failed (the installer was built)' }
git tag -a $tag -m "Release $next"
if ($LASTEXITCODE -ne 0) { Fail 'git tag failed (the version commit was made)' }

$installer = Get-ChildItem $outDir -Filter "*$next*" | Select-Object -First 1
Write-Host ''
Write-Host "release: $tag built and tagged" -ForegroundColor Green
if ($installer) { Write-Host "release: installer $($installer.FullName)" }

if ($NoPush) {
    Write-Host 'release: not pushed; publish with   git push --follow-tags'
    exit 0
}

# --atomic: the commit and the tag land together or not at all.
git push --atomic --follow-tags origin main
if ($LASTEXITCODE -ne 0) { Fail 'git push failed; the release is committed and tagged locally, retry with   git push --follow-tags' }
Write-Host "release: pushed; GitHub Actions now builds the release and submits it to winget" -ForegroundColor Green
