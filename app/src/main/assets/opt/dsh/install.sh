#!/bin/sh
# install-dsh.sh — runs INSIDE the container (as root) on first boot.
# Installs node 22 + deepseek-harness (dsh) ONLINE from China mirrors,
# then writes /opt/start-dsh.sh. Idempotent.
set -e
export DEBIAN_FRONTEND=noninteractive
export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# China-friendly mirrors (override via env if needed)
APT_MIRROR="${APT_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/debian}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
NODE_MIRROR="${NODE_MIRROR:-https://cdn.npmmirror.com/binaries/node}"

echo "[1/7] apt -> TUNA + ca-certificates"
sources=/etc/apt/sources.list.d/debian.sources
[ -f "$sources" ] && sed -i "s#https\?://deb.debian.org/debian#$APT_MIRROR#g" "$sources"
# certs may be broken on the fresh base; use verify-peer=false for the first
# bootstrap round then let ca-certificates fix HTTPS for subsequent runs.
apt-get update -o Acquire::https::Verify-Peer=false -qq || true
apt-get install -y -o Acquire::https::Verify-Peer=false -qq ca-certificates >/dev/null 2>&1 || \
apt-get install -y -qq ca-certificates >/dev/null 2>&1 || true
apt-get update -qq || true
apt-get install -y -qq curl xz-utils python3 git patch build-essential >/dev/null 2>&1 || true

