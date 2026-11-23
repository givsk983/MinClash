package sing_tun

import (
	"net/netip"
	"testing"

	"github.com/metacubex/sing-tun"
	"github.com/metacubex/sing/common/control"
)

type minclashTestInterfaceFinder struct{ control.InterfaceFinder }

func (minclashTestInterfaceFinder) ByAddr(address netip.Addr) (*control.Interface, error) {
	if address == netip.MustParseAddr("192.168.1.1") || address == netip.MustParseAddr("fd00:1::3") {
		return &control.Interface{Name: "local"}, nil
	}
	return nil, nil
}

type minclashTestDefaultMonitor struct {
	tun.DefaultInterfaceMonitor
	blocked bool
}

func (minclashTestDefaultMonitor) DefaultInterface() *control.Interface {
	return &control.Interface{Name: "generic"}
}

func (m minclashTestDefaultMonitor) DefaultInterfaceForDestination(destination netip.Addr) (*control.Interface, bool) {
	if destination == netip.MustParseAddr("fd00:2::3") {
		return &control.Interface{Name: "routed"}, false
	}
	if destination.Is6() {
		return &control.Interface{Name: "rmnet0"}, m.blocked
	}
	return &control.Interface{Name: "wlan0"}, false
}

func TestMinClashAndroidDestinationInterface(t *testing.T) {
	saved := DefaultInterfaceFinder
	DefaultInterfaceFinder = minclashTestInterfaceFinder{}
	t.Cleanup(func() { DefaultInterfaceFinder = saved })
	finder := cDialerInterfaceFinder{tunName: "minclash0", defaultInterfaceMonitor: minclashTestDefaultMonitor{}}
	tests := map[string]string{
		"198.51.100.3":        "wlan0",
		"::ffff:198.51.100.3": "wlan0",
		"2001:db8::3":         "rmnet0",
		"192.168.1.1":         "local",
		"fd00:1::3":           "local",
		"fd00:2::3":           "routed",
		"::":                  "generic",
	}
	for address, want := range tests {
		if got := finder.FindInterfaceName(netip.MustParseAddr(address)); got != want {
			t.Errorf("egress for %s = %s, want %s", address, got, want)
		}
	}
	finder.defaultInterfaceMonitor = minclashTestDefaultMonitor{blocked: true}
	if got := finder.FindInterfaceName(netip.MustParseAddr("2001:db8::3")); got != "<invalid>" {
		t.Fatalf("terminal policy fell through to interface %s", got)
	}
}
