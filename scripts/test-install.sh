#!/bin/sh
# Default: CI-only disposable systemd runner, root required.
# --manual-only: local installer fixtures in a temporary directory; no root/services/network.
set -eu
base=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
if [ "${1:-}" = --manual-only ]; then
 tmp=$(mktemp -d)
 trap 'rm -rf "$tmp"' EXIT
 case "$(uname -m)" in
  x86_64) asset=litehelm-linux-amd64;; aarch64|arm64) asset=litehelm-linux-arm64;; armv7l|armv8l) asset=litehelm-linux-armv7;;
  *) echo 'Unsupported fixture architecture'; exit 1;;
 esac
 mkdir "$tmp/assets" "$tmp/bin"
 cat > "$tmp/assets/$asset" <<'FIXTURE'
#!/bin/sh
case "${1:-}" in
 -h) exit 0;;
 -generate-token) printf '%s\n' 'manual-test-token-not-for-production-0123456789abcdef'; exit 0;;
 *) exit 1;;
esac
FIXTURE
 (cd "$tmp/assets" && sha256sum "$asset" > SHA256SUMS)
 # Manual deployment must not call managed-install commands, even on systemd hosts.
 for name in systemctl useradd install; do
  printf '%s\n' '#!/bin/sh' 'echo "Unexpected system command: $0" >&2' 'exit 97' > "$tmp/bin/$name"
  chmod 755 "$tmp/bin/$name"
 done
 PATH=$tmp/bin:$PATH
 export PATH
 manual=$tmp/private\ deployment
 sh "$base/install.sh" --local-dir "$tmp/assets" --manual-dir "$manual" > "$tmp/install.log"
 [ -x "$manual/litehelm" ]
 [ "$(stat -c %a "$manual")" = 700 ]
 [ "$(stat -c %a "$manual/token")" = 600 ]
 [ "$("$manual/litehelm" -generate-token)" = "$(head -n 1 "$manual/token")" ]
 token_hash=$(sha256sum "$manual/token")
 binary_hash=$(sha256sum "$manual/litehelm")
 sh "$base/install.sh" --local-dir "$tmp/assets" --manual-dir "$manual" > "$tmp/install.log"
 [ "$token_hash" = "$(sha256sum "$manual/token")" ]
 printf '%064d  %s\n' 0 "$asset" > "$tmp/assets/SHA256SUMS"
 if sh "$base/install.sh" --local-dir "$tmp/assets" --manual-dir "$manual" > "$tmp/install.log" 2>&1; then echo 'Accepted bad manual checksum'; exit 1; fi
 [ "$binary_hash" = "$(sha256sum "$manual/litehelm")" ]
 [ "$token_hash" = "$(sha256sum "$manual/token")" ]
 (cd "$tmp/assets" && sha256sum "$asset" > SHA256SUMS)
 if sh "$base/install.sh" --local-dir "$tmp/assets" --manual-dir "$manual" --enable-exec > "$tmp/install.log" 2>&1; then echo 'Accepted system configuration in manual mode'; exit 1; fi
 mkdir -m 700 "$tmp/linked"
 ln -s "$manual/token" "$tmp/linked/token"
 if sh "$base/install.sh" --local-dir "$tmp/assets" --manual-dir "$tmp/linked" > "$tmp/install.log" 2>&1; then echo 'Accepted manual token symlink'; exit 1; fi
 [ "$token_hash" = "$(sha256sum "$manual/token")" ]
 [ ! -e "$manual/.install.lock" ]
 printf '%s\n' 'Manual installer fixtures (not a real NAS/binary): private path, checksum, token preservation, symlink rejection, no managed system commands: PASS'
 exit 0
