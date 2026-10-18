#!/bin/sh
# CI-only: a disposable systemd Linux runner, root privileges required.
set -eu
[ "${LITEHELM_TEST_DISPOSABLE:-}" = 1 ] || { echo 'Set LITEHELM_TEST_DISPOSABLE=1 only on a disposable runner'; exit 1; }
base=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
[ ! -e /etc/systemd/system/litehelm.service ] || { echo 'Refusing existing installation'; exit 1; }
tmp=$(mktemp -d)
cleanup() {
 systemctl disable --now litehelm.service >/dev/null 2>&1 || true
 systemctl stop fixture-worker.timer fixture-worker.service >/dev/null 2>&1 || true
 systemctl stop 'litehelm-job-*' >/dev/null 2>&1 || true
 rm -f /etc/systemd/system/litehelm.service /etc/systemd/system/fixture-worker.service /etc/systemd/system/fixture-worker.timer /usr/local/bin/litehelm
 rm -rf /etc/litehelm /etc/systemd/system/litehelm.service.d /var/lib/litehelm /etc/litehelm-exec-test "$tmp"
 systemctl daemon-reload
}
trap cleanup EXIT
sh "$base/install.sh" --local-dir "$base/dist"
systemd-analyze verify /etc/systemd/system/litehelm.service
# Exercise upgrade from the previous unit without rewriting it.
sed -i 's/RestrictSUIDSGID=false/RestrictSUIDSGID=true/' /etc/systemd/system/litehelm.service
rm -f /etc/systemd/system/litehelm.service.d/10-litehelm-openat2.conf
systemctl daemon-reload
systemctl restart litehelm.service
token_hash=$(sha256sum /etc/litehelm/token)
binary_hash=$(sha256sum /usr/local/bin/litehelm)
unit_hash=$(sha256sum /etc/systemd/system/litehelm.service)
sh "$base/install.sh" --local-dir "$base/dist"
[ "$token_hash" = "$(sha256sum /etc/litehelm/token)" ]
[ "$unit_hash" = "$(sha256sum /etc/systemd/system/litehelm.service)" ]
grep -q 'RestrictSUIDSGID=false' /etc/systemd/system/litehelm.service.d/10-litehelm-openat2.conf
cat > /etc/systemd/system/fixture-worker.service <<'UNIT'
[Unit]
Description=LiteHelm disposable integration fixture
[Service]
ExecStart=/usr/bin/sleep 300
UNIT
cat > /etc/systemd/system/fixture-worker.timer <<'UNIT'
[Unit]
Description=LiteHelm disposable timer
[Timer]
OnActiveSec=1h
Unit=fixture-worker.service
UNIT
mkdir -p /etc/systemd/system/litehelm.service.d
cat > /etc/systemd/system/litehelm.service.d/tools.conf <<'CONF'
[Service]
Environment=LITEHELM_RUN_TIMERS=fixture-worker.timer
Environment=LITEHELM_SCAN_ROOTS=/var/log
CONF
systemctl daemon-reload
systemctl restart litehelm.service
systemctl start fixture-worker.timer
python3 - <<'PY'
import json, urllib.request, urllib.error
from pathlib import Path
token=Path('/etc/litehelm/token').read_text().strip()
def request(path, body=None):
 data=json.dumps(body).encode() if body is not None else None
 r=urllib.request.Request('http://127.0.0.1:9091/api/v1/'+path,data=data,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(r,timeout=20) as v:return json.load(v)
 except urllib.error.HTTPError as e:
  print('API failure',path,e.code,e.read().decode(),flush=True)
  raise
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
ports=request('ports')
assert ports['supported'] and any(':9091' in p['address'] for p in ports['ports'])
disks=request('disks')
assert disks['disks'] and all('inodes_total' in d for d in disks['disks'])
timers=request('timers')
timer=next(t for t in timers['timers'] if t['name']=='fixture-worker.timer')
assert timer['can_run'] and timer['unit']=='fixture-worker.service'
assert request('timers/fixture-worker.timer/run', {'confirm':'fixture-worker.timer'})['ok']
scan=request('disk-scan', {'path':'/var/log'})
assert scan['metric']=='logical_bytes' and scan['elapsed_ms']<5000 and len(scan['entries'])<=20
try:
 request('disk-scan', {'path':'/var/log'})
 raise AssertionError('Missing scan cooldown')
except urllib.error.HTTPError as e:
 assert e.code==429
import subprocess
subprocess.run(['systemctl','stop','fixture-worker.timer','fixture-worker.service'],check=True)
timerfile=Path('/etc/systemd/system/fixture-worker.timer')
timerfile.write_text('[Timer]\nOnActiveSec=1h\nUnit=litehelm.service\n')
subprocess.run(['systemctl','daemon-reload'],check=True)
try:
 request('timers/fixture-worker.timer/run', {'confirm':'fixture-worker.timer'})
 raise AssertionError('Allowed protected target through timer')
except urllib.error.HTTPError as e:
 assert e.code==403
timerfile.unlink()
subprocess.run(['systemctl','daemon-reload'],check=True)
try:
 request('timers/fixture-worker.timer/run', {'confirm':'fixture-worker.timer'})
 raise AssertionError('Guessed service target for deleted timer')
except urllib.error.HTTPError as e:
 assert e.code in (403,500)
assert subprocess.run(['systemctl','is-active','--quiet','fixture-worker.service']).returncode!=0
print('Tools ports/disks/timer whitelist/manual scan/cooldown: PASS')
PY
# Script jobs: opt-in, systemd transient units outside the management sandbox.
sh "$base/install.sh" --local-dir "$base/dist" --enable-exec
python3 - <<'PY'
import json, time, subprocess, urllib.request, urllib.error
from pathlib import Path
token=Path('/etc/litehelm/token').read_text().strip()
def req(path, body=None, expect=200):
 data=json.dumps(body).encode() if body is not None else None
 r=urllib.request.Request('http://127.0.0.1:9091/api/v1/'+path,data=data,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(r,timeout=20) as v: code,out=v.status,json.load(v)
 except urllib.error.HTTPError as e: code,out=e.code,json.loads(e.read())
 assert code==expect,(path,code,out)
 return out
def wait(i, limit=40):
 end=time.time()+limit
 while time.time()<end:
  v=req('jobs/'+i)
  if v['job']['state']!='running': return v
  time.sleep(0.3)
 raise AssertionError(v)
assert req('system')['capabilities']['exec'] is True
assert req('jobs')['runner']=='systemd'
j=req('jobs',{'script':'#!/bin/sh\nset -eu\nwhoami\necho deployed > /etc/litehelm-exec-test\n'})['job']
v=wait(j['id']); assert v['job']['state']=='succeeded' and v['output']=='root\n', v
assert Path('/etc/litehelm-exec-test').read_text()=='deployed\n'
j=req('jobs',{'script':'exit 7'})['job']; assert wait(j['id'])['job']['exit_code']==7
j=req('jobs',{'script':'while true; do echo tick; sleep 0.2; done'})['job']; time.sleep(1)
req('services/litehelm-job-'+j['id']+'.service/action',{'action':'stop'},expect=403)
subprocess.run(['systemctl','restart','litehelm.service'],check=True); time.sleep(2)
assert req('jobs/'+j['id'])['job']['state']=='running'
req('jobs/'+j['id']+'/cancel',{}); assert wait(j['id'])['job']['state']=='cancelled'
j=req('jobs',{'script':'sleep 120','timeout_seconds':10})['job']; assert wait(j['id'])['job']['state']=='timeout'
print('Script jobs: root deploy outside sandbox, exit code, restart survival, cancel, timeout, protected unit: PASS')
PY
sh "$base/install.sh" --local-dir "$base/dist" --disable-exec
[ ! -e /etc/systemd/system/litehelm.service.d/20-litehelm-exec.conf ]
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
