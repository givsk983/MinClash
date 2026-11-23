package route

import (
	"encoding/json"
	"net"
	"reflect"
	"testing"

	"github.com/metacubex/http/httptest"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

func TestMinClashStatistics(t *testing.T) {
	read := func(path string) map[string]json.RawMessage {
		t.Helper()
		w := httptest.NewRecorder()
		minclashStatistics(w, httptest.NewRequest("GET", path, nil))
		var data map[string]json.RawMessage
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &data) != nil {
			t.Fatal("expected a JSON statistics response")
		}
		return data
	}
	summary := read("/minclash/statistics")
	if len(summary) != 3 || len(summary["session"]) == 0 || summary["uploadTotal"] == nil || summary["downloadTotal"] == nil {
		t.Fatalf("unexpected summary fields: %v", summary)
	}
	detail := read("/minclash/statistics?details=1")
	var count int
	if string(detail["session"]) != string(summary["session"]) || json.Unmarshal(detail["connections"], &count) != nil || count < 0 {
		t.Fatal("expected a stable session and numeric connection count")
	}
	apps := read("/minclash/statistics?apps=1")
	if string(apps["session"]) != string(summary["session"]) || string(apps["apps"]) != "[]" {
		t.Fatal("requested empty app traffic must be represented as an array")
	}
}

// These conns exercise tracker I/O and close without involving the local network.
type minclashTrafficTCP struct{ C.Conn }

func (minclashTrafficTCP) Chains() C.Chain             { return nil }
func (minclashTrafficTCP) ProviderChains() C.Chain     { return nil }
func (minclashTrafficTCP) RemoteDestination() string   { return "" }
func (minclashTrafficTCP) Read(b []byte) (int, error)  { return len(b), nil }
func (minclashTrafficTCP) Write(b []byte) (int, error) { return len(b), nil }
func (minclashTrafficTCP) Close() error                { return nil }

type minclashTrafficUDP struct{ C.PacketConn }

func (minclashTrafficUDP) Chains() C.Chain           { return nil }
func (minclashTrafficUDP) ProviderChains() C.Chain   { return nil }
func (minclashTrafficUDP) RemoteDestination() string { return "" }
func (minclashTrafficUDP) Close() error              { return nil }
func (minclashTrafficUDP) WriteTo(b []byte, _ net.Addr) (int, error) {
	return len(b), nil
}
func (minclashTrafficUDP) WaitReadFrom() ([]byte, func(), net.Addr, error) {
	return make([]byte, 3), nil, nil, nil
}

func TestMinClashAppTrafficIncludesClosedConnections(t *testing.T) {
	manager := &statistic.Manager{}
	previous := statistic.DefaultManager
	statistic.DefaultManager = manager
	t.Cleanup(func() { statistic.DefaultManager = previous })

	metadata := &C.Metadata{Type: C.TUN, MinClashTrafficUID: 10002}
	tcp := statistic.NewTCPTracker(minclashTrafficTCP{}, manager, metadata, nil, 10, 20, true)
	_, _ = tcp.Write(make([]byte, 2))
	_, _ = tcp.Read(make([]byte, 3))
	_, countUpload := tcp.UnwrapWriter()
	countUpload[0](4)
	_, countDownload := tcp.UnwrapReader()
	countDownload[0](5)
	metadata.MinClashTrafficUID = 9999 // later rewrites cannot change the bucket
	_, _ = tcp.Write(make([]byte, 1))
	_ = tcp.Close()

	for _, uid := range []uint32{10002, 0} {
		udp := statistic.NewUDPTracker(minclashTrafficUDP{}, manager,
			&C.Metadata{Type: C.TUN, Uid: 10222, MinClashTrafficUID: uid}, nil, 0, 0, true)
		_, _ = udp.WriteTo(make([]byte, 4), nil)
		_, _, _, _ = udp.WaitReadFrom()
		_ = udp.Close()
	}
	inner := statistic.NewTCPTracker(minclashTrafficTCP{}, manager,
		&C.Metadata{Type: C.INNER, MinClashTrafficUID: 10004}, nil, 1, 1, true)
	_ = inner.Close()
	internal := statistic.NewTCPTracker(minclashTrafficTCP{}, manager,
		&C.Metadata{Type: C.TUN, MinClashTrafficUID: 10005}, nil, 1, 1, false)
	_ = internal.Close()

	w := httptest.NewRecorder()
	minclashStatistics(w, httptest.NewRequest("GET", "/minclash/statistics?details=1&apps=1", nil))
	var data struct {
		Apps        []statistic.AppTrafficTotal `json:"apps"`
		Connections int                         `json:"connections"`
	}
	want := []statistic.AppTrafficTotal{{UID: -1, UploadTotal: 4, DownloadTotal: 3},
		{UID: 10002, UploadTotal: 21, DownloadTotal: 31}}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &data) != nil || len(data.Apps) == 0 ||
		!reflect.DeepEqual(data.Apps, want) || data.Connections != 0 {
		t.Fatalf("unexpected closed-connection totals: %s", w.Body.String())
	}
}

func TestMinClashAppTrafficOverflowDoesNotEvict(t *testing.T) {
	manager := &statistic.Manager{}
	for uid := uint32(10000); uid <= 10512; uid++ {
		tracker := statistic.NewTCPTracker(minclashTrafficTCP{}, manager,
			&C.Metadata{Type: C.TUN, MinClashTrafficUID: uid}, nil, 1, 0, true)
		_ = tracker.Close()
	}
	tracker := statistic.NewTCPTracker(minclashTrafficTCP{}, manager,
		&C.Metadata{Type: C.TUN, MinClashTrafficUID: 10000}, nil, 5, 0, true)
	_ = tracker.Close()
	totals := manager.AppTrafficTotals()
	if len(totals) != 513 || totals[0].UID != -1 || totals[0].UploadTotal != 1 ||
		totals[1].UID != 10000 || totals[1].UploadTotal != 6 || totals[512].UID != 10511 {
		t.Fatal("overflow did not preserve every admitted UID and its bytes")
	}
}