fi
[ "${LITEHELM_TEST_DISPOSABLE:-}" = 1 ] || { echo 'Set LITEHELM_TEST_DISPOSABLE=1 only on a disposable runner (or use --manual-only)'; exit 1; }
[ ! -e /etc/systemd/system/litehelm.service ] || { echo 'Refusing existing installation'; exit 1; }
tmp=$(mktemp -d)
cleanup() {
 systemctl disable --now litehelm.service >/dev/null 2>&1 || true
 systemctl stop fixture-worker.timer fixture-worker.service >/dev/null 2>&1 || true
 systemctl stop 'litehelm-job-*' >/dev/null 2>&1 || true
 rm -f /etc/systemd/system/litehelm.service /etc/systemd/system/fixture-worker.service /etc/systemd/system/fixture-worker.timer /usr/local/bin/litehelm
 rm -rf /etc/litehelm /etc/systemd/system/litehelm.service.d /var/lib/litehelm /etc/litehelm-exec-test "$tmp"
 userdel -r litehelm >/dev/null 2>&1 || true
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
# Script jobs: opt-in; a dedicated unprivileged account by default, root only on request.
cat > "$tmp/jobs.py" <<'PY'
import json, sys, time, subprocess, urllib.request, urllib.error
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
def run(script):
 v=wait(req('jobs',{'script':script})['job']['id'])
 assert v['job']['state']=='succeeded', v
 return v
probe='#!/bin/sh -eu\nwhoami\npwd\ngrep NoNewPrivs /proc/self/status\nif (echo x > /etc/litehelm-exec-test) 2>/dev/null; then echo wrote-etc; fi\nif ls /var/lib/litehelm >/dev/null 2>&1; then echo read-state; fi\n'
mode=sys.argv[1]
assert req('system')['capabilities']['exec'] is True
info=req('jobs'); assert info['runner']=='systemd' and info['user']==mode, info
if mode=='litehelm':
 v=run(probe)
 assert v['job']['user']=='litehelm' and v['output']=='litehelm\n/home/litehelm\nNoNewPrivs:\t1\n', v
 assert not Path('/etc/litehelm-exec-test').exists()
 v=run('#!/usr/bin/env python3\nimport os\nprint(os.getuid()==0, "$HOME")\n'); assert v['output']=='False $HOME\n', v
 assert not Path('/run/litehelm-job-'+v['job']['id']).exists()
 j=req('jobs',{'script':'exit 7'})['job']; assert wait(j['id'])['job']['exit_code']==7
 j=req('jobs',{'script':'while true; do echo tick; sleep 0.2; done'})['job']; time.sleep(1)
 req('services/litehelm-job-'+j['id']+'.service/action',{'action':'stop'},expect=403)
 subprocess.run(['systemctl','restart','litehelm.service'],check=True); time.sleep(2)
 assert req('jobs/'+j['id'])['job']['state']=='running'
 req('jobs/'+j['id']+'/cancel',{}); assert wait(j['id'])['job']['state']=='cancelled'
 j=req('jobs',{'script':'sleep 120','timeout_seconds':10})['job']; assert wait(j['id'])['job']['state']=='timeout'
 print('Script jobs as litehelm: no root, no new privileges, no /etc or state access, shebang, restart survival, cancel, timeout: PASS')
else:
 v=run(probe)
 assert v['output']=='root\n/root\nNoNewPrivs:\t0\nwrote-etc\nread-state\n', v
 assert Path('/etc/litehelm-exec-test').read_text()=='x\n'
 Path('/etc/litehelm-exec-test').unlink()
 print('Script jobs as explicit root: PASS')
PY
exec_conf=/etc/systemd/system/litehelm.service.d/20-litehelm-exec.conf
sh "$base/install.sh" --local-dir "$base/dist" --enable-exec
[ "$(stat -c %U:%a /home/litehelm)" = litehelm:700 ]
if grep -q LITEHELM_EXEC_USER "$exec_conf"; then echo 'Default script user written to drop-in'; exit 1; fi
python3 "$tmp/jobs.py" litehelm
sh "$base/install.sh" --local-dir "$base/dist" --exec-user root
grep -qx 'Environment=LITEHELM_EXEC_USER=root' "$exec_conf"
sh "$base/install.sh" --local-dir "$base/dist"
sh "$base/install.sh" --local-dir "$base/dist" --enable-exec
grep -qx 'Environment=LITEHELM_EXEC_USER=root' "$exec_conf"
python3 "$tmp/jobs.py" root
if sh "$base/install.sh" --local-dir "$base/dist" --exec-user nosuchuser; then echo 'Accepted missing script user'; exit 1; fi
if sh "$base/install.sh" --local-dir "$base/dist" --exec-user 'a;b'; then echo 'Accepted unsafe script user'; exit 1; fi
grep -qx 'Environment=LITEHELM_EXEC_USER=root' "$exec_conf"
sh "$base/install.sh" --local-dir "$base/dist" --exec-user litehelm
if grep -q LITEHELM_EXEC_USER "$exec_conf"; then echo 'Script user not reset to litehelm'; exit 1; fi
# Upgrade from an older drop-in (scripts ran as root): the account is created and scripts drop root.
printf '%s\n' '[Service]' 'Environment=LITEHELM_ALLOW_EXEC=1' 'StateDirectory=litehelm' 'StateDirectoryMode=0700' > "$exec_conf"
userdel -r litehelm
sh "$base/install.sh" --local-dir "$base/dist"
id -u litehelm >/dev/null
python3 "$tmp/jobs.py" litehelm
sh "$base/install.sh" --local-dir "$base/dist" --disable-exec
[ ! -e "$exec_conf" ]
id -u litehelm >/dev/null
# Web UI: on by default, same listener as the API; --disable-web keeps the API.
http_code() {
 python3 - "$1" "${2:-}" <<'PY'
import sys, urllib.request, urllib.error
from pathlib import Path
h={'Authorization':'Bearer '+Path('/etc/litehelm/token').read_text().strip()} if sys.argv[2] else {}
try: print(urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:9091'+sys.argv[1],headers=h),timeout=20).status)
except urllib.error.HTTPError as e: print(e.code)
PY
}
python3 - <<'PY'
import json, urllib.request, urllib.error
from pathlib import Path
token=Path('/etc/litehelm/token').read_text().strip()
def get(path, headers=None):
 r=urllib.request.Request('http://127.0.0.1:9091'+path, headers=headers or {})
 try:
  with urllib.request.urlopen(r,timeout=20) as v: return v.status, v.headers, v.read()
 except urllib.error.HTTPError as e: return e.code, e.headers, e.read()
code,h,body=get('/')
assert code==200 and h['Content-Type'].startswith('text/html') and b'app.js' in body, (code,h)
assert "script-src 'self'" in h['Content-Security-Policy'] and h['X-Frame-Options']=='DENY'
assert get('/app.js')[0]==200 and get('/missing')[0]==404
assert get('/api/v1/system')[0]==401
auth={'Authorization':'Bearer '+token}
assert get('/api/v1/system', dict(auth, Origin='http://127.0.0.1:9091'))[0]==200
assert get('/api/v1/system', dict(auth, Origin='https://evil.example'))[0]==403
print('Web UI default on, same-origin API, cross-origin rejected: PASS')
PY
sh "$base/install.sh" --local-dir "$base/dist" --disable-web
grep -q 'LITEHELM_WEB=0' /etc/systemd/system/litehelm.service.d/30-litehelm-web.conf
[ "$(http_code /)" = 404 ]
[ "$(http_code /api/v1/system auth)" = 200 ]
sh "$base/install.sh" --local-dir "$base/dist"
[ -e /etc/systemd/system/litehelm.service.d/30-litehelm-web.conf ]
sh "$base/install.sh" --local-dir "$base/dist" --enable-web
[ ! -e /etc/systemd/system/litehelm.service.d/30-litehelm-web.conf ]
[ "$(http_code /)" = 200 ]
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
cat > "$tmp/litehelm-linux-amd64" <<'NOEXECUSER'
#!/bin/sh
if [ "${1:-}" = -h ]; then echo "Usage: litehelm [-allow-exec]"; exit 0; fi
exit 0
NOEXECUSER
(cd "$tmp" && sha256sum litehelm-linux-amd64 > SHA256SUMS)
if sh "$base/install.sh" --local-dir "$tmp" --enable-exec; then echo 'Accepted legacy binary as unprivileged script user'; exit 1; fi
[ "$binary_hash" = "$(sha256sum /usr/local/bin/litehelm)" ]
[ "$token_hash" = "$(sha256sum /etc/litehelm/token)" ]
systemctl is-active --quiet litehelm.service
printf '%s\n' 'Installer first install, reinstall, service actions/logs, protected unit, web UI toggle, checksum rejection and rollback: PASS'
