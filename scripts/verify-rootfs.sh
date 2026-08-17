#!/data/data/com.termux/files/usr/bin/bash
# verify-rootfs.sh — host side. Imports a built rootfs archive into a scratch
# container, boots dsh web inside it, and proves it serves HTTP on :3080.
#
# Usage: verify-rootfs.sh [scratch-name] [archive]
#   default scratch-name: dsh-verify
#   default archive:      ~/dsh-android/dist/dsh-rootfs.tar.xz
set -euo pipefail

NAME="${1:-dsh-verify}"
ARCHIVE="${2:-$HOME/dsh-android/dist/dsh-rootfs.tar.xz}"

[ -f "$ARCHIVE" ] || { echo "archive not found: $ARCHIVE"; exit 1; }
if proot-distro list | grep -q "^* $NAME$"; then
  echo "container '$NAME' already exists — remove it first: proot-distro remove $NAME"
  exit 1
fi

echo "[1/3] importing $ARCHIVE as '$NAME'"
proot-distro install -n "$NAME" "$ARCHIVE"

echo "[2/3] booting dsh web inside container"
proot-distro login "$NAME" -- /opt/start-dsh.sh >"$HOME/dsh-verify.log" 2>&1 &
LOGIN_PID=$!

ok=0
for i in $(seq 90); do
  sleep 1
  if curl -fsS -o /dev/null http://127.0.0.1:3080 2>/dev/null; then ok=1; break; fi
  if ! kill -0 "$LOGIN_PID" 2>/dev/null; then break; fi
done

if [ "$ok" = 1 ]; then
  title=$(curl -fsS http://127.0.0.1:3080 | grep -o '<title>[^<]*</title>' | head -1)
  echo "VERIFY OK — http://127.0.0.1:3080 answers ($title)"
else
  echo "VERIFY FAILED (log tail below)"
  tail -40 /tmp/dsh-verify.log
  proot-distro kill "$NAME" 2>/dev/null || true
  exit 1
fi

echo "[3/3] stopping container"
proot-distro kill "$NAME" 2>/dev/null || true

echo "DONE — container '$NAME' left installed; remove it later with: proot-distro remove $NAME"