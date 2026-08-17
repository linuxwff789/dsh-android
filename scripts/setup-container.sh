#!/usr/bin/env bash
# setup-container.sh — run INSIDE the target container (as root).
# Installs node 22 LTS + deepseek-harness (dsh) from source.
# Idempotent: safe to re-run; skips steps already done.
#
# Usage (inside container): bash /host/scripts/setup-container.sh /host/patches
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

PATCH_SRC="${1:-/host/patches}"
DSH_HOME_DIR=/opt/dsh-home

# China-friendly mirrors (override via env when building from the repo)
APT_MIRROR="${APT_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/debian}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"

echo "[1/6] apt base packages"
apt-get update -qq
apt-get install -y -qq --no-install-recommends curl ca-certificates xz-utils python3 git patch build-essential

echo "    switching apt sources -> $APT_MIRROR"
perl -pi -e "s|https?://deb.debian.org/debian|$APT_MIRROR|g" /etc/apt/sources.list.d/debian.sources
apt-get update -qq
echo "    npm/pnpm registry -> $NPM_REGISTRY"
npm config set registry "$NPM_REGISTRY" --global
echo "registry=$NPM_REGISTRY" >> /root/.npmrc

echo "[2/6] node 22 LTS -> /opt/node"
if [ ! -x /opt/node/bin/node ]; then
  NODE_VER=$(curl -fsSL https://nodejs.org/dist/index.json | python3 -c "import json,sys;d=json.load(sys.stdin);print(next(v['version'] for v in d if v['version'].startswith('v22.')))")
  echo "    picking node ${NODE_VER}"
  curl -fsSL "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-arm64.tar.xz" -o /tmp/node.tar.xz
  mkdir -p /opt/node
  tar -xJf /tmp/node.tar.xz --strip-components=1 -C /opt/node
  rm -f /tmp/node.tar.xz
fi
/opt/node/bin/node --version

echo "[3/6] deepseek-harness -> /opt/dsh"
if [ ! -d /opt/dsh/.git ]; then
  git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git /opt/dsh
fi

echo "[4/6] apply android patches (FiberState const-enum + link->rename)"
cd /opt/dsh
if ! grep -q "cordis/src/fiber.ts" apps/cli/src/profile-boot.ts; then
  patch -p1 < "$PATCH_SRC/dsh-on-android.patch"
fi
grep -q "cordis/src/fiber.ts" apps/cli/src/profile-boot.ts && echo "    patch applied"

echo "[5/6] pnpm install + native rebuild + full build"
PNPM_VER=$(grep -oP '"packageManager":\s*"pnpm@\K[0-9.]+' package.json)
echo "    pnpm ${PNPM_VER}"
npm install -g "pnpm@${PNPM_VER}" >/dev/null
if ! pnpm install; then
  echo "    plain install failed, retrying with --ignore-scripts"
  pnpm install --ignore-scripts
fi
# node-pty prebuild download is flaky on mobile networks; compile it locally
# from the bundled headers (--nodedir avoids a nodejs.org headers download)
if ! ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/Release/pty.node >/dev/null 2>&1; then
  echo "    compiling node-pty (pty.node)"
  cd node_modules/.pnpm/node-pty@*/node_modules/node-pty
  /opt/node/bin/node /opt/node/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js rebuild --nodedir=/opt/node || true
  cd /opt/dsh
fi
ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/Release/pty.node >/dev/null 2>&1 && echo "    pty.node OK" || echo "    WARNING: pty.node missing (web terminal disabled)"
# full build: builds lib/ (tsc+tsdown, needed by vite + runtime resolution)
# then the web frontend. Matches the termux-verified flow.
pnpm run build

echo "[6/6] entrypoint + smoke test"
cat > /opt/start-dsh.sh <<'EOF'
#!/usr/bin/env bash
export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DSH_HOME=/opt/dsh-home
mkdir -p "$DSH_HOME"
cd /opt/dsh
exec /opt/node/bin/node --expose-internals --import tsx/esm apps/cli/src/bin.ts web --host 127.0.0.1 --port 3080
EOF
chmod +x /opt/start-dsh.sh

echo "    smoke: boot dsh web, curl :3080"
/opt/start-dsh.sh >/var/log/dsh-web.log 2>&1 &
SRV_PID=$!
ok=0
for i in $(seq 60); do
  sleep 1
  if curl -fsS -o /dev/null http://127.0.0.1:3080 2>/dev/null; then ok=1; break; fi
  if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
done
kill "$SRV_PID" 2>/dev/null || true
if [ "$ok" = 1 ]; then
  echo "SMOKE OK"
else
  echo "SMOKE FAILED:"
  tail -40 /var/log/dsh-web.log
  exit 1
fi

echo "SETUP DONE"