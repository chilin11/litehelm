//go:build linux

package main

import (
	"context"
	"encoding/json"
	"golang.org/x/sys/unix"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPortParser(t *testing.T) {
	raw := `tcp LISTEN 0 4096 0.0.0.0:80 0.0.0.0:* users:(("nginx",pid=123,fd=6),("nginx",pid=456,fd=6))
udp UNCONN 0 0 [::]:53 [::]:*
not a socket`
	rows, tr := parsePorts(raw)
	if tr || len(rows) != 3 || rows[0].PID != 123 || rows[1].PID != 456 || rows[2].Address != "[::]:53" || rows[2].PID != 0 {
		t.Fatal(rows, tr)
	}
	_, tr = parsePorts(strings.Repeat("tcp LISTEN 0 1 *:80 *:*\n", 510))
	if !tr {
		t.Fatal("missing truncation")
	}
}
func TestTimerParserWhitelist(t *testing.T) {
	t.Setenv("LITEHELM_RUN_TIMERS", "backup.timer, system.timer")
	rows := parseTimers("Id=backup.timer\nLoadState=loaded\nUnit=backup.service\nDescription=Backup\nActiveState=active\nSubState=waiting\nNextElapseUSecRealtime=Sat 2026-10-17 12:00:00 UTC\n\nId=system.timer\nLoadState=loaded\nUnit=sshd.service\n", testApp())
	if len(rows) != 2 || rows[0].Unit != "backup.service" || rows[1].CanRun || !timerAllowed("backup.timer") || timerAllowed("backup.timer;reboot") {
		t.Fatal(rows)
	}
	if rows[0].CanRun != (os.Geteuid() == 0) {
		t.Fatal("loaded allowed target rejected", rows)
	}
	for _, state := range []string{"not-found", "masked", "error", ""} {
		bad := parseTimers("Id=backup.timer\nLoadState="+state+"\nUnit=backup.service\n", testApp())
		if len(bad) != 1 || bad[0].CanRun {
			t.Fatal("invalid timer accepted", bad)
		}
	}
	missing := parseTimers("Id=backup.timer\nLoadState=loaded\n", testApp())
	if len(missing) != 1 || missing[0].CanRun || missing[0].Unit != "" {
		t.Fatal("guessed timer target", missing)
	}
	w := request(testApp(), "POST", "/api/v1/timers/bad;run.timer/run", `{"confirm":"bad;run.timer"}`, testToken)
	if w.Code != 400 {
		t.Fatal(w.Code)
	}
	w = request(testApp(), "POST", "/api/v1/timers/backup.timer/run", `{"confirm":"other.timer"}`, testToken)
	if w.Code != 400 {
		t.Fatal(w.Code)
	}
}
func makeScanFixture(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	if resolved, e := filepath.EvalSymlinks(root); e == nil {
		root = resolved
	}
	if e := os.Mkdir(filepath.Join(root, "child"), 0700); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(root, "a"), []byte("abc"), 0600); e != nil {
		t.Fatal(e)
	}
	if e := os.WriteFile(filepath.Join(root, "child", "b"), []byte("hello"), 0600); e != nil {
		t.Fatal(e)
	}
	return root
}
func TestDirectoryScanLimitsAndLinks(t *testing.T) {
	root := makeScanFixture(t)
	r, e := scanDirectory(context.Background(), root, 100, 6, time.Second)
	if e != nil || r.Bytes != 8 || r.Files != 2 || r.Partial {
		t.Fatal(r, e)
	}
	if e = os.Symlink("/etc", filepath.Join(root, "link")); e != nil {
		t.Fatal(e)
	}
	r, e = scanDirectory(context.Background(), root, 100, 6, time.Second)
	if e != nil || r.Bytes != 8 || !r.Partial || !strings.Contains(r.Reason, "symlinks") {
		t.Fatal(r, e)
	}
	r, e = scanDirectory(context.Background(), root, 1, 6, time.Second)
	if e != nil || !r.Partial || !strings.Contains(r.Reason, "entry limit") {
		t.Fatal(r, e)
	}
	r, e = scanDirectory(context.Background(), root, 100, 0, time.Second)
	if e != nil || r.Bytes != 3 || !strings.Contains(r.Reason, "depth limit") {
		t.Fatal(r, e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	r, e = scanDirectory(ctx, root, 100, 6, time.Second)
	if e != nil || !strings.Contains(r.Reason, "cancelled") {
		t.Fatal(r, e)
	}
	r, e = scanDirectory(context.Background(), root, 100, 6, -time.Second)
	if e != nil || !strings.Contains(r.Reason, "time limit") {
		t.Fatal(r, e)
	}
	link := root + "-link"
	if e = os.Symlink(root, link); e != nil {
		t.Fatal(e)
	}
	defer os.Remove(link)
	if fd, e := openScanRoot(filepath.Join(link, "child")); e == nil {
		os.NewFile(uintptr(fd), "").Close()
		t.Fatal("followed ancestor symlink")
	}
	for _, path := range []string{"/", "relative", "/var/../etc"} {
		if _, e = openScanRoot(path); e == nil {
			t.Fatal("accepted", path)
		}
	}
}
func TestScanAPIWhitelistAndCooldown(t *testing.T) {
	root := makeScanFixture(t)
	t.Setenv("LITEHELM_SCAN_ROOTS", root+",/proc,/sys,/dev,/,relative,"+root)
	roots := scanRoots()
	if len(roots) != 1 || roots[0] != root {
		t.Fatal(roots)
	}
	a := testApp()
	w := request(a, "POST", "/api/v1/disk-scan", `{"path":"/etc"}`, testToken)
	if w.Code != 403 {
		t.Fatal(w.Code)
	}
	b, _ := json.Marshal(map[string]string{"path": root})
	w = request(a, "POST", "/api/v1/disk-scan", string(b), testToken)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var data scanResult
	if e := json.Unmarshal(w.Body.Bytes(), &data); e != nil || data.Bytes != 8 {
		t.Fatal(data, e)
	}
	w = request(a, "POST", "/api/v1/disk-scan", string(b), testToken)
	if w.Code != 429 {
		t.Fatal(w.Code)
	}
	w = request(a, "GET", "/api/v1/disks", "", testToken)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "inodes_total") {
		t.Fatal(w.Code, w.Body.String())
	}
	for _, fs := range []string{"nfs", "cifs", "fuse.sshfs"} {
		if localFS(fs) {
			t.Fatal(fs)
		}
	}
}

func TestScanRejectsChildMountBeforeStat(t *testing.T) {
	fd, err := unix.Open("/", unix.O_PATH|unix.O_DIRECTORY|unix.O_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fd)
	id, err := mountID(fd)
	if err != nil || id == "" {
		t.Fatal(id, err)
	}
	var st unix.Stat_t
	if err = unix.Fstat(fd, &st); err != nil {
		t.Fatal(err)
	}
	b := &scanBudget{ctx: context.Background(), deadline: time.Now().Add(time.Second), maxEntries: 100, maxDepth: 6, reasons: map[string]bool{}, dev: uint64(st.Dev)}
	if _, ok := b.entry(fd, "proc", 0); ok || !b.reasons["filesystem boundary"] {
		t.Fatal("crossed child mount", b.reasons)
	}
}
