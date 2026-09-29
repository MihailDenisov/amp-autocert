#!/bin/sh
# Compile and exercise pure-Java policy code without Gradle or internet.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
javac --release 21 -d "$TMP" \
  "$ROOT/src/main/java/tech/maicraft/ampautocert/RenewalPolicy.java" \
  "$ROOT/src/main/java/tech/maicraft/ampautocert/AcmeEnvironment.java" \
  "$ROOT/src/main/java/tech/maicraft/ampautocert/AutoCertConfig.java" \
  "$ROOT/src/main/java/tech/maicraft/ampautocert/CertificateInspector.java" \
  "$ROOT/src/main/java/tech/maicraft/ampautocert/CertificatePaths.java" \
  "$ROOT/src/main/java/tech/maicraft/ampautocert/CloudflareCredentialSource.java" \
  "$ROOT/scripts/smoke/SmokeTest.java"
cp "$ROOT/config/amp-autocert.properties.example" "$TMP/"
java -cp "$TMP" SmokeTest
