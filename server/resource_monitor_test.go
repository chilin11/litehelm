//go:build linux

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func resourceFixture(total, idle, memoryUsed, diskUsed uint64) resourceReading {
	return resourceReading{
		TotalTicks: total, IdleTicks: idle,
		Memory: memoryUsage{Total: 10000, Used: memoryUsed, Available: 10000 - memoryUsed},
		Swap:   swapUsage{Total: 2000, Used: 500},
		Disk:   rootDiskUsage{Total: 10000, Used: diskUsed, Free: 10000 - diskUsed},
	}
}

func assertMonitor(t *testing.T, s resourceSnapshot, state string, sampledAt int64, resources ...string) {
	t.Helper()
	if s.Monitor.State != state || s.Monitor.SampledAt != sampledAt || s.Monitor.ThresholdPercent != 85 || s.Monitor.Alerts == nil || len(s.Monitor.Alerts) != len(resources) {
		t.Fatalf("unexpected monitor: %+v; want state=%s sampled_at=%d alerts=%v", s.Monitor, state, sampledAt, resources)
	}
	for i, resource := range resources {
		if s.Monitor.Alerts[i].Resource != resource || s.Monitor.Alerts[i].Percent < 85 || s.Monitor.Alerts[i].Percent > 100 {
			t.Fatalf("unexpected alert: %+v", s.Monitor.Alerts[i])
		}
	}
}

func TestResourceMonitorThresholds(t *testing.T) {
	for _, tc := range []struct {
		name               string
		busy, memory, disk uint64
		alerts             []string
	}{
		{"zero", 0, 0, 0, nil},
		{"below", 8499, 8499, 8499, nil},
		{"exactly", 8500, 8500, 8500, []string{"cpu", "memory", "disk"}},
		{"above", 8501, 8501, 8501, []string{"cpu", "memory", "disk"}},
		{"full", 10000, 10000, 10000, []string{"cpu", "memory", "disk"}},
		{"cpu_only", 8500, 0, 0, []string{"cpu"}},
		{"memory_only", 0, 8500, 0, []string{"memory"}},
		{"disk_only", 0, 0, 8500, []string{"disk"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			now := time.Unix(1700000000, 0)
			r := resourceFixture(1000, 100, tc.memory, tc.disk)
			m := newResourceMonitor(func() (resourceReading, error) { return r, nil })
			assertMonitor(t, m.snapshot(now), "warming_up", 0)
			m.sample(now)
			assertMonitor(t, m.snapshot(now), "warming_up", now.Unix())
			r.TotalTicks += 10000
			r.IdleTicks += 10000 - tc.busy
			now = now.Add(resourceSampleInterval)
			m.sample(now)
			s := m.snapshot(now)
			assertMonitor(t, s, "ok", now.Unix(), tc.alerts...)
			if math.Abs(s.CPUPercent-float64(tc.busy)/100) > 1e-9 {
				t.Fatal("wrong interval CPU", s.CPUPercent)
			}
			for _, alert := range s.Monitor.Alerts {
				if tc.name == "exactly" && alert.Percent != 85 {
					t.Fatal("threshold boundary", alert)
				}
			}
		})
	}
}

func TestResourceMonitorErrorsRecoveryAndExpiry(t *testing.T) {
	now := time.Unix(1700000000, 0)
	r := resourceFixture(1000, 100, 9000, 9000)
	readErr := errors.New("procfs unavailable")
	m := newResourceMonitor(func() (resourceReading, error) { return r, readErr })
	m.sample(now)
	assertMonitor(t, m.snapshot(now), "error", 0)
	readErr = nil
	now = now.Add(resourceSampleInterval)
	m.sample(now)
	assertMonitor(t, m.snapshot(now), "warming_up", now.Unix())
	r.TotalTicks += 100
	r.IdleTicks += 10
	now = now.Add(resourceSampleInterval)
	m.sample(now)
	good := m.snapshot(now)
	assertMonitor(t, good, "ok", now.Unix(), "cpu", "memory", "disk")
	assertMonitor(t, m.snapshot(now.Add(resourceMaxAge-time.Nanosecond)), "ok", now.Unix(), "cpu", "memory", "disk")
	assertMonitor(t, m.snapshot(now.Add(resourceMaxAge)), "error", now.Unix())
	// Expiring one response must not mutate the stored snapshot or other readers.
	assertMonitor(t, m.snapshot(now), "ok", now.Unix(), "cpu", "memory", "disk")
	readErr = errors.New("root statfs failed")
	now = now.Add(resourceSampleInterval)
	m.sample(now)
	failed := m.snapshot(now)
	assertMonitor(t, failed, "error", good.Monitor.SampledAt)
	if failed.CPUPercent != good.CPUPercent || failed.Memory != good.Memory || failed.Swap != good.Swap || failed.Disk != good.Disk {
		t.Fatal("read failure overwrote last published legacy metrics")
	}
	readErr = nil
	r.TotalTicks += 100
	r.IdleTicks += 10
	now = now.Add(resourceSampleInterval)
	m.sample(now)
	assertMonitor(t, m.snapshot(now), "warming_up", now.Unix())
	r.TotalTicks += 100
	r.IdleTicks += 10
	now = now.Add(resourceSampleInterval)
	m.sample(now)
	assertMonitor(t, m.snapshot(now), "ok", now.Unix(), "cpu", "memory", "disk")
	// A long scheduling gap is not a continuous CPU interval.
	r.TotalTicks += 100
	r.IdleTicks += 10
	now = now.Add(resourceMaxAge)
	m.sample(now)
	assertMonitor(t, m.snapshot(now), "warming_up", now.Unix())
}

