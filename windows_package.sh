#!/usr/bin/env bash
set -euo pipefail

APP_NAME="IDSAnalytics"
SHADED_JAR="target/mdb-analytics-1.0.0-shaded.jar"   # your shaded jar
WIN_JRE_DIR="/Users/laurenttandika/Downloads/Tools/jre_17_windows_x64_86"        # must contain bin/java.exe
WIN_JAVAFX_DIR="/Users/laurenttandika/Downloads/Tools/jdk_fx_17_windows_x64_86"  # must have lib/*.jar
ICON_PATH="/Users/laurenttandika/Documents/Projects/mdb-viewer/src/main/resources/images/icon.ico"  # optional
L4J_BIN="/Users/laurenttandika/Downloads/Tools/launch4j/launch4j"                 # your launch4j cli

# Clean staging
rm -rf dist && mkdir -p "dist/${APP_NAME}"

# Copy app jar
cp "$SHADED_JAR" "dist/${APP_NAME}/app.jar"

# Copy Windows JRE
cp -R "$WIN_JRE_DIR" "dist/${APP_NAME}/jre"

# Copy Windows JavaFX SDK if present
JFX_PRESENT="no"
if [ -d "$WIN_JAVAFX_DIR/lib" ]; then
  cp -R "$WIN_JAVAFX_DIR" "dist/${APP_NAME}/javafx"
  JFX_PRESENT="yes"
fi

# Create Windows launcher
if [ "$JFX_PRESENT" = "yes" ]; then
  # JavaFX-aware launcher
  cat > "dist/${APP_NAME}/run.bat" << 'BAT'
@echo off
setlocal
set APP_DIR=%~dp0
set JAVA="%APP_DIR%jre\bin\java.exe"
set JAVAFX="%APP_DIR%javafx\lib"
%JAVA% --module-path "%JAVAFX%" --add-modules javafx.controls,javafx.fxml -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 -jar "%APP_DIR%app.jar"
BAT
else
  # Plain launcher
  cat > "dist/${APP_NAME}/run.bat" << 'BAT'
@echo off
setlocal
set APP_DIR=%~dp0
set JAVA="%APP_DIR%jre\bin\java.exe"
%JAVA% -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 -jar "%APP_DIR%app.jar"
BAT
fi

# Optional icon
if [ -f "$ICON_PATH" ]; then
  cp "$ICON_PATH" "dist/${APP_NAME}/app.ico"
fi

# Build EXE with Launch4j (if available)
if [ -x "$L4J_BIN" ]; then
  "$L4J_BIN" launch4j.xml
elif command -v launch4j >/dev/null 2>&1; then
  launch4j launch4j.xml
elif [ -f "/Users/laurenttandika/Downloads/Tools/launch4j/launch4j.jar" ]; then
  java -jar "/Users/laurenttandika/Downloads/Tools/launch4j/launch4j.jar" launch4j.xml
else
  echo "NOTE: Launch4j not found; skipping EXE creation (ZIP will still include run.bat)."
fi

# Zip bundle
#( cd dist && zip -qr "${APP_NAME}-Windows.zip" "${APP_NAME}" )
#echo "Created dist/${APP_NAME}-Windows.zip"