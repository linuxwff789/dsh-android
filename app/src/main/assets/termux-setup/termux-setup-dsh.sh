#!/data/data/com.termux/files/usr/bin/bash
# termux-setup-dsh.sh — one-shot bootstrap of DSH on Termux.
#
# Termux (itself a terminal) installs: proot-distro -> fresh Debian container
# -> node 22 -> deepseek-harness (dsh), then serves the web UI on :3080.
# Idempotent: safe to re-run; already-done steps are skipped.
#
# Usage: bash termux-setup-dsh.sh
set -euo pipefail

# Capture the whole run so failures are inspectable from outside the
# terminal (e.g. $HOME/dsh-setup.log under the app data dir).
LOG_FILE="${DSH_SETUP_LOG:-$HOME/dsh-setup.log}"
exec >"$LOG_FILE" 2>&1
echo "[setup] start $(date '+%F %T')"

# ---- mirrors (China-friendly) ----
APT_MIRROR="${APT_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/debian}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
NODE_MIRROR="${NODE_MIRROR:-https://cdn.npmmirror.com/binaries/node}"
CONTAINER="debian"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PATCH="${PATCH:-$SCRIPT_DIR/patches/dsh-on-android.patch}"

echo "[0] Termux prerequisites"
# The stock bootstrap binaries have /data/data/com.termux paths compiled in
# (DT_RUNPATH for shared libs, TLS CA bundle paths). The app cannot read
# that other app's private dir (mode 0700, different uid), so:
#  - curl/openssl tools get the fork's own CA bundle via SSL_CERT_FILE
#  - apt's https method (gnutls, which ignores SSL_CERT_FILE) gets it via
#    Acquire::https::CAInfo
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
mkdir -p "$PREFIX/etc/apt/apt.conf.d"
echo "Acquire::https::CAInfo \"$PREFIX/etc/tls/cert.pem\";" > "$PREFIX/etc/apt/apt.conf.d/00-dsh-cainfo"

# apt/dpkg also have the stock /data/data/com.termux paths compiled in for
# their config/state dirs. The app cannot read that other app's private
# dir, so redirect both to the fork's own dirs via their env/config hooks:
#   DPKG_CONFIG_DIR / DPKG_ADMINDIR (dpkg >= 1.19.4)
#   APT_CONFIG (apt reads this file in place of the baked-in dirs)
export DPKG_CONFIG_DIR="$PREFIX/etc/dpkg"
export DPKG_ADMINDIR="$PREFIX/var/lib/dpkg"
mkdir -p "$PREFIX/etc/apt/apt.conf.d" \
  "$PREFIX/var/lib/apt/lists/partial" \
  "$PREFIX/var/cache/apt/archives/partial" \
  "$PREFIX/var/lib/dpkg"
cat > "$PREFIX/etc/apt/dsh-apt.conf" <<EOF
Dir::Etc "$PREFIX/etc/apt";
Dir::Etc::main "$PREFIX/etc/apt/sources.list";
Dir::Etc::parts "$PREFIX/etc/apt/apt.conf.d";
Dir::Etc::sourceparts "$PREFIX/etc/apt/sources.list.d";
Dir::Etc::trustedparts "$PREFIX/etc/apt/trusted.gpg.d";
Dir::Etc::preferencesparts "$PREFIX/etc/apt/preferences.d";
Dir::State "$PREFIX/var/lib/apt";
Dir::State::status "$PREFIX/var/lib/dpkg/status";
Dir::Cache "$PREFIX/var/cache/apt";
Dir::Cache::archives "$PREFIX/var/cache/apt/archives";
Dir::Cache::pkgcache "$PREFIX/var/cache/apt/pkgcache.bin";
Dir::Cache::srcpkgcache "$PREFIX/var/cache/apt/srcpkgcache.bin";
Dir::Bin::methods "$PREFIX/lib/apt/methods";
Dir::Bin::apt-key "$PREFIX/bin/apt-key";
Dir::Bin::dpkg "$PREFIX/bin/dpkg";
Dir::Bin::dpkg-deb "$PREFIX/bin/dpkg-deb";
Dir::Bin::gpgv "$PREFIX/bin/gpgv";
Dir::Log "$PREFIX/var/log/apt";
DPkg::PATH "$PREFIX/bin:/system/bin";
DPkg::Post-Invoke {"$PREFIX/bin/dsh-fix-scripts.sh";};
EOF
# eipp.log.xz is written by dpkg itself (not apt) and its path is compiled
# into the binary (stock com.termux). Redirect it the same way:
mkdir -p "$PREFIX/var/log/apt"
export APT_CONFIG="$PREFIX/etc/apt/dsh-apt.conf"

