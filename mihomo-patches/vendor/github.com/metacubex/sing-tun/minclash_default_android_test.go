package tun

import (
	"net"
	"net/netip"
	"testing"

	"github.com/metacubex/sing/common/control"
	"github.com/metacubex/sing/common/logger"
	"github.com/sagernet/netlink"
	"golang.org/x/sys/unix"
)

func TestMinClashAndroidClearsLostUplink(t *testing.T) {
	old := control.Interface{Index: 7, Name: "wlan0"}
	current := control.Interface{Index: 8, Name: "rmnet0"}
	finder := control.NewDefaultInterfaceFinder()
	finder.UpdateInterfaces([]control.Interface{old, current})
	m := &defaultInterfaceMonitor{logger: logger.NOP(), interfaceFinder: finder, androidTunTable: 20882}
	var updates []*control.Interface
	m.RegisterCallback(func(uplink *control.Interface, _ int) { updates = append(updates, uplink) })
	oldRoute := netlink.Route{Family: unix.AF_INET, Table: 1007, LinkIndex: old.Index, Type: unix.RTN_UNICAST}
	newRoute := netlink.Route{Family: unix.AF_INET, Table: 1008, LinkIndex: current.Index, Type: unix.RTN_UNICAST}
	oldRule := netlink.Rule{Family: unix.AF_INET, Table: oldRoute.Table, IifName: "lo", Mask: androidNetIDMask}
	newRule := netlink.Rule{Family: unix.AF_INET, Table: newRoute.Table, IifName: "lo", Mask: androidNetIDMask}
	routes := []netlink.Route{oldRoute, newRoute}
	if err := m.updateMinClashAndroidUplink([]netlink.Rule{oldRule}, routes); err != nil {
		t.Fatal(err)
	}
	if err := m.updateMinClashAndroidUplink(nil, routes); err != ErrNoRoute {
		t.Fatalf("ambiguous default during handoff: %v", err)
	}
	m.noRoute.Store(true)
	if err := m.updateMinClashAndroidUplink(nil, routes); err != ErrNoRoute {
		t.Fatalf("still-ambiguous default during handoff: %v", err)
	}
	if m.DefaultInterface() != nil || m.androidUplink != "" {
		t.Fatal("lost network retained its interface or identity")
	}
	for _, destination := range []string{"198.51.100.3", "2001:db8::3"} {
		if iface, blocked := m.DefaultInterfaceForDestination(netip.MustParseAddr(destination)); iface != nil || !blocked {
			t.Fatalf("lost network can still resolve an interface for %s", destination)
		}
	}
	lan := &net.IPNet{IP: net.ParseIP("fd00:7::"), Mask: net.CIDRMask(64, 128)}
	localRoute := netlink.Route{Family: unix.AF_INET6, Table: newRoute.Table, LinkIndex: current.Index, Type: unix.RTN_UNICAST, Dst: lan}
	routes = append(routes, localRoute)
	if err := m.updateMinClashAndroidUplink([]netlink.Rule{newRule}, routes); err != nil {
		t.Fatal(err)
	}
	if iface, blocked := m.DefaultInterfaceForDestination(netip.MustParseAddr("198.51.100.3")); iface == nil || !iface.Equals(current) || blocked {
		t.Fatal("IPv4 did not recover on the new network")
	}
	if iface, blocked := m.DefaultInterfaceForDestination(netip.MustParseAddr("2001:db8::3")); iface != nil || !blocked {
		t.Fatal("IPv6 must not borrow the recovered IPv4 interface")
	}
	if iface, blocked := m.DefaultInterfaceForDestination(netip.MustParseAddr("fd00:7::3")); iface == nil || !iface.Equals(current) || blocked {
		t.Fatal("specifically routed IPv6 destination lost the recovered interface")
	}
	if len(updates) != 3 || updates[0] == nil || !updates[0].Equals(old) || updates[1] != nil ||
		updates[2] == nil || !updates[2].Equals(current) {
		t.Fatalf("unexpected network change notifications: %v", updates)
	}
	unreachable := netlink.Route{Family: unix.AF_INET6, Table: 1010, Type: unix.RTN_UNREACHABLE}
	denyRule := netlink.Rule{Family: unix.AF_INET6, Table: unreachable.Table, IifName: "lo", Mask: androidNetIDMask}
	if err := m.updateMinClashAndroidUplink([]netlink.Rule{newRule, denyRule}, append(routes, unreachable)); err != nil {
		t.Fatal(err)
	}
	if iface, blocked := m.DefaultInterfaceForDestination(netip.MustParseAddr("fd00:7::3")); iface != nil || !blocked {
		t.Fatal("specifically routed fallback ignored a terminal default policy")
	}
}
