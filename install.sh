#!/bin/sh
# LiteHelm installer: native Linux binary + systemd, or explicit private-directory deployment.
set -eu
REPO=chilin11/litehelm
VERSION=latest
LOCAL_DIR=
MANUAL_DIR=
EXEC=keep
EXEC_USER=
WEB=keep
usage() {
 cat <<'HELP'
LiteHelm · 轻舵
Usage: sudo sh install.sh [--version vX.Y.Z] [--local-dir DIRECTORY] [--enable-exec|--disable-exec] [--exec-user USER] [--disable-web|--enable-web]
       sh install.sh --manual-dir /absolute/private/directory [--version vX.Y.Z] [--local-dir DIRECTORY]
  --version       Install a specific GitHub release (default: latest)
  --local-dir     Use binaries and SHA256SUMS from a local directory
  --manual-dir    Deploy binary and token only; no root, systemd, account changes or automatic startup
  --enable-exec   Allow the app to run arbitrary scripts as an unprivileged user (litehelm, created if missing)
  --exec-user     Run scripts as another existing account, e.g. root (implies --enable-exec)
  --disable-exec  Remove script execution again (the litehelm user is kept)
  --disable-web   Stop serving the built-in web UI (API for the app is unaffected)
  --enable-web    Serve the built-in web UI again (default)
  --help          Show help without changing anything
Existing token, service unit and drop-ins are preserved; script execution, script user and web UI are unchanged unless requested.
Manual mode preserves its own token and never modifies an existing system installation. Configure runtime flags when starting it.
HELP
}
while [ "$#" -gt 0 ]; do
 case "$1" in
  --version|--local-dir|--manual-dir)
   [ "$#" -ge 2 ] && [ -n "$2" ] || { usage; exit 1; }
   opt=$1; shift
   case "$opt" in --version) VERSION=$1;; --local-dir) LOCAL_DIR=$1;; --manual-dir) MANUAL_DIR=$1;; esac;;
  --enable-exec) EXEC=on;;
  --exec-user) [ "$#" -ge 2 ] || { usage; exit 1; }; shift; EXEC_USER=$1;;
  --disable-exec) EXEC=off;;
  --disable-web) WEB=off;;
  --enable-web) WEB=on;;
  --help|-h) usage; exit 0;;
  *) echo "Unknown option: $1" >&2; usage; exit 1;;
 esac
 shift
done
case "$VERSION" in latest|v[0-9]*) ;; *) echo 'Invalid version' >&2; exit 1;; esac
if [ -n "$EXEC_USER" ]; then
 case "$EXEC_USER" in [a-z_]*) ;; *) echo 'Invalid --exec-user' >&2; exit 1;; esac
 case "$EXEC_USER" in *[!a-z0-9_.-]*) echo 'Invalid --exec-user' >&2; exit 1;; esac
 [ "${#EXEC_USER}" -le 32 ] || { echo 'Invalid --exec-user' >&2; exit 1; }
 [ "$EXEC" != off ] || { echo '--exec-user cannot be combined with --disable-exec' >&2; exit 1; }
 EXEC=on
fi
case "$VERSION" in *[!a-zA-Z0-9._-]*) echo 'Invalid version' >&2; exit 1;; esac
[ "$(uname -s)" = Linux ] || { echo 'Linux is required' >&2; exit 1; }
if [ -z "$MANUAL_DIR" ]; then
 command -v systemctl >/dev/null || { echo 'System install requires systemd; use --manual-dir /absolute/private/directory (no sudo) on NAS/non-systemd hosts' >&2; exit 1; }
 [ -d /run/systemd/system ] || { echo 'systemd must be running; use --manual-dir /absolute/private/directory (no sudo) instead' >&2; exit 1; }
 [ "$(id -u)" = 0 ] || { echo 'Run system installation with sudo sh install.sh, or use --manual-dir without sudo' >&2; exit 1; }
 # A systemctl executable or a stale runtime directory alone is not sufficient.
 systemctl show --property=Version >/dev/null || { echo 'Cannot reach the systemd manager; installation left untouched' >&2; exit 1; }
