#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# PVE Pocket — Portable Master Build & Verification Pipeline
# Supports both standard Ubuntu x86_64 (CI / Dev) and Debian / ARM64 (Raspberry Pi)
set -euo pipefail

WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$WORKSPACE"

echo "=================================================="
echo "PVE Pocket — Master Build Pipeline (v0.1.2-alpha)"
echo "=================================================="

# Detect environment: Pi user-local vs Standard Android SDK / CI
if [ -f "tools/env.sh" ]; then
    echo "Loading ARM64 Pi build environment from tools/env.sh..."
    source tools/env.sh
    JAVA_CMD="java"
    JAVAC_CMD="javac"
    KEYTOOL_CMD="$JAVA_HOME/bin/keytool"
    JAR_CMD="$JAVA_HOME/bin/jar"
    D8_CMD="java -cp $D8_JAR com.android.tools.r8.D8"
    APKSIGNER_CMD="java -jar $APKSIGNER_JAR"
elif [ -f "../../tools/env.sh" ]; then
    echo "Loading ARM64 Pi build environment from ../../tools/env.sh..."
    source ../../tools/env.sh
    JAVA_CMD="java"
    JAVAC_CMD="javac"
    KEYTOOL_CMD="$JAVA_HOME/bin/keytool"
    JAR_CMD="$JAVA_HOME/bin/jar"
    D8_CMD="java -cp $D8_JAR com.android.tools.r8.D8"
    APKSIGNER_CMD="java -jar $APKSIGNER_JAR"
else
    echo "Configuring standard Android SDK build environment..."
    JAVA_CMD="${JAVA_HOME:-}/bin/java"
    [ ! -x "$JAVA_CMD" ] && JAVA_CMD="java"
    JAVAC_CMD="${JAVA_HOME:-}/bin/javac"
    [ ! -x "$JAVAC_CMD" ] && JAVAC_CMD="javac"
    KEYTOOL_CMD="${JAVA_HOME:-}/bin/keytool"
    [ ! -x "$KEYTOOL_CMD" ] && KEYTOOL_CMD="keytool"
    JAR_CMD="${JAVA_HOME:-}/bin/jar"
    [ ! -x "$JAR_CMD" ] && JAR_CMD="jar"

    ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/lib/android-sdk}}"
    if [ -d "$ANDROID_SDK_ROOT/build-tools/29.0.3" ]; then
        BT_DIR="$ANDROID_SDK_ROOT/build-tools/29.0.3"
    elif [ -d "$ANDROID_SDK_ROOT/build-tools" ]; then
        BT_DIR=$(ls -d "$ANDROID_SDK_ROOT/build-tools/"* 2>/dev/null | sort -V | tail -n 1)
    else
        BT_DIR="$ANDROID_SDK_ROOT"
    fi

    AAPT="${BT_DIR}/aapt"
    [ ! -x "$AAPT" ] && AAPT="$(command -v aapt || true)"
    ZIPALIGN="${BT_DIR}/zipalign"
    [ ! -x "$ZIPALIGN" ] && ZIPALIGN="$(command -v zipalign || true)"

    if [ -x "${BT_DIR}/apksigner" ]; then
        APKSIGNER_CMD="${BT_DIR}/apksigner"
    elif [ -f "${BT_DIR}/lib/apksigner.jar" ]; then
        APKSIGNER_CMD="$JAVA_CMD -jar ${BT_DIR}/lib/apksigner.jar"
    elif [ -f "/usr/share/java/apksigner.jar" ]; then
        APKSIGNER_CMD="$JAVA_CMD -jar /usr/share/java/apksigner.jar"
    else
        APKSIGNER_CMD="apksigner"
    fi

    if [ -x "${BT_DIR}/d8" ]; then
        D8_CMD="${BT_DIR}/d8"
    elif [ -f "tools/r8.jar" ]; then
        D8_CMD="$JAVA_CMD -cp tools/r8.jar com.android.tools.r8.D8"
    else
        D8_CMD="d8"
    fi

    ANDROID_JAR=""
    for candidate in \
        "$ANDROID_SDK_ROOT/platforms/android-29/android.jar" \
        "$ANDROID_SDK_ROOT/platforms/android-34/android.jar" \
        "$ANDROID_SDK_ROOT/platforms/android-"*/android.jar \
        "/usr/lib/android-sdk/platforms/android-29/android.jar"; do
        if [ -f "$candidate" ]; then
            ANDROID_JAR="$candidate"
            break
        fi
    done

    TARGET_SDK=29
    MIN_SDK=26
    SIGNING_DIR="$WORKSPACE/signing"
    KEYSTORE="$SIGNING_DIR/debug.keystore"
    KEY_ALIAS="pve-pocket-debug"