func TestResourceMonitorInvalidCounters(t *testing.T) {
	for _, name := range []string{"total_reset", "idle_reset", "stalled", "impossible_idle_delta", "zero_memory", "memory_overflow", "zero_disk", "disk_overflow", "invalid_swap"} {
		t.Run(name, func(t *testing.T) {
			now := time.Unix(1700000000, 0)
			r := resourceFixture(1000, 100, 9000, 9000)
			m := newResourceMonitor(func() (resourceReading, error) { return r, nil })
			m.sample(now)
			switch name {
			case "total_reset":
				r.TotalTicks = 900
			case "idle_reset":
				r.TotalTicks += 100
				r.IdleTicks = 90
			case "stalled":
			case "impossible_idle_delta":
				r.TotalTicks += 100
				r.IdleTicks += 200
			case "zero_memory":
				r.Memory.Total = 0
			case "memory_overflow":
				r.Memory.Used = r.Memory.Total + 1
			case "zero_disk":
				r.Disk.Total = 0
			case "disk_overflow":
				r.Disk.Free = r.Disk.Total + 1
			case "invalid_swap":
				r.Swap.Used = r.Swap.Total + 1
			}
			m.sample(now.Add(resourceSampleInterval))
			assertMonitor(t, m.snapshot(now.Add(resourceSampleInterval)), "error", now.Unix())
		})
	}
	// A valid counter reset becomes the next baseline, not a permanent error.
	now := time.Unix(1700000000, 0)
	r := resourceFixture(1000, 100, 9000, 9000)
	m := newResourceMonitor(func() (resourceReading, error) { return r, nil })
	m.sample(now)
	r.TotalTicks = 900
	m.sample(now.Add(5 * time.Second))
	r.TotalTicks += 100
	r.IdleTicks += 10
	m.sample(now.Add(10 * time.Second))
	assertMonitor(t, m.snapshot(now.Add(10*time.Second)), "ok", now.Add(10*time.Second).Unix(), "cpu", "memory", "disk")
}

