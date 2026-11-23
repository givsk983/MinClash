package tunnel

import (
	"context"
	"errors"
	"net/netip"
	"testing"

	"github.com/metacubex/mihomo/component/protection"
	C "github.com/metacubex/mihomo/constant"
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
	oldPolicy, oldVerify, oldProxies, oldMode := protection.Runtime, minclashVerifySocketUID, proxies, mode
	defer func() {
		protection.Runtime = oldPolicy
		minclashVerifySocketUID = oldVerify
		proxies = oldProxies
		mode = oldMode
	}()
	protection.Runtime = protection.Policy{Version: 1, Apps: []protection.App{{Package: "org.example.app", UID: 10123, Target: "Safe"}}}
	direct := &minclashTestProxy{}
	proxies = map[string]C.Proxy{"Safe": direct, "DIRECT": direct, "GLOBAL": direct}
	destination := netip.MustParseAddr("2001:db8::123")
	lookups := 0
	minclashVerifySocketUID = func(m *C.Metadata) uint32 {
		lookups++
		if m.DstIP != destination || m.DstPort != 443 {
			t.Fatal("socket owner must be verified against the original destination")
		}
		return 10123
	}
	for _, selectedMode := range []TunnelMode{Direct, Global, Rule} {
		mode = selectedMode
		for _, network := range []C.NetWork{C.TCP, C.UDP} {
			m := &C.Metadata{Type: C.TUN, NetWork: network, DstIP: destination, DstPort: 443}
			if err := minclashIdentify(m); err != nil {
				t.Fatal(err)
			}
			if network == C.TCP {
				minclashIdentifyTCPTraffic(m)
			}
			if m.MinClashTrafficUID != 10123 {
				t.Fatal("the verified ingress UID was not retained for accounting")
			}
			m.DstIP = netip.Addr{} // Fake-IP / sniffing may replace the original target.
			p, _, err := resolveMetadata(m)
			if err != nil || p != direct || !m.MinClashProtected {
				t.Fatal("protection lost to mode", selectedMode, err)
			}
			if _, err = p.DialContext(context.Background(), m); err == nil {
				t.Fatal("nested DIRECT allowed")
			}
		}
	}
	if lookups != 6 {
		t.Fatal("verified origins were resolved again after ingress")
	}
	minclashVerifySocketUID = func(*C.Metadata) uint32 { return 0 }
	previousLogDeadline := minclashNextOriginLog.Swap(0)
	defer minclashNextOriginLog.Store(previousLogDeadline)
	missing := &C.Metadata{Type: C.TUN, Uid: 10123}
	if err := minclashIdentify(missing); !errors.Is(err, errMinClashOriginUnknown) {
		t.Fatal("unverified metadata was accepted at ingress")
	}
	logDeadline := minclashNextOriginLog.Load()
	if logDeadline == 0 {
		t.Fatal("first rejection did not mark the origin diagnostic")
	}
	if err := minclashIdentify(missing); !errors.Is(err, errMinClashOriginUnknown) || minclashNextOriginLog.Load() != logDeadline {
		t.Fatal("repeated rejection did not respect the diagnostic rate limit")
	}
	if _, _, err := resolveMetadata(missing); !errors.Is(err, errMinClashOriginUnknown) {
		t.Fatal("unverified metadata reached ordinary routing")
	}
	protection.Runtime.Apps = nil
	if _, handled, err := minclashResolve(&C.Metadata{Type: C.TUN}); handled || err != nil {
		t.Fatal("off changed routing")
	}
}

func TestMinClashOrdinaryAccountingDoesNotTrustMetadataUID(t *testing.T) {
	oldPolicy, oldVerify := protection.Runtime, minclashVerifySocketUID
	t.Cleanup(func() {
		protection.Runtime = oldPolicy
		minclashVerifySocketUID = oldVerify
	})
	protection.Runtime.Apps = nil
	lookups := 0
	minclashVerifySocketUID = func(*C.Metadata) uint32 {
		lookups++
		return 10123
	}
	tcp := &C.Metadata{Type: C.TUN, NetWork: C.TCP, Uid: 10222}
	minclashIdentifyTCPTraffic(tcp)
	if tcp.MinClashTrafficUID != 10123 || tcp.Uid != 10222 || tcp.MinClashIdentified {
		t.Fatal("accounting did not preserve independence from traffic routing")
	}
	udp := &C.Metadata{Type: C.TUN, NetWork: C.UDP, Uid: 10222}
	if err := minclashIdentify(udp); err != nil || udp.MinClashTrafficUID != 0 || lookups != 1 {
		t.Fatal("ordinary UDP did not retain unverified attribution")
	}
}
