#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Homelab Pocket — User-Local ARM64 Toolchain Bootstrap Script
# Bootstraps all required user-local tools without root / sudo.
set -euo pipefail

WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$WORKSPACE/tools"
EXTRACTED="$TOOLS/extracted"
DEBS_DIR="$TOOLS/debs"
SIGNING_DIR="$WORKSPACE/signing"

# Pinned upstream tool checksums (SHA-256)
TEMURIN_SHA256="e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578"
PLATFORM29_SHA256="951da8bf175254da74626824f919bd28def64f8828f29dd3b124a535cf4049d8"
R8_SHA256="900dfbc649519969fc5a4c7520d6b7355338e565fa1249874e0190b8d61b1199"

echo "=========================================================="
echo "Homelab Pocket — ARM64 User-Local Toolchain Bootstrap"
echo "=========================================================="
echo "Target Workspace: $WORKSPACE"
echo "Tools Directory:  $TOOLS"
echo ""

mkdir -p "$TOOLS" "$DEBS_DIR" "$EXTRACTED" "$SIGNING_DIR"

# 1. Adoptium OpenJDK 21 LTS (aarch64)
if [ ! -x "$TOOLS/jdk/bin/java" ]; then
    echo "[1/6] Setting up Adoptium OpenJDK 21 LTS (aarch64)..."
    if [ ! -f "$TOOLS/temurin21-aarch64.tar.gz" ]; then
        echo "Downloading Eclipse Temurin 21.0.13+9 aarch64..."
        curl -fSL "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.13_9.tar.gz" -o "$TOOLS/temurin21-aarch64.tar.gz"
    fi
    echo "  Verifying temurin21-aarch64.tar.gz checksum..."
    echo "$TEMURIN_SHA256  $TOOLS/temurin21-aarch64.tar.gz" | sha256sum -c -
    tar -xzf "$TOOLS/temurin21-aarch64.tar.gz" -C "$TOOLS"
    ln -sf "$TOOLS"/jdk-21* "$TOOLS/jdk"
    echo "  JDK installed: $("$TOOLS/jdk/bin/java" -version 2>&1 | head -1)"
else
    echo "[1/6] Adoptium JDK 21 already installed: $("$TOOLS/jdk/bin/java" -version 2>&1 | head -1)"
fi

# 2. Android Build Tools (aapt, zipalign, apksigner, libs)
echo ""
echo "[2/6] Bootstrapping Debian ARM64 Android build packages..."
REQUIRED_PKGS=(
    "aapt"
    "zipalign"
    "apksigner"
    "libzopfli1"
    "android-libaapt"
    "android-libandroidfw"
    "android-libbacktrace"
    "android-libbase"
    "android-libcutils"
    "android-liblog"
    "android-libunwind"
    "android-libutils"
    "android-libziparchive"
    "junit4"
    "libhamcrest-java"
    "libandroid-json-java"
)

# Download packages if not already present
for pkg in "${REQUIRED_PKGS[@]}"; do
    if ! ls "$DEBS_DIR"/$pkg*.deb >/dev/null 2>&1; then
        echo "  Downloading $pkg with apt-get download (user-local)..."
        (cd "$DEBS_DIR" && apt-get download "$pkg" 2>/dev/null || echo "Note: apt download for $pkg skipped if repository unavailable")
    fi
done