func TestResourceMonitorLifecycleWithoutRequests(t *testing.T) {
	const interval = 5 * time.Millisecond
	var reads atomic.Uint64
	m := newResourceMonitor(func() (resourceReading, error) {
		n := reads.Add(1)
		return resourceFixture(1000+100*n, 100+15*n, 8500, 8500), nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go m.run(ctx, interval)
	deadline := time.After(2 * time.Second)
	var first time.Time
	for {
		s := m.snapshot(time.Now())
		if first.IsZero() && !s.sampledAt.IsZero() {
			first = s.sampledAt
		}
		if reads.Load() >= 3 && s.Monitor.State == "ok" && s.sampledAt.After(first) {
			break
		}
		select {
		case <-deadline:
			t.Fatal("sampler did not advance without HTTP requests")
		case <-time.After(time.Millisecond):
		}
	}
	cancel()
	select {
	case <-m.done:
	case <-time.After(time.Second):
		t.Fatal("sampler did not stop")
	}
	assertMonitor(t, m.snapshot(time.Now()), "error", m.snapshot(time.Now()).Monitor.SampledAt)
	stoppedAt := reads.Load()
	time.Sleep(3 * interval)
	if reads.Load() != stoppedAt {
		t.Fatal("sampling continued after shutdown")
	}
	ctx, cancel = context.WithCancel(context.Background())
	cancel()
	unstarted := newResourceMonitor(func() (resourceReading, error) {
		t.Fatal("cancelled monitor sampled")
		return resourceReading{}, nil
	})
	unstarted.run(ctx, interval)
	assertMonitor(t, unstarted.snapshot(time.Now()), "error", 0)
}

func TestSystemResourceMonitorContract(t *testing.T) {
	for _, state := range []string{"unstarted", "warming_up", "ok", "error", "expired"} {
		t.Run(state, func(t *testing.T) {
			var reads int
			r := resourceFixture(1000, 100, 8500, 8500)
			readErr := error(nil)
			m := newResourceMonitor(func() (resourceReading, error) { reads++; return r, readErr })
			now := time.Now()
			if state == "expired" {
				now = now.Add(-resourceMaxAge)
			}
			if state != "unstarted" {
				m.sample(now.Add(-resourceSampleInterval))
			}
			if state == "ok" || state == "error" || state == "expired" {
				r.TotalTicks += 100
				r.IdleTicks += 15
				m.sample(now)
			}
			if state == "error" {
				readErr = errors.New("failed")
				m.sample(now)
			}
			a := testApp()
			a.monitor = m
			before := reads
			for i := 0; i < 2; i++ {
				w := request(a, "GET", "/api/v1/system", "", testToken)
				if w.Code != 200 {
					t.Fatal(w.Code, w.Body.String())
				}
				var body map[string]json.RawMessage
				if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
					t.Fatal(err)
				}
				for _, key := range []string{"version", "hostname", "os", "arch", "uptime_seconds", "cpu_percent", "cpu_cores", "load", "memory", "swap", "disk", "network", "timestamp", "capabilities", "resource_monitor"} {
					if _, ok := body[key]; !ok {
						t.Fatal("missing legacy/API field", key)
					}
				}
				var report resourceMonitorStatus
				if err := json.Unmarshal(body["resource_monitor"], &report); err != nil {
					t.Fatal(err)
				}
				want := state
				if state == "unstarted" {
					want = "warming_up"
				}
				if state == "expired" {
					want = "error"
				}
				if report.State != want || report.ThresholdPercent != 85 || report.Alerts == nil {
					t.Fatal("bad JSON contract", string(body["resource_monitor"]))
				}
				if state == "ok" {
					if len(report.Alerts) != 3 {
						t.Fatal(report)
					}
					var cpu float64
					var memory memoryUsage
					var disk rootDiskUsage
					if err := json.Unmarshal(body["cpu_percent"], &cpu); err != nil {
						t.Fatal(err)
					}
					if err := json.Unmarshal(body["memory"], &memory); err != nil {
						t.Fatal(err)
					}
					if err := json.Unmarshal(body["disk"], &disk); err != nil {
						t.Fatal(err)
					}
					if cpu != 85 || memory != r.Memory || disk != r.Disk {
						t.Fatal("legacy values differ from monitored reading")
					}
				} else if len(report.Alerts) != 0 {
					t.Fatal("unready monitor emitted alerts", report)
				}
				if state == "unstarted" && report.SampledAt != 0 {
					t.Fatal(report)
				}
				if state != "unstarted" && report.SampledAt == 0 {
					t.Fatal(report)
				}
			}
			if reads != before {
				t.Fatal("HTTP request triggered resource sampling")
			}
		})
	}
}

func TestSystemResourceMonitorBlockedSampler(t *testing.T) {
	r := resourceFixture(1000, 100, 8500, 8500)
	m := newResourceMonitor(func() (resourceReading, error) { return r, nil })
	now := time.Now()
	m.sample(now.Add(-resourceSampleInterval))
	r.TotalTicks += 100
	r.IdleTicks += 15
	m.sample(now)
	entered, release := make(chan struct{}), make(chan struct{})
	m.read = func() (resourceReading, error) {
		close(entered)
		<-release
		return r, nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	go m.run(ctx, resourceSampleInterval)
	defer func() { cancel(); close(release); <-m.done }()
	<-entered
	a := testApp()
	a.monitor = m
	response := make(chan int, 1)
	go func() { response <- request(a, "GET", "/api/v1/system", "", testToken).Code }()
	select {
	case code := <-response:
		if code != 200 {
			t.Fatal(code)
		}
	case <-time.After(time.Second):
		t.Fatal("system endpoint waited for blocked resource read")
	}
}

func TestResourceMonitorConcurrentSnapshots(t *testing.T) {
	var n uint64
	m := newResourceMonitor(func() (resourceReading, error) {
		n++
		return resourceFixture(1000+n*100, 100+n*15, 8500, 8500), nil
	})
	base := time.Now().Add(-2 * time.Second)
	m.sample(base)
	start := make(chan struct{})
	var wg sync.WaitGroup
	errCh := make(chan error, 10)
	wg.Add(1)
	go func() {
		defer wg.Done()
		<-start
		for i := 1; i <= 1000; i++ {
			m.sample(base.Add(time.Duration(i) * time.Millisecond))
		}
	}()
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for j := 0; j < 500; j++ {
				s := m.snapshot(time.Now())
				if s.Monitor.State == "ok" {
					if s.CPUPercent != 85 || len(s.Monitor.Alerts) != 3 || s.Monitor.Alerts[0].Percent != 85 || s.Memory.Used != 8500 || s.Disk.Used != 8500 {
						errCh <- fmt.Errorf("torn/shared snapshot: %+v", s)
						return
					}
					// Mutating a response must not race with any other reader/writer.
					s.Monitor.Alerts[0].Percent = -1
				}
			}
		}()
	}
	a := testApp()
	a.monitor = m
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for j := 0; j < 50; j++ {
				w := request(a, "GET", "/api/v1/system", "", testToken)
				var body struct {
					ResourceMonitor resourceMonitorStatus `json:"resource_monitor"`
				}
				if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &body) != nil || body.ResourceMonitor.Alerts == nil {
					errCh <- fmt.Errorf("concurrent API response: %d %s", w.Code, w.Body.String())
					return
				}
			}
		}()
	}
	close(start)
	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Error(err)
	}
}

