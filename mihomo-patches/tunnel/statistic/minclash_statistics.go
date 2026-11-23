package statistic

import (
	"math"
	"sort"
	"sync"
	"sync/atomic"

	C "github.com/metacubex/mihomo/constant"
)

const maxAppTrafficUIDs = 512

type appTrafficStore struct {
	mu      sync.Mutex
	byUID   map[int64]*appTrafficCounter
	unknown appTrafficCounter
}

type appTrafficCounter struct {
	upload   atomic.Int64
	download atomic.Int64
}

type AppTrafficTotal struct {
	UID           int64 `json:"uid"`
	UploadTotal   int64 `json:"uploadTotal"`
	DownloadTotal int64 `json:"downloadTotal"`
}

func (m *Manager) ActiveCount() int {
	return m.connections.Size()
}

func (m *Manager) tunAppTraffic(metadata *C.Metadata, pushToManager bool) *appTrafficCounter {
	if !pushToManager || metadata.Type != C.TUN {
		return nil
	}
	uid := int64(metadata.MinClashTrafficUID)
	if uid == 0 || uid > math.MaxInt32 {
		return &m.appTraffic.unknown
	}
	store := &m.appTraffic
	store.mu.Lock()
	defer store.mu.Unlock()
	if counter := store.byUID[uid]; counter != nil {
		return counter
	}
	if len(store.byUID) >= maxAppTrafficUIDs {
		return &store.unknown
	}
	if store.byUID == nil {
		store.byUID = make(map[int64]*appTrafficCounter)
	}
	counter := &appTrafficCounter{}
	store.byUID[uid] = counter
	return counter
}

func (c *appTrafficCounter) addUpload(size int64) {
	if c != nil && size > 0 {
		c.upload.Add(size)
	}
}

func (c *appTrafficCounter) addDownload(size int64) {
	if c != nil && size > 0 {
		c.download.Add(size)
	}
}

func (m *Manager) AppTrafficTotals() []AppTrafficTotal {
	store := &m.appTraffic
	store.mu.Lock()
	totals := make([]AppTrafficTotal, 0, len(store.byUID)+1)
	appendTotal := func(uid int64, counter *appTrafficCounter) {
		upload, download := counter.upload.Load(), counter.download.Load()
		if upload != 0 || download != 0 {
			totals = append(totals, AppTrafficTotal{UID: uid, UploadTotal: upload, DownloadTotal: download})
		}
	}
	appendTotal(-1, &store.unknown)
	for uid, counter := range store.byUID {
		appendTotal(uid, counter)
	}
	store.mu.Unlock()
	sort.Slice(totals, func(i, j int) bool { return totals[i].UID < totals[j].UID })
	return totals
}

func (m *Manager) resetAppTraffic() {
	store := &m.appTraffic
	store.mu.Lock()
	defer store.mu.Unlock()
	store.unknown.upload.Store(0)
	store.unknown.download.Store(0)
	for _, counter := range store.byUID {
		counter.upload.Store(0)
		counter.download.Store(0)
	}
}
