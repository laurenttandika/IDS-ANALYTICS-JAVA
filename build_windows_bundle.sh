#!/usr/bin/env bash
set -euo pipefail

# CONFIG
APP_NAME="IDSAnalytics"
PKG_SCRIPT="./windows_package.sh"   # your existing packaging script
JRE_DIR="dist/${APP_NAME}/jre"

echo "🏗 Building ${APP_NAME} for Windows..."

# 1) Build Maven project
echo "🚀 Running Maven package (skip tests)..."
mvn -DskipTests package

# 2) Run packaging script
if [ ! -f "$PKG_SCRIPT" ]; then
    echo "❌ Packaging script not found: $PKG_SCRIPT"
    exit 1
fi
echo "📦 Running packaging script..."
bash "$PKG_SCRIPT"

# 3) Slim JRE
if [ -d "$JRE_DIR" ]; then
    echo "🔍 Slimming JRE in: $JRE_DIR"

    # Remove license/legal docs
    rm -rf "$JRE_DIR/legal" 2>/dev/null && echo "🗑 Removed: legal/"
    rm -rf "$JRE_DIR/man" 2>/dev/null && echo "🗑 Removed: man/"
    rm -rf "$JRE_DIR/include" 2>/dev/null && echo "🗑 Removed: include/"
    rm -rf "$JRE_DIR/demo" 2>/dev/null && echo "🗑 Removed: demo/"
    rm -rf "$JRE_DIR/sample" 2>/dev/null && echo "🗑 Removed: sample/"

    # If it's actually a JDK, remove extra dev tools
    rm -rf "$JRE_DIR/jmods" 2>/dev/null && echo "🗑 Removed: jmods/"

    for tool in javac.exe javadoc.exe jdeps.exe jar.exe jcmd.exe jconsole.exe jdb.exe; do
        if [ -f "$JRE_DIR/bin/$tool" ]; then
            rm -f "$JRE_DIR/bin/$tool"
            echo "🗑 Removed: bin/$tool"
        fi
    done
else
    echo "⚠ No JRE directory found at $JRE_DIR — skipping slimming."
fi

# 4) Zip final output
echo "🗜 Creating ZIP..."
(
  cd dist
  zip -qr "${APP_NAME}-Windows.zip" "${APP_NAME}"
)
echo "✅ Done! Output: dist/${APP_NAME}-Windows.zip"