func TestResourceReadingCPUTicks(t *testing.T) {
	total, idle, err := parseCPUTicks("cpu 10 20 30 40 5 6 7 8 100 200\ncpu0 1 2 3 4\n")
	if err != nil || total != 126 || idle != 45 {
		t.Fatal(total, idle, err)
	}
	for _, raw := range []string{"", "cpu0 1 2 3 4", "cpu 1 2 3", "cpu 1 2 bad 4", "cpu -1 2 3 4", "cpu 0 0 0 0", "cpu 18446744073709551615 1 1 1"} {
		if _, _, err := parseCPUTicks(raw); err == nil {
			t.Fatal("accepted malformed CPU reading", raw)
		}
	}
}

func TestResourceReadingMemory(t *testing.T) {
	const swap = "SwapTotal: 50 kB\nSwapFree: 20 kB\n"
	for _, tc := range []struct {
		raw       string
		available uint64
	}{
		{"MemTotal: 100 kB\nMemAvailable: 15 kB\n", 15},
		{"MemTotal: 100 kB\nMemFree: 5 kB\nBuffers: 5 kB\nCached: 5 kB\n", 15},
		{"MemTotal: 100 kB\nMemAvailable: 200 kB\n", 100},
	} {
		memory, swapUsage, err := parseMemoryInfo(tc.raw + swap)
		if err != nil || memory.Total != 100*1024 || memory.Available != tc.available*1024 || memory.Used != (100-tc.available)*1024 || swapUsage.Total != 50*1024 || swapUsage.Used != 30*1024 {
			t.Fatal(memory, swapUsage, err)
		}
	}
	for _, raw := range []string{
		"", "MemTotal: 0 kB\n", "MemTotal: bad kB\n", "MemTotal: 100 MB\n",
		"MemTotal: 18446744073709551615 kB\n", "MemTotal: 100 kB\n",
		"MemTotal: 100 kB\nMemFree: 10 kB\n", "MemTotal: 100 kB\nMemAvailable: -1 kB\n",
	} {
		if _, _, err := parseMemoryInfo(raw + swap); err == nil {
			t.Fatal("accepted malformed memory reading", raw)
		}
	}
	if _, _, err := parseMemoryInfo("MemTotal: 100 kB\nMemAvailable: 10 kB\nSwapTotal: 0 kB\nSwapFree: 1 kB\n"); err == nil {
		t.Fatal("accepted swap underflow")
	}
}

func TestResourceReadingLive(t *testing.T) {
	r, err := readResources()
	if err != nil {
		t.Fatal(err)
	}
	if err := validateResourceReading(r); err != nil {
		t.Fatal(err, r)
	}
}