# Post-Invoke hook: apt-installed script packages (proot-distro, ...) still
# carry the stock com.termux shebang; rewrite it to this fork's prefix after
# every dpkg run.
cat > "$PREFIX/bin/dsh-fix-scripts.sh" <<'HOOK'
#!/bin/sh
for d in "$PREFIX/bin" "$PREFIX/libexec"; do
  for f in "$d"/*; do
    [ -f "$f" ] || continue
    case "$(head -c 100 "$f" | head -1)" in
      '#!'*com.termux*)
        sed -i "1s#com.termux#$PACKAGE_NAME#" "$f" 2>/dev/null || true
        ;;
    esac
  done
done
exit 0
HOOK
# POSIX sh has no PREFIX var here; substitute at write time.
sed -i "s#\$PREFIX#$PREFIX#g; s#\$PACKAGE_NAME#${PACKAGE_NAME:-dev.lwff.dsh}#g" "$PREFIX/bin/dsh-fix-scripts.sh"
chmod +x "$PREFIX/bin/dsh-fix-scripts.sh"

# apt 3.x (which this fork ships via the real-Termux binaries) IGNORES the
# APT_CONFIG env var and instead reads Dir::Etc/main (apt.conf) from its
# COMPILED-IN default dir (/data/data/com.termux/files/usr/etc/apt). On a
# device that also has real Termux installed that means apt would read/write
# the STOCK Termux package state and sources! Wrap every apt binary so it
# always passes -c $PREFIX/etc/apt/dsh-apt.conf (which redirects Dir::Etc,
# Dir::State, Dir::Cache, Dir::Bin::* to this fork's own prefix).
REAL_APT_DIR="$PREFIX/bin/.real"
mkdir -p "$REAL_APT_DIR"
for APT_TOOL in apt apt-get apt-cache apt-config apt-mark; do
  if [ -x "$PREFIX/bin/$APT_TOOL" ] && [ ! -e "$REAL_APT_DIR/$APT_TOOL" ]; then
    mv "$PREFIX/bin/$APT_TOOL" "$REAL_APT_DIR/$APT_TOOL"
  fi
  cat > "$PREFIX/bin/$APT_TOOL" <<WRAP
#!/system/bin/sh
# dsh apt wrapper: force fork-specific config so apt never touches the
# stock /data/data/com.termux paths (real Termux on the same device).
CFG="$PREFIX/etc/apt/dsh-apt.conf"
if [ -f "\$CFG" ]; then
  exec "$REAL_APT_DIR/$APT_TOOL" -c "\$CFG" "\$@"
else
  exec "$REAL_APT_DIR/$APT_TOOL" "\$@"
fi
WRAP
  chmod 755 "$PREFIX/bin/$APT_TOOL"
done

# Pre-select the Tsinghua mirror so pkg skips probing ~40 mirrors.
TUNA_MIRROR="$PREFIX/etc/termux/mirrors/chinese_mainland/mirrors.tuna.tsinghua.edu.cn"
if [ -f "$TUNA_MIRROR" ]; then
  ln -sfn "$TUNA_MIRROR" "$PREFIX/etc/termux/chosen_mirrors"
fi

# apt 3.x only accepts deb822 sources; pkg's legacy one-line format fails
# with "Extra junk at end of file". Use apt-get directly with a deb822
# source file (same shape as stock Termux setups).
mkdir -p "$PREFIX/etc/apt/sources.list.d"
# The fork's apt variant ships a keyring WITHOUT the official Termux signing
# key (5A897D96E57CF20C), so verifies fail with NO_PUBKEY. Deploy the bundled
# official termux.gpg into trusted.gpg.d (the app copies it next to this
# script), then point Signed-By at it.
mkdir -p "$PREFIX/etc/apt/trusted.gpg.d"
if [ -s "$SCRIPT_DIR/termux.gpg" ]; then
  cp -f "$SCRIPT_DIR/termux.gpg" "$PREFIX/etc/apt/trusted.gpg.d/termux.gpg"
  SIGNED_BY="$PREFIX/etc/apt/trusted.gpg.d/termux.gpg"
else
  SIGNED_BY="$PREFIX/etc/apt/trusted.gpg.d/2096779623.gpg"
fi
cat > "$PREFIX/etc/apt/sources.list.d/termux.sources" <<EOF
Types: deb
URIs: https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
Suites: stable
Components: main
Signed-By: $SIGNED_BY
EOF
: > "$PREFIX/etc/apt/sources.list"

# Fresh bootstraps have no apt lists yet; update BEFORE install or the
# install fails and set -e aborts the whole script.
apt-get update 2>&1 || true
command -v proot-distro >/dev/null || apt-get install -y proot-distro proot

echo "[1] Debian container ($CONTAINER)"
if ! proot-distro list 2>&1 | grep -qE "[ *] *$CONTAINER"; then
  echo "  installing $CONTAINER (downloads, may take a while)..."
  proot-distro install "$CONTAINER"
else
  echo "  $CONTAINER already present"
fi

# Stage the source patch inside Debian. The container cannot directly read
# Termux's private $HOME path, so pipe it through proot-distro explicitly.
if [ -f "$PATCH" ]; then
  proot-distro login "$CONTAINER" -- bash -c 'cat > /opt/patch.patch' < "$PATCH"
fi

# ---- steps that run INSIDE the container ----
proot-distro login "$CONTAINER" -- bash -s <<'INNER'
set -euo pipefail
export PATH=/opt/node22/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
CONTAINER_APT="${APT_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/debian}"

echo "[2] apt -> TUNA + build deps"
apt-get update -qq 2>/dev/null || true
apt-get install -y -qq --no-install-recommends ca-certificates curl git python3 build-essential xz-utils 2>/dev/null || true
perl -pi -e "s|https?://deb.debian.org/debian|$CONTAINER_APT|g" /etc/apt/sources.list.d/debian.sources 2>/dev/null || true
apt-get update -qq 2>/dev/null || true

echo "[3] node 22 LTS -> /opt/node22"
if [ ! -x /opt/node22/bin/node ]; then
  VER=$(curl -fsSL https://nodejs.org/dist/index.json | python3 -c \
    "import json,sys;d=json.load(sys.stdin);print(next(v['version'] for v in d if v['version'].startswith('v22.')))")
  echo "  node $VER"
  curl -fsSL "${NODE_MIRROR:-https://cdn.npmmirror.com/binaries/node}/$VER/node-$VER-linux-arm64.tar.xz" -o /tmp/node.tar.xz
  mkdir -p /opt/node22 && tar -xJf /tmp/node.tar.xz --strip-components=1 -C /opt/node22
  rm -f /tmp/node.tar.xz
fi
/opt/node22/bin/node --version
npm config set registry "${NPM_REGISTRY:-https://registry.npmmirror.com}" --global

echo "[4] deepseek-harness -> /opt/dsh"
[ -d /opt/dsh/.git ] || git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git /opt/dsh
cd /opt/dsh
if ! grep -q "cordis/src/fiber.ts" apps/cli/src/profile-boot.ts; then
  if [ -s /opt/patch.patch ]; then
    patch -p1 < /opt/patch.patch
  else
    echo "ERROR: /opt/patch.patch is missing"
    exit 1
  fi
fi

echo "[5] pnpm install + node-pty + build"
if [ ! -x /opt/dsh/node_modules/.bin/pnpm ]; then
  PNPM_VER=$(grep -oP '"packageManager":\s*"pnpm@\K[0-9.]+' package.json)
  npm install -g "pnpm@${PNPM_VER}" >/dev/null
  pnpm install --ignore-scripts || pnpm install
fi
# node-pty: compile the native addon locally (flaky prebuilt downloads)
if ! ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/Release/pty.node >/dev/null 2>&1; then
  cd node_modules/.pnpm/node-pty@*/node_modules/node-pty
  /opt/node22/bin/node /opt/node22/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js rebuild --nodedir=/opt/node22 || true
  cd /opt/dsh
