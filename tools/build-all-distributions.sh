#!/usr/bin/env bash
# ==============================================================================
# CloudStream Desktop - Master Multiplatform Distribution Builder
# ==============================================================================
# Bu betik, hem Linux hem Windows varyantlarini (Portable, Deb, AppImage, Setup.exe)
# tek bir adimda deterministik olarak derler, paketler ve SHA256 ozetleriyle
# desktop-app/build/distributions/ altinda toplar.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${ROOT_DIR}/desktop-app/build/distributions"

echo "=================================================================="
echo "  CloudStream Desktop - Tum Multiplatform Dagitimlari Olusturucu "
echo "=================================================================="
echo "Cikis Dizini: ${DIST_DIR}"
echo ""

mkdir -p "${DIST_DIR}"

# ------------------------------------------------------------------------------
# 1. LINUX DAGITIMLARI
# ------------------------------------------------------------------------------
echo "=================================================="
echo "[1/4] Linux Dagitimlari Derleniyor (Deb & Portable)"
echo "=================================================="

"${ROOT_DIR}/tools/agent-exec.sh" gradle :desktop-app:createReleaseDistributable :desktop-app:packageReleaseDeb

# A. Linux Portable (.tar.gz)
echo "[+] Linux Portable (Gomulu JRE) arsivleniyor..."
LINUX_APP_DIR="${ROOT_DIR}/desktop-app/build/compose/binaries/main-release/app"
if [ -d "${LINUX_APP_DIR}/cloudstream-desktop" ]; then
    tar -czf "${DIST_DIR}/CloudStream-1.0.0-Linux-x64-Portable.tar.gz" \
        -C "${LINUX_APP_DIR}" cloudstream-desktop
    echo "  [OK] ${DIST_DIR}/CloudStream-1.0.0-Linux-x64-Portable.tar.gz"
fi

# B. Linux Debian Paketi (.deb)
DEB_SRC="${ROOT_DIR}/desktop-app/build/compose/binaries/main-release/deb/cloudstream-desktop_1.0.0_amd64.deb"
if [ -f "${DEB_SRC}" ]; then
    cp "${DEB_SRC}" "${DIST_DIR}/"
    echo "  [OK] ${DIST_DIR}/cloudstream-desktop_1.0.0_amd64.deb"
fi

# ------------------------------------------------------------------------------
# 2. WINDOWS DAGITIMLARI
# ------------------------------------------------------------------------------
echo ""
echo "=================================================="
echo "[2/4] Windows Dagitimlari Derleniyor (Setup.exe & Portable)"
echo "=================================================="

"${ROOT_DIR}/tools/build-windows-distribution.sh"

WIN_SRC_DIR="${ROOT_DIR}/desktop-app/build/distributions/windows"
if [ -d "${WIN_SRC_DIR}" ]; then
    cp -r "${WIN_SRC_DIR}"/* "${DIST_DIR}/"
    rm -rf "${WIN_SRC_DIR}"
fi

# ------------------------------------------------------------------------------
# 3. SHA-256 KONTROL TOPLAMLARI (CHECKSUMS)
# ------------------------------------------------------------------------------
echo ""
echo "=================================================="
echo "[3/4] SHA-256 Guvenlik Ozetleri Hesaplaniyor..."
echo "=================================================="

cd "${DIST_DIR}"
sha256sum CloudStream-* cloudstream-* > SHA256SUMS.txt 2>/dev/null || true
echo "  [OK] ${DIST_DIR}/SHA256SUMS.txt"

# ------------------------------------------------------------------------------
# 4. OZET VE RAPOR
# ------------------------------------------------------------------------------
echo ""
echo "=================================================================="
echo "  TUM MULTIPLATFORM DAGITIMLARI BASARIYLA TAMAMLANDI!            "
echo "=================================================================="
ls -lh "${DIST_DIR}"
echo ""
cat "${DIST_DIR}/SHA256SUMS.txt"
echo "=================================================================="