else
 [ "$EXEC" = keep ] && [ "$WEB" = keep ] || { echo '--manual-dir cannot be combined with exec/web installer options; pass runtime flags to the binary instead' >&2; exit 1; }
 case "$MANUAL_DIR" in
  /*) ;;
  *) echo '--manual-dir must be an absolute path' >&2; exit 1;;
 esac
 case "$MANUAL_DIR" in
  /|*/|*//*|*/./*|*/../*|*/.|*/..) echo '--manual-dir must be a normalized private directory path, not /' >&2; exit 1;;
 esac
 command -v stat >/dev/null || { echo 'stat with -c support is required for manual deployment (GNU or BusyBox)' >&2; exit 1; }
 # Reject symbolic links in every component, including a dangling final link.
 rest=${MANUAL_DIR#/}; checked=
 while [ -n "$rest" ]; do
  part=${rest%%/*}; checked=$checked/$part
  [ ! -L "$checked" ] || { echo 'Manual directory must not contain symbolic links' >&2; exit 1; }
  if [ "$rest" = "$part" ]; then rest=; else rest=${rest#*/}; fi
 done
 umask 077
 mkdir -p "$MANUAL_DIR"
 [ "$(stat -c %u "$MANUAL_DIR")" = "$(id -u)" ] || { echo 'Manual directory must be owned by the current user' >&2; exit 1; }
 case "$(stat -c %a "$MANUAL_DIR")" in *00) ;; *) echo 'Manual directory must be private (mode 0700); adjust its permissions yourself' >&2; exit 1;; esac
 for name in litehelm token; do
  if [ -e "$MANUAL_DIR/$name" ] || [ -L "$MANUAL_DIR/$name" ]; then
   [ -f "$MANUAL_DIR/$name" ] && [ ! -L "$MANUAL_DIR/$name" ] || { echo "Manual $name must be a regular non-symlink file" >&2; exit 1; }
  fi
 done
 if [ -e "$MANUAL_DIR/token" ]; then
  [ "$(stat -c %u:%a "$MANUAL_DIR/token")" = "$(id -u):600" ] || { echo 'Existing manual token must be owned by the current user with mode 0600; left untouched' >&2; exit 1; }
 fi
fi
case "$(uname -m)" in
 x86_64) arch=amd64;; aarch64|arm64) arch=arm64;; armv7l|armv8l) arch=armv7;;
 *) echo 'Unsupported architecture; build server from source' >&2; exit 1;;
esac
command -v sha256sum >/dev/null || { echo 'sha256sum is required' >&2; exit 1; }
# mkdir is an atomic lock and works on minimal systems without flock.
lock=/run/litehelm-install.lock
if [ -n "$MANUAL_DIR" ]; then lock=$MANUAL_DIR/.install.lock; fi
mkdir "$lock" 2>/dev/null || { echo "Cannot lock $lock; check access or a concurrent/stale installation" >&2; exit 1; }
tmp=
cleanup() { [ -z "$tmp" ] || rm -rf "$tmp"; rmdir "$lock" 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
if [ -n "$MANUAL_DIR" ]; then
 # Test execution on the destination filesystem as well (NAS shares may be noexec).
 tmp=$(mktemp -d "$MANUAL_DIR/.install.XXXXXX")
else
 tmp=$(mktemp -d /tmp/litehelm-install.XXXXXX)
fi
asset=litehelm-linux-$arch
if [ -n "$LOCAL_DIR" ]; then
 if [ ! -f "$LOCAL_DIR/$asset" ] || [ ! -f "$LOCAL_DIR/SHA256SUMS" ]; then echo 'Local directory must contain binary and SHA256SUMS' >&2; exit 1; fi
 cp "$LOCAL_DIR/$asset" "$tmp/$asset"
 cp "$LOCAL_DIR/SHA256SUMS" "$tmp/SHA256SUMS"
else
 command -v curl >/dev/null || { echo 'curl is required for online install' >&2; exit 1; }
 if [ "$VERSION" = latest ]; then base="https://github.com/$REPO/releases/latest/download"; else base="https://github.com/$REPO/releases/download/$VERSION"; fi
 curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 180 "$base/$asset" -o "$tmp/$asset"
 curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 "$base/SHA256SUMS" -o "$tmp/SHA256SUMS"
fi
expected=$(awk -v name="$asset" '$2 == name {print $1}' "$tmp/SHA256SUMS")
[ "${#expected}" -eq 64 ] || { echo 'Missing or invalid asset checksum' >&2; exit 1; }
case "$expected" in *[!a-fA-F0-9]*) echo 'Invalid checksum' >&2; exit 1;; esac
actual=$(sha256sum "$tmp/$asset"); actual=${actual%% *}
[ "$actual" = "$expected" ] || { echo 'SHA256 mismatch; installed version left untouched' >&2; exit 1; }
chmod 755 "$tmp/$asset"
# Smoke-test architecture before replacing anything.
"$tmp/$asset" -h >/dev/null 2>&1 || { echo 'Binary cannot run on this host/filesystem; check CPU architecture, kernel and noexec mount options' >&2; exit 1; }
if [ -n "$MANUAL_DIR" ]; then
 if [ ! -e "$MANUAL_DIR/token" ]; then
  "$tmp/$asset" -generate-token > "$tmp/token"
  chmod 600 "$tmp/token"
  # Exclusive creation: never replace a token created by another invocation.
  ln "$tmp/token" "$MANUAL_DIR/token"
 fi
 # Same-filesystem atomic replacement; an already-running process is not restarted.
 mv -f "$tmp/$asset" "$MANUAL_DIR/litehelm"
 printf 'Manual deployment ready in %s\n' "$MANUAL_DIR"
 printf '%s\n' 'From that directory run: GOMEMLIMIT=32MiB GOGC=75 ./litehelm -token-file ./token' 'No service was installed or restarted. Token preserved; configure your own foreground supervisor and HTTPS reverse proxy.' 'This mode does not inherit the systemd sandbox. Prefer an unprivileged NAS account; scripts and power control are off by default.'
 exit 0
