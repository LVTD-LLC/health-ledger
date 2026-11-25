#!/bin/sh
# Keep operational commands one-shot. The web process and queue worker share a lifecycle.
if [ "$#" -gt 0 ] && [ "$1" != "serve" ]; then exec health-ledger "$@"; fi
python3 /app/worker/worker.py &
worker=$!
health-ledger "$@" &
web=$!
trap 'kill "$worker" "$web" 2>/dev/null || true' TERM INT
while kill -0 "$web" 2>/dev/null && kill -0 "$worker" 2>/dev/null; do sleep 1; done
kill "$worker" "$web" 2>/dev/null || true
wait "$web"