# Extract all debs into tools/extracted
echo "  Extracting package archives into $EXTRACTED..."
for deb in "$DEBS_DIR"/*.deb; do
    if [ -f "$deb" ]; then
        dpkg-deb -x "$deb" "$EXTRACTED"
    fi
done

# 3. Android Platform (API 29 android.jar)
echo ""
echo "[3/6] Setting up Android SDK Platform (API 29)..."
PLATFORMS_DIR="$TOOLS/android-sdk/platforms"
TARGET_PLATFORM_DIR="$PLATFORMS_DIR/android-29"
mkdir -p "$PLATFORMS_DIR"
if [ ! -f "$TARGET_PLATFORM_DIR/android.jar" ]; then
    if [ ! -f "$TOOLS/platform-29.zip" ]; then
        echo "Downloading Android platform-29..."
        curl -fSL "https://dl.google.com/android/repository/platform-29_r05.zip" -o "$TOOLS/platform-29.zip"
    fi
    echo "  Verifying platform-29.zip checksum..."
    echo "$PLATFORM29_SHA256  $TOOLS/platform-29.zip" | sha256sum -c -
    # Remove any prior directory to avoid nested mv
    rm -rf "$PLATFORMS_DIR/android-10" "$TARGET_PLATFORM_DIR"
    unzip -q -o "$TOOLS/platform-29.zip" -d "$PLATFORMS_DIR"
    if [ -d "$PLATFORMS_DIR/android-10" ]; then
        mv "$PLATFORMS_DIR/android-10" "$TARGET_PLATFORM_DIR"
    fi
fi
echo "  Verified android.jar: $(ls -lh "$TARGET_PLATFORM_DIR/android.jar" | awk '{print $5, $9}')"

# 4. Google D8 / R8 Compiler
echo ""
echo "[4/6] Setting up Google D8/R8 compiler..."
if [ ! -f "$TOOLS/r8.jar" ]; then
    echo "Downloading R8 8.3.37..."
    curl -fSL "https://storage.googleapis.com/r8-releases/raw/8.3.37/r8.jar" -o "$TOOLS/r8.jar"
fi
echo "  Verifying r8.jar checksum..."
echo "$R8_SHA256  $TOOLS/r8.jar" | sha256sum -c -
echo "  Verified r8.jar: $(ls -lh "$TOOLS/r8.jar" | awk '{print $5, $9}')"

# 5. Local Debug Keystore
echo ""
echo "[5/6] Checking debug signing keystore..."
KEYSTORE="$SIGNING_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    echo "Generating unique local debug keystore..."
    "$TOOLS/jdk/bin/keytool" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass homelab-debug-only \
        -alias homelab-pocket-debug \
        -keypass homelab-debug-only \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=HomelabPocketDebug, OU=Engineering, O=HomelabPocket, L=Local, ST=Debian, C=US"
    echo "  Generated debug keystore at $KEYSTORE"
else
    echo "  Existing debug keystore found at $KEYSTORE"
fi

# 6. Generate tools/env.sh
echo ""
echo "[6/6] Generating $TOOLS/env.sh..."
cat << 'ENVEOF' > "$TOOLS/env.sh"
#!/bin/bash
# Homelab Pocket — build environment setup
# Generated by tools/bootstrap-tools.sh
set -e

WORKSPACE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$WORKSPACE/tools"
EXTRACTED="$TOOLS/extracted"

# JDK
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$PATH"

# Android tools (from extracted debs)
export AAPT="$EXTRACTED/usr/lib/android-sdk/build-tools/debian/aapt"
export ZIPALIGN="$EXTRACTED/usr/bin/zipalign"
export APKSIGNER_JAR="$EXTRACTED/usr/share/java/apksigner.jar"
export D8_JAR="$TOOLS/r8.jar"

# Shared libs needed by aapt, zipalign (native ARM64 binaries)
LIBDIR="$EXTRACTED/usr/lib/aarch64-linux-gnu"
ZOPFLI="$EXTRACTED/usr/lib"
export LD_LIBRARY_PATH="$LIBDIR/android:$LIBDIR:$ZOPFLI${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# Android platform
export ANDROID_JAR="$TOOLS/android-sdk/platforms/android-29/android.jar"
export TARGET_SDK=29
export MIN_SDK=26

# Signing key
export SIGNING_DIR="$WORKSPACE/signing"
export KEYSTORE="$SIGNING_DIR/debug.keystore"
export KEY_ALIAS=homelab-pocket-debug

echo "Build environment loaded:"
echo "  JAVA: $(java -version 2>&1 | head -1)"
echo "  AAPT: $AAPT"
echo "  D8: $D8_JAR"
echo "  ANDROID_JAR: $ANDROID_JAR"
ENVEOF
chmod +x "$TOOLS/env.sh"

echo ""
echo "=========================================================="
echo "Toolchain Bootstrap Complete!"
echo "To build the project:"
echo "  source tools/env.sh"
echo "  ./tools/build.sh"
echo "=========================================================="
