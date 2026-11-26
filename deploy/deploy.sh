#!/usr/bin/env bash
# Builds keel-server for the VPS and installs it as a systemd service.
#   ./deploy/deploy.sh            # root@89.124.4.220 (the DataPasa VPS)
#   HOST=user@1.2.3.4 ./deploy/deploy.sh
set -euo pipefail
HOST="${HOST:-root@89.124.4.220}"
cd "$(dirname "$0")/.."
make server-linux
scp dist/keel-server-linux-amd64 deploy/keel-server.service "$HOST:/tmp/"
ssh "$HOST" 'set -e
  sudo id keel >/dev/null 2>&1 || sudo useradd --system --home /var/lib/keel --shell /usr/sbin/nologin keel
  sudo install -d -o keel -g keel -m 750 /var/lib/keel
  sudo install -m 755 /tmp/keel-server-linux-amd64 /usr/local/bin/keel-server
  sudo install -m 644 /tmp/keel-server.service /etc/systemd/system/keel-server.service
  if command -v ufw >/dev/null; then sudo ufw allow 80/tcp >/dev/null; sudo ufw allow 443/tcp >/dev/null; fi
  sudo systemctl daemon-reload
  sudo systemctl enable --now keel-server
  sudo systemctl restart keel-server
  sleep 2; systemctl --no-pager --lines 5 status keel-server'
echo "Deployed. Health: https://89-124-4-220.sslip.io/api/health"
