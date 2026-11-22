package config

import (
	"context"
	"fmt"

	"github.com/metacubex/mihomo/component/protection"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/dns"
)

func minclashValidateProxies(proxies map[string]C.Proxy) error {
	p := protection.Runtime
	for _, app := range p.Apps {
		if _, ok := proxies[app.Target]; !ok {
			return fmt.Errorf("protected proxy is missing: %s", app.Target)
		}
	}
	if p.StrictDNS {
		if _, ok := proxies[p.DNSProxy]; !ok {
			return fmt.Errorf("strict DNS proxy is missing: %s", p.DNSProxy)
		}
	}
	return nil
}

// Apply on EVERY parse, including controller reloads. Imported DNS cannot
// override the root-owned policy. All application-domain resolvers are proxied.
func minclashDNS(raw *RawConfig) {
	p := protection.Runtime
	if !p.StrictDNS {
		return
	}
	upstream := []string{dns.MinClashDoHEndpoint}
	bootstrap := []string{"rcode://refused"}
	if p.Bootstrap {
		bootstrap = upstream
	}
	raw.DNS = RawDNS{
		Enable: true, IPv6: true, EnhancedMode: C.DNSFakeIP, FakeIPRange: "198.18.0.1/16",
		NameServer: upstream, DirectNameServer: upstream, ProxyServerNameserver: bootstrap,
		// The upstream parser requires a literal address here. The parsed
		// strict configuration replaces this placeholder with a refusal.
		DefaultNameserver: []string{"127.0.0.1:9"},
	}
	raw.ClashForAndroid.AppendSystemDNS = false
}

// Bind the actual configured adapter. This keeps imported proxy names out
// of URL fragments and skips DNS dialer's RULES and interface selectors.
func minclashBindDNS(config *DNS, proxies map[string]C.Proxy) {
	p := protection.Runtime
	if !p.StrictDNS {
		return
	}
	proxy := minclashProtectedDNSAdapter{proxies[p.DNSProxy]}
	for _, servers := range [][]dns.NameServer{config.NameServer, config.DirectNameServer} {
		for i := range servers {
			servers[i].ProxyAdapter = proxy
			servers[i].MinClashDoHMode = dns.MinClashDoHProtected
		}
	}
	if p.Bootstrap {
		for i := range config.ProxyServerNameserver {
			config.ProxyServerNameserver[i].MinClashDoHMode = dns.MinClashDoHBootstrap
		}
	}
	config.DefaultNameserver = []dns.NameServer{{Net: "rcode", Addr: "refused"}}
}

type minclashProtectedDNSAdapter struct{ C.ProxyAdapter }

func (p minclashProtectedDNSAdapter) DialContext(ctx context.Context, metadata *C.Metadata) (C.Conn, error) {
	metadata.MinClashProtected = true
	return p.ProxyAdapter.DialContext(ctx, metadata)
}

func (p minclashProtectedDNSAdapter) ListenPacketContext(ctx context.Context, metadata *C.Metadata) (C.PacketConn, error) {
	metadata.MinClashProtected = true
	return p.ProxyAdapter.ListenPacketContext(ctx, metadata)
}
