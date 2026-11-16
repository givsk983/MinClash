package tunnel

import (
	"context"
	"errors"
	"github.com/metacubex/mihomo/component/protection"
	C "github.com/metacubex/mihomo/constant"
	"net/netip"
	"testing"
)

type minclashTestProxy struct{ C.Proxy }

func (p *minclashTestProxy) Name() string                  { return "test" }
func (p *minclashTestProxy) IsL3Protocol(*C.Metadata) bool { return false }
func (p *minclashTestProxy) SupportUDP() bool              { return true }
func (p *minclashTestProxy) DialContext(_ context.Context, m *C.Metadata) (C.Conn, error) {
	if m.MinClashProtected {
		return nil, errors.New("blocked")
	}
	panic("lost protection flag")
}
func (p *minclashTestProxy) ListenPacketContext(_ context.Context, m *C.Metadata) (C.PacketConn, error) {
	if m.MinClashProtected {
		return nil, errors.New("blocked")
	}
	panic("lost protection flag")
}
func TestMinClashProtectedPrecedesModes(t *testing.T) {
	oldPolicy, oldFind, oldProxies, oldMode := protection.Runtime, minclashFindProcess, proxies, mode
	defer func() {
		protection.Runtime = oldPolicy
		minclashFindProcess = oldFind
		proxies = oldProxies
		mode = oldMode
	}()
	protection.Runtime = protection.Policy{Version: 1, Apps: []protection.App{{Package: "org.example.app", UID: 10123, Target: "Safe"}}}
	direct := &minclashTestProxy{}
	proxies = map[string]C.Proxy{"Safe": direct, "DIRECT": direct, "GLOBAL": direct}
	minclashFindProcess = func(string, netip.Addr, int) (uint32, string, error) { return 10123, "org.example.app", nil }
	for _, selectedMode := range []TunnelMode{Direct, Global, Rule} {
		mode = selectedMode
		m := &C.Metadata{Type: C.TUN, NetWork: C.TCP}
		p, _, err := resolveMetadata(m)
		if err != nil || p != direct || !m.MinClashProtected {
			t.Fatal("protection lost to mode", selectedMode, err)
		}
		if _, err = p.DialContext(context.Background(), m); err == nil {
			t.Fatal("nested DIRECT allowed")
		}
	}
	minclashFindProcess = func(string, netip.Addr, int) (uint32, string, error) { return 0, "", errors.New("unknown") }
	if _, _, err := resolveMetadata(&C.Metadata{Type: C.TUN}); err == nil {
		t.Fatal("unknown UID fell through")
	}
	protection.Runtime.Apps = nil
	if _, handled, err := minclashResolve(&C.Metadata{Type: C.TUN}); handled || err != nil {
		t.Fatal("off changed routing")
	}
}
func TestMinClashStrictDNSMissingAndDirect(t *testing.T) {
	old, oldProxies := protection.Runtime, proxies
	defer func() { protection.Runtime = old; proxies = oldProxies }()
	protection.Runtime = protection.Policy{Version: 1, StrictDNS: true, DNSProxy: "Safe"}
	proxies = map[string]C.Proxy{}
	d := NewDNSDialer(nil, nil, protection.DNSProxy)
	if _, err := d.DialContext(context.Background(), "tcp", "1.1.1.1:443"); err == nil {
		t.Fatal("missing proxy fell back to interface")
	}
	proxies["Safe"] = &minclashTestProxy{}
	if _, err := d.DialContext(context.Background(), "tcp", "1.1.1.1:443"); err == nil {
		t.Fatal("DNS allowed direct")
	}
	if _, err := d.ListenPacket(context.Background(), "udp", "1.1.1.1:53"); err == nil {
		t.Fatal("UDP DNS allowed direct")
	}
}
