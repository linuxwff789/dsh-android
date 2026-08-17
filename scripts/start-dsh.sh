#!/usr/bin/env bash
# start-dsh.sh — entrypoint inside the container, run as the main process
# by proot when (re)starting the dsh server. Stays in FOREGROUND:
# the proot session owning this process IS the daemon (termux session/
# service model). Logs go to the session stdout.
export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DSH_HOME=/opt/dsh-home
mkdir -p "$DSH_HOME"
cd /opt/dsh
exec /opt/node/bin/node --expose-internals --import tsx/esm apps/cli/src/bin.ts web --host 127.0.0.1 --port 3080