fi

if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: Could not locate android.jar (platform 29)."
    exit 1
fi

echo "Build environment:"
echo "  JAVA:        $($JAVA_CMD -version 2>&1 | head -1)"
echo "  AAPT:        $AAPT"
echo "  ANDROID_JAR: $ANDROID_JAR"

# Ensure output directories
rm -rf app/build/gen app/build/obj app/build/dex app/build/test-obj
mkdir -p app/build/gen app/build/obj app/build/dex app/build/test-obj artifacts

echo ""
echo "[1/8] Compiling Android resources with AAPT..."
$AAPT package -f -m \
  -J app/build/gen \
  -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res \
  -I "$ANDROID_JAR"

echo ""
echo "[2/8] Compiling main Java sources with javac..."
find app/src/main/java app/build/gen -name "*.java" > app/build/sources.txt
$JAVAC_CMD -encoding UTF-8 \
  --release 8 \
  -cp "$ANDROID_JAR" \
  -d app/build/obj \
  @app/build/sources.txt

echo ""
echo "[3/8] Compiling behavioral test suite..."
if [ ! -f "app/src/test/resources/test_fixtures.p12" ]; then
  echo "Generating test certificate keystore in app/src/test/resources/test_fixtures.p12..."
  mkdir -p app/src/test/resources
  "$KEYTOOL_CMD" -genkeypair -alias local-ip -keyalg RSA -dname "CN=localhost" -ext "SAN=ip:127.0.0.1" -validity 365 -keystore app/src/test/resources/test_fixtures.p12 -storetype PKCS12 -storepass testpass -keypass testpass -noprompt
  "$KEYTOOL_CMD" -genkeypair -alias dns-host -keyalg RSA -dname "CN=pve.example.com" -ext "SAN=dns:pve.example.com" -validity 365 -keystore app/src/test/resources/test_fixtures.p12 -storetype PKCS12 -storepass testpass -keypass testpass -noprompt
  "$KEYTOOL_CMD" -genkeypair -alias wildcard-host -keyalg RSA -dname "CN=wildcard" -ext "SAN=dns:*.lab.example.com" -validity 365 -keystore app/src/test/resources/test_fixtures.p12 -storetype PKCS12 -storepass testpass -keypass testpass -noprompt
  "$KEYTOOL_CMD" -genkeypair -alias wrong-san -keyalg RSA -dname "CN=wrong.example" -ext "SAN=dns:wrong.example" -validity 365 -keystore app/src/test/resources/test_fixtures.p12 -storetype PKCS12 -storepass testpass -keypass testpass -noprompt
  "$KEYTOOL_CMD" -genkeypair -alias no-san -keyalg RSA -dname "CN=legacy.example.com" -validity 365 -keystore app/src/test/resources/test_fixtures.p12 -storetype PKCS12 -storepass testpass -keypass testpass -noprompt
  "$KEYTOOL_CMD" -genkeypair -alias san-cn-conflict -keyalg RSA -dname "CN=192.0.2.100" -ext "SAN=dns:other.example.com" -validity 365 -keystore app/src/test/resources/test_fixtures.p12 -storetype PKCS12 -storepass testpass -keypass testpass -noprompt
fi

# Locate JUnit & Hamcrest dependencies
JUNIT_JAR=""
HAMCREST_JAR=""
JSON_JAR=""
for j in \
    "${EXTRACTED:-}/usr/share/java/junit4.jar" \
    "/usr/share/java/junit4.jar"; do
    if [ -f "$j" ]; then JUNIT_JAR="$j"; break; fi
done
for h in \
    "${EXTRACTED:-}/usr/share/java/hamcrest.jar" \
    "${EXTRACTED:-}/usr/share/java/hamcrest-core.jar" \
    "/usr/share/java/hamcrest.jar" \
    "/usr/share/java/hamcrest-core.jar"; do
    if [ -f "$h" ]; then HAMCREST_JAR="$h"; break; fi
done
for js in \
    "${EXTRACTED:-}/usr/share/java/com.android.json.jar" \
    "/usr/share/java/com.android.json.jar" \
    "/usr/share/java/android-json.jar"; do
    if [ -f "$js" ]; then JSON_JAR="$js"; break; fi
done

TEST_DEPS="app/build/obj"
[ -n "$JUNIT_JAR" ] && TEST_DEPS="$TEST_DEPS:$JUNIT_JAR"
[ -n "$HAMCREST_JAR" ] && TEST_DEPS="$TEST_DEPS:$HAMCREST_JAR"
[ -n "$JSON_JAR" ] && TEST_DEPS="$TEST_DEPS:$JSON_JAR"
TEST_CP="$TEST_DEPS:$ANDROID_JAR"

