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
# China-friendly mirror: the bootstrap ships packages-cf.termux.dev which is
# slow/unreliable in China. Switch to the Tsinghua mirror (same as stock
# Termux setups use) before any apt operation.
sed -i "s#https://packages-cf.termux.dev/apt/termux-main#https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main#g" \
  "$PREFIX/etc/apt/sources.list" 2>/dev/null || true
# Fresh bootstraps have no apt lists yet; update BEFORE install or the
# install fails and set -e aborts the whole script.
pkg update -y >/dev/null 2>&1 || true
command -v proot-distro >/dev/null || pkg install -y proot-distro proot

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
