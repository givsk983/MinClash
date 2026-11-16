package config

import (
	"fmt"
	"github.com/metacubex/mihomo/component/protection"
	C "github.com/metacubex/mihomo/constant"
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
	upstream := []string{"https://1.1.1.1/dns-query#" + protection.DNSProxy}
	bootstrap := []string{"rcode://refused"}
	if p.Bootstrap {
		bootstrap = []string{"https://1.1.1.1/dns-query#DIRECT"}
	}
	raw.DNS = RawDNS{
		Enable: true, IPv6: true, EnhancedMode: C.DNSFakeIP, FakeIPRange: "198.18.0.1/16",
		NameServer: upstream, DirectNameServer: upstream, ProxyServerNameserver: bootstrap,
		// The DoH endpoint is already an IP; never resolve server names via OS DNS.
		DefaultNameserver: []string{"127.0.0.1:9"},
	}
	raw.ClashForAndroid.AppendSystemDNS = false
}
