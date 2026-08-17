#!/data/data/com.termux/files/usr/bin/bash
# build-rootfs.sh — host side. Creates a fresh template container, runs the
# in-container setup, exports the rootfs archive.
#
# Usage: build-rootfs.sh [container-name] [output-file]
#   default container-name: dsh-tpl
#   default output:         ~/dsh-android/dist/dsh-rootfs.tar.xz
#
# Works on Termux (native arm64) AND on x86_64 GitHub Actions: the Actions
# runner must have proot + proot-distro + qemu-user-static configured so
# debian:latest arm64 images can run under emulation.
set -euo pipefail

NAME="${1:-dsh-tpl}"
OUT="${2:-$HOME/dsh-android/dist/dsh-rootfs.tar.xz}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

command -v proot-distro >/dev/null || { echo "proot-distro missing"; exit 1; }

if proot-distro list | grep -q "^* $NAME$"; then
  echo "container '$NAME' already exists — remove it first: proot-distro remove $NAME"
  exit 1
fi

mkdir -p "$(dirname "$OUT")"

echo "[1/3] installing fresh container '$NAME' (debian:latest)"
proot-distro install -n "$NAME" debian:latest

echo "[2/3] running in-container setup (bind $ROOT -> /host)"
proot-distro login --bind "$ROOT":/host "$NAME" -- bash /host/scripts/setup-container.sh /host/patches

echo "[3/3] exporting rootfs archive"
proot-distro backup "$NAME" --output "$OUT"

echo "OK: $OUT"