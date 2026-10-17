#!/bin/sh
# CI-only: a disposable systemd Linux runner, root privileges required.
set -eu
[ "${LITEHELM_TEST_DISPOSABLE:-}" = 1 ] || { echo 'Set LITEHELM_TEST_DISPOSABLE=1 only on a disposable runner'; exit 1; }
base=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
[ ! -e /etc/systemd/system/litehelm.service ] || { echo 'Refusing existing installation'; exit 1; }
tmp=$(mktemp -d)
cleanup() {
 systemctl disable --now litehelm.service >/dev/null 2>&1 || true
 systemctl stop fixture-worker.service >/dev/null 2>&1 || true
 rm -f /etc/systemd/system/litehelm.service /etc/systemd/system/fixture-worker.service /usr/local/bin/litehelm
 rm -rf /etc/litehelm "$tmp"
 systemctl daemon-reload
}
trap cleanup EXIT
sh "$base/install.sh" --local-dir "$base/dist"
systemd-analyze verify /etc/systemd/system/litehelm.service
token_hash=$(sha256sum /etc/litehelm/token)
binary_hash=$(sha256sum /usr/local/bin/litehelm)
unit_hash=$(sha256sum /etc/systemd/system/litehelm.service)
sh "$base/install.sh" --local-dir "$base/dist"
[ "$token_hash" = "$(sha256sum /etc/litehelm/token)" ]
[ "$unit_hash" = "$(sha256sum /etc/systemd/system/litehelm.service)" ]
cat > /etc/systemd/system/fixture-worker.service <<'UNIT'
[Unit]
Description=LiteHelm disposable integration fixture
[Service]
ExecStart=/usr/bin/sleep 300
UNIT
systemctl daemon-reload
python3 - <<'PY'
import json, urllib.request, urllib.error
from pathlib import Path
token=Path('/etc/litehelm/token').read_text().strip()
def request(path, body=None):
 data=json.dumps(body).encode() if body is not None else None
 r=urllib.request.Request('http://127.0.0.1:9091/api/v1/'+path,data=data,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(r,timeout=20) as v:return json.load(v)
assert request('system')['capabilities']['service_control']
name='fixture-worker.service'
assert any(s['name']==name for s in request('services')['services'])
for action in ['start','stop','start']:
 assert request('services/'+name+'/action', {'action':action})['ok']
 assert any(s['name']==name for s in request('services')['services'])
assert 'logs' in request('services/'+name+'/logs')
try:
 request('services/litehelm.service/action', {'action':'stop'})
 raise AssertionError('Allowed stop of management service')
except urllib.error.HTTPError as e:
 assert e.code==403
PY
cp "$base/dist/litehelm-linux-amd64" "$tmp/litehelm-linux-amd64"
printf '%064d  litehelm-linux-amd64\n' 0 > "$tmp/SHA256SUMS"
if sh "$base/install.sh" --local-dir "$tmp"; then echo 'Accepted bad checksum'; exit 1; fi
[ "$binary_hash" = "$(sha256sum /usr/local/bin/litehelm)" ]
cat > "$tmp/litehelm-linux-amd64" <<'BAD'
#!/bin/sh
if [ "${1:-}" = -h ]; then exit 0; fi
exit 1
BAD
(cd "$tmp" && sha256sum litehelm-linux-amd64 > SHA256SUMS)
if sh "$base/install.sh" --local-dir "$tmp"; then echo 'Accepted crashing binary'; exit 1; fi
[ "$binary_hash" = "$(sha256sum /usr/local/bin/litehelm)" ]
[ "$token_hash" = "$(sha256sum /etc/litehelm/token)" ]
systemctl is-active --quiet litehelm.service
printf '%s\n' 'Installer first install, reinstall, service actions/logs, protected unit, checksum rejection and rollback: PASS'
