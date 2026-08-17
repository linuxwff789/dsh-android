#!/system/bin/sh
# bootstrap.sh — build a clean Debian minbase (arm64 / trixie) from TUNA,
# using busybox only (no dpkg-deb needed: a .deb = ar(tar.xz)).
# Produces a rootfs with NO termux paths baked in, then registers it in
# dpkg (unpack + configure) so apt works.
#
# Package list is precomputed (pkglist.txt); no python needed at runtime.
#
# Env used:
#   DSH_BUSYBOX        path to busybox (required for unpack step)
#   DSH_PROOT          path to proot (required for finalize step)
#   DSH_LD_LIBRARY_PATH  ld path for busybox/proot (host .so dir)
#   DSH_MIRROR         debian mirror (default TUNA)
#   DSH_STEP           unpack | finalize | all (default all)
#
# Usage: bootstrap.sh <rootfs-dir>
set -e
ROOT="${1:?rootfs dir}"
MIRROR="${DSH_MIRROR:-http://mirrors.tuna.tsinghua.edu.cn/debian}"
STEP="${DSH_STEP:-all}"

bb() { "${DSH_BUSYBOX:-busybox}" "$@"; }
run_proot() {
  "${DSH_PROOT:-proot}" --link2symlink --sysvipc \
    --kernel-release='\Linux\localhost\6.17.0-PRoot-Distro\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\aarch64\localdomain\-1\' \
    -L --change-id=0:0 --rootfs="$ROOT" --cwd=/ \
    --bind=/dev --bind=/proc --bind=/sys \
    /bin/sh -c "$1"
}

do_unpack() {
  echo "[1/3] downloading + unpacking ${DSH_PKGLIST:-pkglist.txt}"
  mkdir -p "$ROOT/var/lib/dpkg" "$ROOT/tmp/debs" "$ROOT/var/cache/apt" "$ROOT/var/lib/apt/lists" "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$ROOT/run"
  : > "$ROOT/var/lib/dpkg/status"
  n=0
  while IFS=$'\t' read -r name fn; do
    [ -z "$name" ] && continue
    n=$((n+1))
    url="$MIRROR/$fn"
    deb="$ROOT/tmp/debs/$name.deb"
    if ! curl -fsSL "$url" -o "$deb" 2>/dev/null; then bb wget -q "$url" -O "$deb"; fi
    d="$ROOT/tmp/x-$name"; mkdir -p "$d"
    ( cd "$d" && bb ar x "$deb" )
    dt=""
    for f in "$d"/data.tar.*; do [ -f "$f" ] && dt="$f" && break; done
    case "$dt" in
      *.xz) bb xz -d -c "$dt" 2>/dev/null | bb tar -x -C "$ROOT" 2>/dev/null || true ;;
      *.gz) bb gzip -d -c "$dt" 2>/dev/null | bb tar -x -C "$ROOT" 2>/dev/null || true ;;
      *)    bb tar -x -f "$dt" -C "$ROOT" 2>/dev/null || true ;;
    esac
    rm -rf "$d"
    echo "  unpacked $name ($n)"
  done < "${DSH_PKGLIST:-pkglist.txt}"
  { echo "nameserver 223.5.5.5"; echo "nameserver 8.8.8.8"; } > "$ROOT/etc/resolv.conf"
  cat > "$ROOT/etc/apt/sources.list" <<'SRC'
deb http://mirrors.tuna.tsinghua.edu.cn/debian trixie main
deb http://mirrors.tuna.tsinghua.edu.cn/debian trixie-updates main
SRC
  echo "[1/3] done ($n packages)"
}

do_finalize() {
  echo "[2/3] dpkg unpack + configure under proot (TMPDIR=/tmp)"
  cat > "$ROOT/tmp/dpkg-fix.sh" <<'SH'
#!/bin/sh
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive
cd /tmp/debs || exit 9
for d in *.deb; do dpkg --root=/ --force-not-root --admindir=/var/lib/dpkg --unpack "$d" >/dev/null 2>&1 || true; done
dpkg --root=/ --force-not-root --admindir=/var/lib/dpkg --configure -a >/dev/null 2>&1 || true
rm -rf /tmp/debs /tmp/dpkg-fix.sh
SH
  run_proot "sh /tmp/dpkg-fix.sh"
  echo "[2/3] done"
}

case "$STEP" in
  unpack)   do_unpack ;;
  finalize) do_finalize ;;
  all)      do_unpack; do_finalize ;;
esac
echo "bootstrap-$STEP-OK"