find app/src/test/java -name "*.java" > app/build/test-sources.txt
$JAVAC_CMD -encoding UTF-8 \
  --release 8 \
  -cp "$TEST_CP" \
  -d app/build/test-obj \
  @app/build/test-sources.txt

echo ""
echo "[4/8] Running behavioral test suite..."
TEST_RUN_CP="app/build/test-obj:$TEST_CP"
$JAVA_CMD -cp "$TEST_RUN_CP" org.junit.runner.JUnitCore \
  com.homelabpocket.model.MetricAggregationTest \
  com.homelabpocket.api.TargetRoutingTest \
  com.homelabpocket.api.DoubleSubmitGuardTest \
  com.homelabpocket.api.TaskPollingLifecycleTest \
  com.homelabpocket.security.SecurityAndRedactionTest \
  com.homelabpocket.security.AuthLifecycleTest \
  com.homelabpocket.security.MissingPinTransportTest \
  com.homelabpocket.security.EndpointIdentityAndRedirectTest \
  com.homelabpocket.api.PdmSafetyTest \
  com.homelabpocket.security.TerminalSafetyTest \
  com.homelabpocket.api.NodeResourceCompletenessTest \
  com.homelabpocket.api.ProxmoxApiParsingTest \
  com.homelabpocket.api.EmptyOnboardingTest \
  com.homelabpocket.security.AuthRefreshAndTlsOptionTest \
  com.homelabpocket.security.AddServerActivityTransactionalTest \
  com.homelabpocket.api.PdmUpstreamAuthRegressionTest \
  com.homelabpocket.ui.UiPolishViewModelBindingTest

echo ""
echo "[5/8] Dexing classes with D8..."
find app/build/obj -name "*.class" > app/build/classes.txt
$D8_CMD \
  --release \
  --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output app/build/dex \
  @app/build/classes.txt

echo ""
echo "[6/8] Packaging APK archive with resources & assets..."
$AAPT package -f \
  -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res \
  -A app/src/main/assets \
  -I "$ANDROID_JAR" \
  -F app/build/unaligned.apk

"$JAR_CMD" uf app/build/unaligned.apk -C app/build/dex classes.dex

echo ""
echo "[7/8] Aligning APK with zipalign..."
$ZIPALIGN -f -p 4 app/build/unaligned.apk app/build/aligned.apk

echo ""
echo "[8/8] Signing APK with apksigner..."
# Keystore check: If dedicated keystore is absent, generate ephemeral debug keystore for CI/testing
KEYSTORE_PASS="homelab-debug-only"
if [ ! -f "$KEYSTORE" ]; then
    echo "  Notice: Keystore '$KEYSTORE' not found."
    echo "  Generating ephemeral debug keystore for standalone compilation..."
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL_CMD" -genkeypair \
      -alias "$KEY_ALIAS" \
      -keyalg RSA \
      -keysize 2048 \
      -validity 10000 \
      -keystore "$KEYSTORE" \
      -storepass "$KEYSTORE_PASS" \
      -keypass "$KEYSTORE_PASS" \
      -dname "CN=PVE Pocket Ephemeral Debug, O=PVE Pocket CI, C=US" \
      -noprompt
fi

$APKSIGNER_CMD sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEYSTORE_PASS" \
  --out artifacts/pve-pocket-debug.apk \
  app/build/aligned.apk

cp -f artifacts/pve-pocket-debug.apk artifacts/homelab-pocket-debug.apk
if [ -f "artifacts/pve-pocket-debug.apk.idsig" ]; then
  cp -f artifacts/pve-pocket-debug.apk.idsig artifacts/homelab-pocket-debug.apk.idsig
fi

echo ""
echo "=================================================="
echo "Build Verification:"
echo "=================================================="
$APKSIGNER_CMD verify --verbose artifacts/pve-pocket-debug.apk
echo ""
$AAPT dump badging artifacts/pve-pocket-debug.apk | grep -E "(package|sdkVersion|targetSdkVersion|application-label)"
echo ""
APK_SIZE=$(stat -c%s artifacts/pve-pocket-debug.apk)
APK_SHA256=$(sha256sum artifacts/pve-pocket-debug.apk | awk '{print $1}')
echo "Artifact: artifacts/pve-pocket-debug.apk"
echo "Size:     $APK_SIZE bytes"
echo "SHA256:   $APK_SHA256"
echo "=================================================="
