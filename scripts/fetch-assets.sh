#!/data/data/com.termux/files/usr/bin/bash
# fetch-assets.sh — assemble the runtime assets for an APK build into the
# termux-app fork tree (app/src/main/assets/opt/dsh/).
#
# Small binaries (proot, xz + libs) are committed to the repo already.
# The large rootfs archive is NOT — pass its path here:
#   fetch-assets.sh [path-to-dsh-rootfs.tar.xz]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DEST="$ROOT/app/src/main/assets/opt/dsh"
ROOTFS_SRC="${1:-$HOME/dsh-android/dist/dsh-rootfs.tar.xz}"

[ -f "$ROOTFS_SRC" ] || { echo "rootfs archive not found: $ROOTFS_SRC"; exit 1; }

mkdir -p "$ASSETS_DEST"
echo "rootfs: $ROOTFS_SRC ($(du -h "$ROOTFS_SRC" | cut -f1))"
cp "$ROOTFS_SRC" "$ASSETS_DEST/rootfs.tar.xz"
echo "OK — assets ready for ./gradlew assembleDebug"