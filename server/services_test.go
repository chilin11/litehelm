//go:build linux

package main

import (
	"context"
	"os"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestInstalledServiceSurvivesUnload(t *testing.T) {
	ps := mergeServices("nginx.service loaded active running Web server\n● worker.service loaded failed failed Worker\n", "nginx.service enabled enabled\nstopped.service disabled disabled\nlocked.service masked enabled\nworker@.service disabled enabled\n")
	units := map[string]serviceInfo{}
	for _, p := range ps {
		units[p.Name] = p
	}
	if len(units) != 4 || units["nginx.service"].Active != "active" || units["stopped.service"].Active != "inactive" || units["locked.service"].Load != "masked" || units["worker.service"].Active != "failed" {
		t.Fatal(units)
	}
}
func TestUTF8OutputAndTruncation(t *testing.T) {
	p := t.TempDir() + "/log"
	raw := strings.Repeat("a", 65535) + "中"
	if e := os.WriteFile(p, []byte(raw), 0600); e != nil {
		t.Fatal(e)
	}
	cat := "cat"
	if _, e := os.Stat("/system/bin/cat"); e == nil {
		cat = "/system/bin/cat"
	}
	value, tr, e := commandLimit(context.Background(), 65536, cat, p)
	if e != nil || !tr || !utf8.ValidString(value) || len(value) > 65536 || value != strings.Repeat("a", 65535) {
		t.Fatal(len(value), tr, e)
	}
	value, tr, e = commandLimit(context.Background(), 128<<10, cat, p)
	if e != nil || tr || value != raw {
		t.Fatal(len(value), tr, e)
	}
}