fi
# Script jobs run as a dedicated unprivileged account unless another one is chosen.
exec_conf=/etc/systemd/system/litehelm.service.d/20-litehelm-exec.conf
prev_user=
if [ -f "$exec_conf" ]; then prev_user=$(sed -n 's/^Environment=LITEHELM_EXEC_USER=//p' "$exec_conf" | tail -n 1); fi
exec_user=${EXEC_USER:-${prev_user:-litehelm}}
if [ "$EXEC" = on ] || { [ "$EXEC" = keep ] && [ -e "$exec_conf" ]; }; then
 if ! "$tmp/$asset" -h 2>&1 | grep -q -- '-exec-user'; then
  if [ "$exec_user" != root ]; then
   echo "Installed litehelm binary does not support --exec-user; run scripts as root with --exec-user root or upgrade binary" >&2
   exit 1
  fi
 fi
 if [ "$exec_user" = litehelm ]; then
  if ! id -u litehelm >/dev/null 2>&1; then
   command -v useradd >/dev/null || { echo 'useradd is required to create the litehelm script user' >&2; exit 1; }
   nologin=$(command -v nologin || echo /bin/false)
   useradd --system --user-group --create-home --home-dir /home/litehelm --shell "$nologin" --comment 'LiteHelm script jobs' litehelm
   chmod 700 /home/litehelm
   echo 'Created unprivileged system user litehelm for script jobs'
  fi
 elif ! id -u "$exec_user" >/dev/null 2>&1; then
  echo "Script user $exec_user does not exist; create it first" >&2; exit 1
 fi
fi
install -d -m 700 /etc/litehelm
if [ ! -e /etc/litehelm/token ]; then
 umask 077
 "$tmp/$asset" -generate-token > "$tmp/token"
 install -m 600 "$tmp/token" /etc/litehelm/token
fi
if [ ! -f /etc/litehelm/token ] || [ -L /etc/litehelm/token ]; then echo 'Token must be a regular non-symlink file' >&2; exit 1; fi
chmod 600 /etc/litehelm/token
had_binary=false
had_unit=false
was_active=false
if [ -f /usr/local/bin/litehelm ]; then cp -p /usr/local/bin/litehelm "$tmp/previous"; had_binary=true; fi
if [ -e /etc/systemd/system/litehelm.service ]; then had_unit=true; fi
if systemctl is-active --quiet litehelm.service; then was_active=true; fi
install -m 755 "$tmp/$asset" /usr/local/bin/litehelm.new
mv -f /usr/local/bin/litehelm.new /usr/local/bin/litehelm
if [ "$had_unit" = false ]; then
 cat > /etc/systemd/system/litehelm.service <<'UNIT'
[Unit]
Description=LiteHelm lightweight mobile server manager
After=network.target

[Service]
Type=simple
User=root
ExecStart=/usr/local/bin/litehelm -listen 127.0.0.1:9091 -token-file /etc/litehelm/token
Environment=LITEHELM_UNIT=litehelm.service
Environment=GOMEMLIMIT=32MiB
Environment=GOGC=75
Restart=on-failure
RestartSec=3
TimeoutStopSec=10
UMask=0077
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=false
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
CapabilityBoundingSet=CAP_KILL CAP_DAC_READ_SEARCH CAP_SYS_PTRACE

