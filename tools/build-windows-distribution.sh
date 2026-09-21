#!/usr/bin/env bash
# ==============================================================================
# CloudStream Desktop - Windows Distribution & Installer Builder
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "=================================================================="
echo "  CloudStream Desktop - Windows 10/11 Installer & Portable Build  "
echo "=================================================================="

# 1. Check MinGW-w64 compiler
if ! command -v x86_64-w64-mingw32-gcc >/dev/null 2>&1; then
    echo "[-] HATA: x86_64-w64-mingw32-gcc bulunamadi." >&2
    exit 1
fi

DIST_DIR="${ROOT_DIR}/desktop-app/build/distributions/windows"
mkdir -p "${DIST_DIR}"
TMP_DIR="/tmp/cs-win-build-$$"
mkdir -p "${TMP_DIR}/payload/CloudStream/app"
mkdir -p "${TMP_DIR}/payload/CloudStream/runtime"

cleanup() {
    rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

echo "[+] 1. Release Fat JAR derleniyor (Multiplatform Skiko + MPV)..."
"${ROOT_DIR}/tools/agent-exec.sh" gradle :desktop-app:packageReleaseUberJarForCurrentOS

JAR_SRC="${ROOT_DIR}/desktop-app/build/compose/jars/cloudstream-desktop-linux-x64-1.0.0-release.jar"
if [ ! -f "${JAR_SRC}" ]; then
    echo "[-] HATA: ${JAR_SRC} bulunamadi." >&2
    exit 1
fi
cp "${JAR_SRC}" "${TMP_DIR}/payload/CloudStream/app/CloudStream.jar"

echo "[+] 2. Windows JRE 21 (Temurin) dogrulaniyor..."
JRE_CACHE="/tmp/temurin21-jre-win64.zip"
if [ ! -f "${JRE_CACHE}" ]; then
    echo "  -> JRE 21 indiriliyor..."
    curl -sL "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip" -o "${JRE_CACHE}"
fi
unzip -q "${JRE_CACHE}" -d "${TMP_DIR}/jre-tmp"
mv "${TMP_DIR}/jre-tmp"/jdk-*/* "${TMP_DIR}/payload/CloudStream/runtime/"
rm -rf "${TMP_DIR}/jre-tmp"

echo "[+] 3. Native C++ CloudStream.exe Launcher derleniyor..."
cd "${ROOT_DIR}/desktop-app/src/main/native/windows"
x86_64-w64-mingw32-windres launcher.rc -O coff -o "${TMP_DIR}/launcher_res.o"
x86_64-w64-mingw32-gcc -O3 -s -municode -mwindows launcher.c "${TMP_DIR}/launcher_res.o" -o "${TMP_DIR}/payload/CloudStream/CloudStream.exe"

echo "[+] 4. Native C++ Uninstaller (uninstall.exe) derleniyor..."
cd "${ROOT_DIR}/desktop-app/src/main/native/windows/installer"
x86_64-w64-mingw32-gcc -O3 -s -municode -mwindows uninstaller.c -o "${TMP_DIR}/payload/CloudStream/uninstall.exe" -lole32 -lshell32

cp "${ROOT_DIR}/desktop-app/src/main/resources/logo_ui.ico" "${TMP_DIR}/payload/CloudStream/"

cat > "${TMP_DIR}/payload/CloudStream/run.bat" << 'EOF'
@echo off
title CloudStream Desktop
cd /d "%~dp0"

set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"

"%JAVA_EXE%" -XX:+UseG1GC -XX:+UseStringDeduplication -Dsun.java2d.d3d=true -Dskiko.renderApi=DIRECT3D -jar "%~dp0app\CloudStream.jar" %*

if %errorlevel% neq 0 (
    echo.
    echo [Hata Kodu: %errorlevel%]
    pause
)
EOF

echo "[+] 5. Portable ZIP arsivi olusturuluyor..."
cd "${TMP_DIR}/payload"
zip -r -q -9 "${DIST_DIR}/CloudStream-1.0.0-Windows-x64-Portable.zip" CloudStream/

echo "[+] 6. Tekil Setup.exe Yukleyicisi olusturuluyor..."
zip -r -q -9 "${TMP_DIR}/payload.zip" CloudStream/
cd "${ROOT_DIR}/desktop-app/src/main/native/windows/installer"

cat > "${TMP_DIR}/installer.rc" <<EOF
1 ICON "${ROOT_DIR}/desktop-app/src/main/resources/logo_ui.ico"
1 24 "${ROOT_DIR}/desktop-app/src/main/native/windows/app.manifest"
100 RCDATA "${TMP_DIR}/payload.zip"
EOF

x86_64-w64-mingw32-windres "${TMP_DIR}/installer.rc" -O coff -o "${TMP_DIR}/installer_res.o"
x86_64-w64-mingw32-gcc -O3 -s -municode -mwindows installer.c miniz.c miniz_tdef.c miniz_tinfl.c miniz_zip.c "${TMP_DIR}/installer_res.o" \
    -o "${DIST_DIR}/CloudStream-Setup-1.0.0.exe" \
    -lcomctl32 -lole32 -lshell32 -luuid

echo "=================================================================="
echo "  Windows Dagitim Paketleri Basariyla Olusturuldu!               "
echo "=================================================================="
ls -lh "${DIST_DIR}"
echo ""