echo "[2/7] node 22 -> /opt/node (from $NODE_MIRROR)"
if [ ! -x /opt/node/bin/node ]; then
  VER=$(curl -fsSL https://nodejs.org/dist/index.json | python3 -c "import json,sys;d=json.load(sys.stdin);print(next(v['version'] for v in d if v['version'].startswith('v22.')))")
  echo "  node $VER"
  curl -fsSL "$NODE_MIRROR/$VER/node-$VER-linux-arm64.tar.xz" -o /tmp/node.tar.xz
  mkdir -p /opt/node && tar -xJf /tmp/node.tar.xz --strip-components=1 -C /opt/node
  rm -f /tmp/node.tar.xz
fi
/opt/node/bin/node --version
/opt/node/bin/npm config set registry "$NPM_REGISTRY" --global

echo "[3/7] dsh source -> /opt/dsh"
[ -d /opt/dsh/.git ] || git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git /opt/dsh

echo "[4/7] apply android patches"
cd /opt/dsh
if ! grep -q "cordis/src/fiber.ts" apps/cli/src/profile-boot.ts; then
cat > /tmp/dsh-droid.patch <<'PATCH'
diff --git a/apps/cli/src/profile-boot.ts b/apps/cli/src/profile-boot.ts
index 19c4abb..6b54fce 100644
--- a/apps/cli/src/profile-boot.ts
+++ b/apps/cli/src/profile-boot.ts
@@ -14,7 +14,8 @@
 import { writeFileSync } from 'node:fs'
 import { join, resolve } from 'node:path'
 import { fileURLToPath } from 'node:url'
-import { FiberState, type Context } from '@deepseek-ai/cordis'
+import { FiberState } from '@deepseek-ai/cordis/src/fiber.ts'
+import type { Context } from '@deepseek-ai/cordis'
 import type { PatchOptions } from '@deepseek-ai/cordis-plugin-include'
 import type { EntryOptions } from '@deepseek-ai/cordis-plugin-loader'
 import {
diff --git a/packages/session/session-persistence-jsonl/src/index.ts b/packages/session/session-persistence-jsonl/src/index.ts
index 5113746..efb533d 100644
--- a/packages/session/session-persistence-jsonl/src/index.ts
+++ b/packages/session/session-persistence-jsonl/src/index.ts
@@ -9,7 +9,7 @@
 import { Context } from '@deepseek-ai/cordis'
 import z from '@deepseek-ai/schemastery'
 import { readdirSync } from 'node:fs'
-import { open, mkdir, readFile, readdir, realpath, link, rm, stat, truncate } from 'node:fs/promises'
+import { open, mkdir, readFile, readdir, realpath, rename, rm, stat, truncate } from 'node:fs/promises'
 import { dirname, join, resolve } from 'node:path'
 import { performance } from 'node:perf_hooks'
 import { scheduler } from 'node:timers/promises'
@@ -544,17 +544,16 @@ export class JsonlSessionPersistence extends SessionPersistence implements Persi
     // Publish via link()+unlink(), NOT rename(): link fails with EEXIST if the
     // final path already exists, so two processes materializing the same id
     // concurrently cannot clobber each other. rename() would silently overwrite.
-    let linked = false
+    // Termux/Android patch: link(2) is denied by SELinux for untrusted_app
+    // (EACCES on any hard-link creation), so publish via rename() instead.
+    // Trade-off: loses the EEXIST clobber guard, acceptable for single-user.
     try {
-      await link(tmp, finalPath)
-      linked = true
-    } finally {
-      // Remove an unpublished temp on failure. After publication, defer cleanup
-      // until the directory entry is durable so cleanup cannot reject a live log.
-      /* v8 ignore next -- link failure is the TOCTOU/IO race guarded above; not reachable in test */
-      if (!linked) await rm(tmp, { force: true })
+      await rename(tmp, finalPath)
+    } catch (err) {
+      await rm(tmp, { force: true })
+      throw err
     }
-    // link() succeeded — the log is published. fsync the directory so the new
+    // rename() succeeded — the log is published. fsync the directory so the new
     // entry survives a power loss: the new link is not crash-durable until the
     // parent directory's metadata is synced.
     await this.syncDirPosix(dir)
PATCH
  patch -p1 < /tmp/dsh-droid.patch
fi
grep -q "cordis/src/fiber.ts" apps/cli/src/profile-boot.ts && echo "  patched"

echo "[5/7] pnpm install + full build"
PNPM_VER=$(grep -oP '"packageManager":\s*"pnpm@\K[0-9.]+' package.json)
/opt/node/bin/npm install -g "pnpm@$PNPM_VER" >/dev/null 2>&1 || true
cd /opt/dsh
if ! /opt/node/bin/npx pnpm install --registry "$NPM_REGISTRY" >/tmp/pnpm.log 2>&1; then
  /opt/node/bin/npx pnpm install --registry "$NPM_REGISTRY" --ignore-scripts >>/tmp/pnpm.log 2>&1 || true
fi
if ! ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/Release/pty.node >/dev/null 2>&1; then
  (cd node_modules/.pnpm/node-pty@*/node_modules/node-pty && \
   /opt/node/bin/node /opt/node/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js rebuild --nodedir=/opt/node) || true
fi
ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/Release/pty.node >/dev/null 2>&1 && echo "  pty.node OK" || echo "  WARN pty.node missing"
/opt/node/bin/npx pnpm run build >/tmp/build.log 2>&1 || { tail -15 /tmp/build.log; exit 1; }
echo "  build OK"

echo "[6/7] entrypoint"
cat > /opt/start-dsh.sh <<'EOF'
#!/usr/bin/env bash
export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DSH_HOME=/opt/dsh-home
mkdir -p "$DSH_HOME"
cd /opt/dsh
exec /opt/node/bin/node --expose-internals --import tsx/esm apps/cli/src/bin.ts web --host 127.0.0.1 --port 3080
EOF
chmod +x /opt/start-dsh.sh

echo "[7/7] smoke test"
/opt/start-dsh.sh >/var/log/dsh-web.log 2>&1 &
SRV=$!
ok=0
for i in $(seq 60); do
  sleep 1
  if curl -fsS -o /dev/null http://127.0.0.1:3080 2>/dev/null; then ok=1; break; fi
  kill -0 "$SRV" 2>/dev/null || break
done
kill "$SRV" 2>/dev/null || true
if [ "$ok" = 1 ]; then echo "SMOKE OK"; else echo "SMOKE FAILED:"; tail -20 /var/log/dsh-web.log; exit 1; fi

# mark installed
touch /opt/.dsh-installed
echo "INSTALL DONE"