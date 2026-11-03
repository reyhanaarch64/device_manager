#!/usr/bin/env sh
# Lightweight Gradle bootstrap launcher. Downloads and verifies the pinned distribution
# on first use; subsequent runs use the local Gradle cache.
set -eu

VERSION="8.14.6"
SHA256="7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24"
ARCHIVE="gradle-${VERSION}-bin.zip"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/device-manager-bootstrap"
DIST="${BASE}/gradle-${VERSION}"
BIN="${DIST}/bin/gradle"

if [ ! -x "$BIN" ]; then
  mkdir -p "$BASE"
  TMP="${BASE}/${ARCHIVE}.part"
  echo "Gradle ${VERSION} belum tersedia; mengunduh distribusi resmi..." >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --retry-delay 2 "https://services.gradle.org/distributions/${ARCHIVE}" -o "$TMP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$TMP" "https://services.gradle.org/distributions/${ARCHIVE}"
  else
    echo "butuh curl atau wget untuk bootstrap Gradle" >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$SHA256" "$TMP" | sha256sum -c -
  elif command -v shasum >/dev/null 2>&1; then
    printf '%s  %s\n' "$SHA256" "$TMP" | shasum -a 256 -c -
  else
    echo "butuh sha256sum atau shasum untuk verifikasi distribusi" >&2
    exit 1
  fi
  command -v unzip >/dev/null 2>&1 || { echo "butuh unzip untuk ekstraksi Gradle" >&2; exit 1; }
  unzip -q "$TMP" -d "$BASE"
  rm -f "$TMP"
fi

exec "$BIN" "$@"