[Install]
WantedBy=multi-user.target
UNIT
fi
# Older systemd blocks openat2 when RestrictSUIDSGID is enabled.
# Keep existing units and user drop-ins; add one named compatibility setting.
compat=/etc/systemd/system/litehelm.service.d/10-litehelm-openat2.conf
added_compat=false
if [ ! -e "$compat" ]; then
 install -d -m 755 /etc/systemd/system/litehelm.service.d
 printf '%s\n' '[Service]' '# Safe read-only scans require openat2.' 'RestrictSUIDSGID=false' > "$compat"
 chmod 644 "$compat"
 added_compat=true
fi
# Script jobs need a writable state directory under ProtectSystem=strict.
had_exec=false
if [ -e "$exec_conf" ]; then cp -p "$exec_conf" "$tmp/exec.conf"; had_exec=true; fi
if [ "$EXEC" = on ]; then
 install -d -m 755 /etc/systemd/system/litehelm.service.d
 {
  printf '%s\n' '[Service]' '# Managed by install.sh --enable-exec/--exec-user: the app may run arbitrary scripts as the script user.' 'Environment=LITEHELM_ALLOW_EXEC=1' 'StateDirectory=litehelm' 'StateDirectoryMode=0700'
  if [ "$exec_user" != litehelm ]; then printf 'Environment=LITEHELM_EXEC_USER=%s\n' "$exec_user"; fi
 } > "$exec_conf"
 chmod 644 "$exec_conf"
elif [ "$EXEC" = off ]; then
 rm -f "$exec_conf"
fi
# The web UI is on by default; a managed drop-in turns it off.
web_conf=/etc/systemd/system/litehelm.service.d/30-litehelm-web.conf
had_web=false
if [ -e "$web_conf" ]; then cp -p "$web_conf" "$tmp/web.conf"; had_web=true; fi
if [ "$WEB" = off ]; then
 install -d -m 755 /etc/systemd/system/litehelm.service.d
 printf '%s\n' '[Service]' '# Managed by install.sh --disable-web: only the /api/v1 API is served.' 'Environment=LITEHELM_WEB=0' > "$web_conf"
 chmod 644 "$web_conf"
elif [ "$WEB" = on ]; then
 rm -f "$web_conf"
fi
rollback() {
 echo 'Startup failed; restoring previous binary and service state' >&2
 systemctl stop litehelm.service || true
 if [ "$had_binary" = true ]; then install -m 755 "$tmp/previous" /usr/local/bin/litehelm.new; mv -f /usr/local/bin/litehelm.new /usr/local/bin/litehelm; else rm -f /usr/local/bin/litehelm; fi
 if [ "$had_unit" = false ]; then rm -f /etc/systemd/system/litehelm.service; fi
 if [ "$added_compat" = true ]; then rm -f "$compat"; fi
 if [ "$had_exec" = true ]; then cp -p "$tmp/exec.conf" "$exec_conf"; else rm -f "$exec_conf"; fi
 if [ "$had_web" = true ]; then cp -p "$tmp/web.conf" "$web_conf"; else rm -f "$web_conf"; fi
 systemctl daemon-reload || true
 if [ "$was_active" = true ]; then systemctl start litehelm.service || true; fi
 echo 'Token has been preserved. Inspect: journalctl -u litehelm -n 40 --no-pager' >&2
 exit 1
}
systemctl daemon-reload || rollback
systemctl restart litehelm.service || rollback
# Let immediate crashes appear before reporting success.
sleep 2
systemctl is-active --quiet litehelm.service || rollback
systemctl enable litehelm.service
printf '%s\n' 'LiteHelm is running at http://127.0.0.1:9091' 'Publish this local endpoint using your HTTPS reverse proxy; the same origin serves the app API and the web UI.' 'Read the token locally: sudo cat /etc/litehelm/token' 'Existing token, unit and user drop-ins were preserved; managed openat2 compatibility setting installed.'
if [ -e "$exec_conf" ]; then echo "Script execution: enabled as $exec_user (change with --exec-user, disable with --disable-exec)"; else echo 'Script execution: disabled (enable with --enable-exec)'; fi
if [ -e "$web_conf" ]; then echo 'Web UI: disabled (enable with --enable-web)'; else echo 'Web UI: enabled at your HTTPS origin (disable with --disable-web)'; fi
