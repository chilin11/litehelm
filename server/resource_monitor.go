//go:build linux

package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	resourceSampleInterval = 5 * time.Second
	resourceMaxAge         = 3 * resourceSampleInterval
	resourceThreshold      = 85.0
)

type resourceAlert struct {
	Resource string  `json:"resource"`
	Percent  float64 `json:"percent"`
}

type resourceMonitorStatus struct {
	State            string          `json:"state"`
	SampledAt        int64           `json:"sampled_at"`
	ThresholdPercent float64         `json:"threshold_percent"`
	Alerts           []resourceAlert `json:"alerts"`
}

type memoryUsage struct {
	Total     uint64 `json:"total"`
	Used      uint64 `json:"used"`
	Available uint64 `json:"available"`
}

type swapUsage struct {
	Total uint64 `json:"total"`
	Used  uint64 `json:"used"`
}

type rootDiskUsage struct {
	Total uint64 `json:"total"`
	Used  uint64 `json:"used"`
	Free  uint64 `json:"free"`
}

type resourceReading struct {
	TotalTicks, IdleTicks uint64
	Memory                memoryUsage
	Swap                  swapUsage
	Disk                  rootDiskUsage
}

type resourceSnapshot struct {
	CPUPercent float64
	Memory     memoryUsage
	Swap       swapUsage
	Disk       rootDiskUsage
	Monitor    resourceMonitorStatus
	sampledAt  time.Time // Retains the monotonic clock for freshness checks.
}

type resourceMonitor struct {
	mu     sync.RWMutex
	latest resourceSnapshot
	read   func() (resourceReading, error)
	done   chan struct{}
	// Only the single sampling goroutine accesses the CPU baseline.
	previous   *resourceReading
	previousAt time.Time
}

func newResourceMonitor(read func() (resourceReading, error)) *resourceMonitor {
	return &resourceMonitor{
		read: read,
		done: make(chan struct{}),
		latest: resourceSnapshot{Monitor: resourceMonitorStatus{
			State: "warming_up", ThresholdPercent: resourceThreshold, Alerts: []resourceAlert{},
		}},
	}
}

func startResourceMonitor(ctx context.Context) *resourceMonitor {
	m := newResourceMonitor(readResources)
	go m.run(ctx, resourceSampleInterval)
	return m
}

func (m *resourceMonitor) run(ctx context.Context, interval time.Duration) {
	defer close(m.done)
	defer m.markError()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		if ctx.Err() != nil {
			return
		}
		// Read immediately on startup, then independently of HTTP requests.
		m.sample(time.Now())
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (m *resourceMonitor) markError() {
	m.mu.Lock()
	m.latest.Monitor.State = "error"
	m.latest.Monitor.Alerts = []resourceAlert{}
	m.mu.Unlock()
}

// sample has one writer; readers only observe complete, immutable snapshots.
func (m *resourceMonitor) sample(now time.Time) {
	r, err := m.read()
	if err == nil {
		err = validateResourceReading(r)
	}
	if err != nil {
		// A failed reading breaks continuity. Recovery needs a new baseline and
		// one more successful interval, not a CPU average over the outage.
		m.previous = nil
		m.markError()
		return
	}
	previous, previousAt := m.previous, m.previousAt
	m.previous, m.previousAt = &r, now
	s := resourceSnapshot{
		Memory: r.Memory, Swap: r.Swap, Disk: r.Disk, sampledAt: now,
		Monitor: resourceMonitorStatus{
			State: "warming_up", SampledAt: now.Unix(),
			ThresholdPercent: resourceThreshold, Alerts: []resourceAlert{},
		},
	}
	if previous != nil && now.Sub(previousAt) < resourceMaxAge {
		if !now.After(previousAt) || r.TotalTicks <= previous.TotalTicks || r.IdleTicks < previous.IdleTicks || r.IdleTicks-previous.IdleTicks > r.TotalTicks-previous.TotalTicks {
			// Counter resets/invalid deltas must never look like a healthy 0% CPU.
			// The current counters form the baseline for the following interval.
			m.markError()
			return
		}
		total, idle := r.TotalTicks-previous.TotalTicks, r.IdleTicks-previous.IdleTicks
		s.CPUPercent = 100 * float64(total-idle) / float64(total)
		s.Monitor.State = "ok"
		for _, v := range []resourceAlert{
			{Resource: "cpu", Percent: s.CPUPercent},
			{Resource: "memory", Percent: 100 * float64(r.Memory.Used) / float64(r.Memory.Total)},
			{Resource: "disk", Percent: 100 * float64(r.Disk.Used) / float64(r.Disk.Total)},
		} {
			if v.Percent >= resourceThreshold {
				s.Monitor.Alerts = append(s.Monitor.Alerts, v)
			}
		}
	}
	m.mu.Lock()
	m.latest = s
	m.mu.Unlock()
}

// snapshot never reads procfs/statfs or waits for the sampler. Even when a
// kernel read stalls, a formerly healthy snapshot expires instead of reporting OK.
func (m *resourceMonitor) snapshot(now time.Time) resourceSnapshot {
	if m == nil {
		return resourceSnapshot{Monitor: resourceMonitorStatus{
			State: "warming_up", ThresholdPercent: resourceThreshold, Alerts: []resourceAlert{},
		}}
	}
	m.mu.RLock()
	s := m.latest
	// A caller must not be able to mutate alerts shared with another response.
	s.Monitor.Alerts = append([]resourceAlert{}, s.Monitor.Alerts...)
	m.mu.RUnlock()
	if !s.sampledAt.IsZero() && (now.Before(s.sampledAt) || now.Sub(s.sampledAt) >= resourceMaxAge) {
		s.Monitor.State = "error"
		s.Monitor.Alerts = []resourceAlert{}
	}
	return s
}

func validateResourceReading(r resourceReading) error {
	if r.TotalTicks == 0 || r.IdleTicks > r.TotalTicks || r.Memory.Total == 0 || r.Memory.Used > r.Memory.Total || r.Memory.Available > r.Memory.Total || r.Swap.Used > r.Swap.Total || r.Disk.Total == 0 || r.Disk.Used > r.Disk.Total || r.Disk.Free > r.Disk.Total {
		return errors.New("invalid resource counters")
	}
	return nil
}

func readResources() (resourceReading, error) {
	var r resourceReading
	var err error
	if r.TotalTicks, r.IdleTicks, err = cpuTicks(); err != nil {
		return r, err
	}
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return r, err
	}
	if r.Memory, r.Swap, err = parseMemoryInfo(string(b)); err != nil {
		return r, err
	}
	var fs syscall.Statfs_t
	if err = syscall.Statfs("/", &fs); err != nil {
		return r, fmt.Errorf("cannot inspect root filesystem: %w", err)
	}
	if fs.Bsize <= 0 || fs.Blocks == 0 || fs.Bfree > fs.Blocks || fs.Bavail > fs.Bfree || fs.Blocks > ^uint64(0)/uint64(fs.Bsize) {
		return r, errors.New("invalid root filesystem counters")
	}
	r.Disk = rootDiskUsage{
		Total: fs.Blocks * uint64(fs.Bsize),
		Used:  (fs.Blocks - fs.Bfree) * uint64(fs.Bsize),
		Free:  fs.Bavail * uint64(fs.Bsize),
	}
	return r, nil
}

