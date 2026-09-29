#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEST="$ROOT/gradle/wrapper/gradle-wrapper.jar"
JAR_URI='https://raw.githubusercontent.com/gradle/gradle/v8.14.3/gradle/wrapper/gradle-wrapper.jar'
CHECKSUM_URI='https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256'
mkdir -p "$(dirname "$DEST")"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
if command -v curl >/dev/null 2>&1; then
  curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 "$JAR_URI" -o "$TMP/wrapper.jar"
  curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 "$CHECKSUM_URI" -o "$TMP/sha256"
elif command -v wget >/dev/null 2>&1; then
  wget -q "$JAR_URI" -O "$TMP/wrapper.jar"
  wget -q "$CHECKSUM_URI" -O "$TMP/sha256"
else
  echo 'Install curl or wget, or run: gradle wrapper --gradle-version 8.14.3' >&2
  exit 1
fi
EXPECTED=$(tr -d ' \t\r\n' < "$TMP/sha256")
case "$EXPECTED" in *[!0-9a-fA-F]*|'') echo 'Unexpected wrapper checksum' >&2; exit 1;; esac
[ "${#EXPECTED}" -eq 64 ] || { echo 'Unexpected SHA-256 length' >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$TMP/wrapper.jar" | cut -d ' ' -f1)
else
  ACTUAL=$(shasum -a 256 "$TMP/wrapper.jar" | cut -d ' ' -f1)
fi
[ "$ACTUAL" = "$EXPECTED" ] || { echo 'Wrapper SHA-256 mismatch' >&2; exit 1; }
mv "$TMP/wrapper.jar" "$DEST"
echo 'Official Gradle wrapper JAR downloaded and verified.'
