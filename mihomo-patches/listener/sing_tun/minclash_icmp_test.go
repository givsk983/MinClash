package sing_tun

import (
	"errors"
	"net/netip"
	"testing"

	"github.com/metacubex/mihomo/component/protection"
	tun "github.com/metacubex/sing-tun"
	M "github.com/metacubex/sing/common/metadata"
	N "github.com/metacubex/sing/common/network"
)

func TestMinClashProtectedSessionDropsExternalICMP(t *testing.T) {
	previous := protection.Runtime
	t.Cleanup(func() { protection.Runtime = previous })
	protection.Runtime = protection.Policy{Apps: []protection.App{{UID: 10123, Target: "Safe"}}}
	handler := &ListenerHandler{
		Inet4Address: []netip.Prefix{netip.MustParsePrefix("198.18.0.1/30")},
	}
	for _, target := range []string{"192.0.2.1", "2001:db8::1"} {
		action, err := handler.PrepareConnection(N.NetworkICMP, M.Socksaddr{}, M.SocksaddrFrom(netip.MustParseAddr(target), 0), nil, 0)
		if action != nil || !errors.Is(err, tun.ErrDrop) {
			t.Fatalf("protected ICMP to %s was not dropped: %v", target, err)
		}
	}
	action, err := handler.PrepareConnection(N.NetworkICMP, M.Socksaddr{}, M.SocksaddrFrom(netip.MustParseAddr("198.18.0.2"), 0), nil, 0)
	if action != nil || err != nil {
		t.Fatalf("TUN-local ICMP should stay local: %v", err)
	}
}