func cpuTicks() (uint64, uint64, error) {
	b, err := os.ReadFile("/proc/stat")
	if err != nil {
		return 0, 0, err
	}
	return parseCPUTicks(string(b))
}

func parseCPUTicks(raw string) (uint64, uint64, error) {
	f := strings.Fields(strings.SplitN(raw, "\n", 2)[0])
	if len(f) < 5 || f[0] != "cpu" {
		return 0, 0, errors.New("invalid /proc/stat")
	}
	var total, idle uint64
	// guest/guest_nice are already included in user/nice; do not double count.
	for i := 1; i < len(f) && i <= 8; i++ {
		n, err := strconv.ParseUint(f[i], 10, 64)
		if err != nil || n > ^uint64(0)-total {
			return 0, 0, errors.New("invalid /proc/stat counter")
		}
		total += n
		if i == 4 || i == 5 {
			idle += n
		}
	}
	if total == 0 {
		return 0, 0, errors.New("empty /proc/stat counters")
	}
	return total, idle, nil
}

func parseMemoryInfo(raw string) (memoryUsage, swapUsage, error) {
	values := map[string]uint64{}
	for _, line := range strings.Split(raw, "\n") {
		f := strings.Fields(line)
		if len(f) == 0 {
			continue
		}
		key := strings.TrimSuffix(f[0], ":")
		switch key {
		case "MemTotal", "MemAvailable", "MemFree", "Buffers", "Cached", "SwapTotal", "SwapFree":
			if len(f) != 3 || f[2] != "kB" {
				return memoryUsage{}, swapUsage{}, errors.New("invalid /proc/meminfo field")
			}
			n, err := strconv.ParseUint(f[1], 10, 64)
			if err != nil || n > ^uint64(0)/1024 {
				return memoryUsage{}, swapUsage{}, errors.New("invalid /proc/meminfo counter")
			}
			values[key] = n * 1024
		}
	}
	total, hasTotal := values["MemTotal"]
	swapTotal, hasSwapTotal := values["SwapTotal"]
	swapFree, hasSwapFree := values["SwapFree"]
	if !hasTotal || total == 0 || !hasSwapTotal || !hasSwapFree || swapFree > swapTotal {
		return memoryUsage{}, swapUsage{}, errors.New("missing or invalid memory totals")
	}
	available, ok := values["MemAvailable"]
	if !ok {
		// Preserve support for older kernels lacking MemAvailable.
		for _, key := range []string{"MemFree", "Buffers", "Cached"} {
			n, exists := values[key]
			if !exists || n > ^uint64(0)-available {
				return memoryUsage{}, swapUsage{}, errors.New("missing or invalid available memory")
			}
			available += n
		}
	}
	if available > total {
		available = total
	}
	return memoryUsage{Total: total, Used: total - available, Available: available}, swapUsage{Total: swapTotal, Used: swapTotal - swapFree}, nil
}