fi
pnpm run build 2>/dev/null || true

echo "[6] start script + smoke"
cat > /opt/start-dsh.sh <<'EOS'
#!/usr/bin/env bash
export PATH=/opt/node22/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DSH_HOME=/opt/dsh-home
mkdir -p "$DSH_HOME"
cd /opt/dsh
exec /opt/node22/bin/node --expose-internals --import tsx/esm apps/cli/src/bin.ts --profile web --host 127.0.0.1 --port 3080
EOS
chmod +x /opt/start-dsh.sh
echo "INNER DONE"
INNER

echo "[7] starting DSH web on :3080 (background)"
pkill -f "start-dsh.sh" 2>/dev/null || true
pkill -f "profile web" 2>/dev/null || true
sleep 1
proot-distro login "$CONTAINER" -- /opt/start-dsh.sh > "$HOME/dsh-server.log" 2>&1 &
disown
touch "$HOME/.dsh-setup-complete"
echo "OK — wait ~20s then open http://127.0.0.1:3080"
echo "Or: proot-distro login debian -- /opt/start-dsh.sh"
# When launched by the bundled APK, return to its WebView after setup.
if [ "${DSH_RETURN_TO_CLIENT:-0}" = "1" ]; then
  am start -n dev.lwff.dsh/com.termux.app.DshWebActivity >/dev/null 2>&1 || true
fi
