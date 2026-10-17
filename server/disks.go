//go:build linux

package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

type diskInfo struct {
	Mount       string `json:"mountpoint"`
	Device      string `json:"device"`
	Filesystem  string `json:"filesystem"`
	Total       uint64 `json:"total"`
	Used        uint64 `json:"used"`
	Available   uint64 `json:"available"`
	InodesTotal uint64 `json:"inodes_total"`
	InodesUsed  uint64 `json:"inodes_used"`
}

func localFS(s string) bool {
	switch s {
	case "ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "vfat", "overlay", "tmpfs":
		return true
	}
	return false
}
func unescapeMount(s string) string {
	return strings.NewReplacer(`\040`, " ", `\011`, "\t", `\012`, "\n", `\134`, `\`).Replace(s)
}
func scanRoots() []string {
	raw, ok := os.LookupEnv("LITEHELM_SCAN_ROOTS")
	if !ok {
		raw = "/var/log,/var/cache"
	}
	roots := []string{}
	seen := map[string]bool{}
	for _, s := range strings.Split(raw, ",") {
		s = strings.TrimSpace(s)
		if !filepath.IsAbs(s) {
			continue
		}
		s = filepath.Clean(s)
		bad := s == "/"
		for _, p := range []string{"/proc", "/sys", "/dev"} {
			if s == p || strings.HasPrefix(s, p+"/") {
				bad = true
			}
		}
		if bad || seen[s] {
			continue
		}
		seen[s] = true
		if len(roots) < 16 {
			roots = append(roots, s)
		}
	}
	return roots
}

// Do not call statfs on known remote filesystems or FUSE.
func (a *app) disks(w http.ResponseWriter, r *http.Request) {
	rows := []diskInfo{}
	seen := map[string]bool{}
	tr := false
	for _, line := range strings.Split(file("/proc/self/mountinfo"), "\n") {
		halves := strings.SplitN(line, " - ", 2)
		if len(halves) != 2 {
			continue
		}
		left, right := strings.Fields(halves[0]), strings.Fields(halves[1])
		if len(left) < 5 || len(right) < 2 || !localFS(right[0]) {
			continue
		}
		mount := unescapeMount(left[4])
		if seen[mount] {
			continue
		}
		if len(rows) >= 64 {
			tr = true
			break
		}
		select {
		case <-r.Context().Done():
			return
		default:
		}
		fd, err := unix.Open(mount, unix.O_PATH|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
		if err != nil {
			continue
		}
		id, idErr := mountID(fd)
		var s unix.Statfs_t
		statErr := error(nil)
		if idErr == nil && id == left[0] {
			statErr = unix.Fstatfs(fd, &s)
		}
		unix.Close(fd)
		if idErr != nil || id != left[0] || statErr != nil || !localScanType(uint64(s.Type)) {
			continue
		}
		seen[mount] = true
		usedInodes := uint64(0)
		if s.Files >= s.Ffree {
			usedInodes = s.Files - s.Ffree
		}
		rows = append(rows, diskInfo{mount, unescapeMount(right[1]), right[0], s.Blocks * uint64(s.Bsize), (s.Blocks - s.Bfree) * uint64(s.Bsize), s.Bavail * uint64(s.Bsize), s.Files, usedInodes})
	}
	output(w, 200, map[string]any{"disks": rows, "scan_roots": scanRoots(), "truncated": tr})
}

func mountID(fd int) (string, error) {
	data, err := os.ReadFile("/proc/self/fdinfo/" + strconv.Itoa(fd))
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(string(data), "\n") {
		if key, value, ok := strings.Cut(line, ":"); ok && key == "mnt_id" {
			return strings.TrimSpace(value), nil
		}
	}
	return "", fmt.Errorf("mount identity unavailable")
}

type scanEntry struct {
	Name  string `json:"name"`
	Bytes uint64 `json:"bytes"`
	Files int    `json:"files"`
}
type scanResult struct {
	Path    string      `json:"path"`
	Bytes   uint64      `json:"bytes"`
	Files   int         `json:"files"`
	Entries []scanEntry `json:"entries"`
	Partial bool        `json:"partial"`
	Reason  string      `json:"reason"`
	Elapsed int64       `json:"elapsed_ms"`
	Metric  string      `json:"metric"`
}
type scanBudget struct {
	ctx                           context.Context
	deadline                      time.Time
	maxEntries, maxDepth, visited int
	reasons                       map[string]bool
	stop                          bool
	dev                           uint64
}

func (b *scanBudget) partial(reason string) { b.reasons[reason] = true }
func (b *scanBudget) check() bool {
	if b.stop {
		return false
	}
	if b.ctx.Err() != nil {
		b.partial("cancelled")
		b.stop = true
		return false
	}
	if time.Now().After(b.deadline) {
		b.partial("time limit")
		b.stop = true
		return false
	}
	if b.visited >= b.maxEntries {
		b.partial("entry limit")
		b.stop = true
		return false
	}
	return true
}

// fd-based traversal: never follow symlinks, even during rename races.
func (b *scanBudget) entry(fd int, name string, depth int) (scanEntry, bool) {
	row := scanEntry{Name: name}
	entryFD, err := unix.Openat2(fd, name, &unix.OpenHow{Flags: unix.O_PATH | unix.O_NOFOLLOW | unix.O_CLOEXEC, Resolve: unix.RESOLVE_BENEATH | unix.RESOLVE_NO_XDEV})
	if err != nil {
		if err == unix.EXDEV {
			b.partial("filesystem boundary")
		} else {
			b.partial("permission or changed entry")
		}
		return row, false
	}
	defer unix.Close(entryFD)
	var st unix.Stat_t
	if unix.Fstat(entryFD, &st) != nil || uint64(st.Dev) != b.dev {
		b.partial("permission or changed entry")
		return row, false
	}
	switch st.Mode & unix.S_IFMT {
	case unix.S_IFREG:
		if st.Size > 0 {
			row.Bytes = uint64(st.Size)
		}
		row.Files = 1
	case unix.S_IFDIR:
		if depth >= b.maxDepth {
			b.partial("depth limit")
			return row, false
		}
		child, err := unix.Openat(entryFD, ".", unix.O_RDONLY|unix.O_DIRECTORY|unix.O_NOFOLLOW|unix.O_CLOEXEC|unix.O_NONBLOCK, 0)
		if err != nil {
			b.partial("permission or changed entry")
			return row, false
		}
		var actual unix.Stat_t
		if unix.Fstat(child, &actual) != nil || uint64(actual.Dev) != b.dev || actual.Ino != st.Ino {
			unix.Close(child)
			b.partial("changed entry")
			return row, false
		}
		row.Bytes, row.Files = b.walk(child, depth+1)
	case unix.S_IFLNK:
		b.partial("symlinks skipped")
		return row, false
	default:
		b.partial("special files skipped")
		return row, false
	}
	return row, true
}
func (b *scanBudget) walk(fd int, depth int) (uint64, int) {
	dir := os.NewFile(uintptr(fd), "scan-directory")
	defer dir.Close()
	var total uint64
	files := 0
	for b.check() {
		entries, e := dir.ReadDir(64)
		for _, en := range entries {
			if !b.check() {
				break
			}
			b.visited++
			row, ok := b.entry(fd, en.Name(), depth)
			if ok {
				total += row.Bytes
				files += row.Files
			}
		}
		if e != nil {
			if e != io.EOF {
				b.partial("directory read error")
			}
			break
		}
	}
	return total, files
}
func localScanType(kind uint64) bool {
	switch kind {
	case unix.EXT4_SUPER_MAGIC, unix.XFS_SUPER_MAGIC, unix.BTRFS_SUPER_MAGIC, unix.F2FS_SUPER_MAGIC, unix.OVERLAYFS_SUPER_MAGIC, unix.TMPFS_MAGIC, unix.MSDOS_SUPER_MAGIC:
		return true
	}
	return false
}
func openScanRoot(path string) (int, error) {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path || path == "/" {
		return -1, fmt.Errorf("invalid scan root")
	}
	fd, e := unix.Open("/", unix.O_PATH|unix.O_DIRECTORY|unix.O_CLOEXEC, 0)
	if e != nil {
		return -1, e
	}
	parts := strings.Split(strings.TrimPrefix(path, "/"), "/")
	for i, part := range parts {
		flags := unix.O_PATH | unix.O_DIRECTORY | unix.O_NOFOLLOW | unix.O_CLOEXEC
		if i == len(parts)-1 {
			flags = unix.O_RDONLY | unix.O_DIRECTORY | unix.O_NOFOLLOW | unix.O_CLOEXEC | unix.O_NONBLOCK
		}
		next, err := unix.Openat(fd, part, flags, 0)
		unix.Close(fd)
		if err != nil {
			return -1, err
		}
		fd = next
	}
	return fd, nil
}
func scanDirectory(ctx context.Context, path string, limit int, depth int, duration time.Duration) (scanResult, error) {
	started := time.Now()
	result := scanResult{Path: path, Entries: []scanEntry{}, Metric: "logical_bytes"}
	fd, e := openScanRoot(path)
	if e != nil {
		return result, e
	}
	dir := os.NewFile(uintptr(fd), path)
	defer dir.Close()
	var st unix.Stat_t
	var fs unix.Statfs_t
	if e = unix.Fstat(fd, &st); e != nil {
		return result, e
	}
	if e = unix.Fstatfs(fd, &fs); e != nil {
		return result, e
	}
	if !localScanType(uint64(fs.Type)) {
		return result, fmt.Errorf("remote or virtual filesystem cannot be scanned")
	}
	probe, probeErr := unix.Openat2(fd, ".", &unix.OpenHow{Flags: unix.O_PATH | unix.O_DIRECTORY | unix.O_CLOEXEC, Resolve: unix.RESOLVE_BENEATH | unix.RESOLVE_NO_XDEV})
	if probeErr != nil {
		return result, fmt.Errorf("safe scan requires openat2 support (Linux 5.6+): %w", probeErr)
	}
	unix.Close(probe)
	budget := &scanBudget{ctx: ctx, deadline: started.Add(duration), maxEntries: limit, maxDepth: depth, reasons: map[string]bool{}, dev: uint64(st.Dev)}
	for budget.check() {
		entries, err := dir.ReadDir(64)
		for _, en := range entries {
			if !budget.check() {
				break
			}
			budget.visited++
			row, ok := budget.entry(fd, en.Name(), 0)
			if !ok {
				continue
			}
			result.Bytes += row.Bytes
			result.Files += row.Files
			result.Entries = append(result.Entries, row)
		}
		if err != nil {
			if err != io.EOF {
				budget.partial("directory read error")
			}
			break
		}
	}
	sort.Slice(result.Entries, func(i, j int) bool { return result.Entries[i].Bytes > result.Entries[j].Bytes })
	if len(result.Entries) > 20 {
		result.Entries = result.Entries[:20]
	}
	reasons := []string{}
	for reason := range budget.reasons {
		reasons = append(reasons, reason)
	}
	sort.Strings(reasons)
	result.Partial = len(reasons) > 0
	result.Reason = strings.Join(reasons, ", ")
	result.Elapsed = time.Since(started).Milliseconds()
	return result, nil
}
func (a *app) diskScan(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path string `json:"path"`
	}
	if !decode(w, r, &req) {
		return
	}
	allowed := false
	for _, p := range scanRoots() {
		if req.Path == p {
			allowed = true
		}
	}
	if !allowed {
		fail(w, 403, "Choose one configured scan root")
		return
	}
	if !a.scanMu.TryLock() {
		fail(w, 429, "A directory scan is already running")
		return
	}
	defer a.scanMu.Unlock()
	if time.Since(a.lastScan) < 15*time.Second {
		fail(w, 429, "Directory scan cooldown: wait 15 seconds")
		return
	}
	a.lastScan = time.Now()
	result, e := scanDirectory(r.Context(), req.Path, 10000, 6, 2*time.Second)
	if e != nil {
		fail(w, 400, "Cannot scan configured directory: "+e.Error())
		return
	}
	output(w, 200, result)
}
