package config

import (
	"context"
	"errors"
	"testing"

	"github.com/metacubex/mihomo/component/protection"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/dns"
)

func TestMinClashDNSOverride(t *testing.T) {
	old := protection.Runtime
	defer func() { protection.Runtime = old }()
	protection.Runtime = protection.Policy{Version: 1, StrictDNS: true, DNSProxy: "Safe"}
	raw := &RawConfig{DNS: RawDNS{NameServer: []string{"system"}, Fallback: []string{"223.5.5.5"}, ProxyServerNameserver: []string{"system"}}}
	minclashDNS(raw)
	if len(raw.DNS.Fallback) != 0 || raw.DNS.ProxyServerNameserver[0] != "rcode://refused" || raw.DNS.NameServer[0] != dns.MinClashDoHEndpoint {
		t.Fatal("subscription DNS survived")
	}
	if _, err := parseDNS(raw, nil); err != nil {
		t.Fatal(err)
	}
	protection.Runtime.Bootstrap = true
	minclashDNS(raw)
	if raw.DNS.ProxyServerNameserver[0] != dns.MinClashDoHEndpoint {
		t.Fatal("bootstrap not explicit")
	}
	protection.Runtime.StrictDNS = false
	raw.DNS.NameServer = []string{"system"}
	minclashDNS(raw)
	if raw.DNS.NameServer[0] != "system" {
		t.Fatal("off changed behavior")
	}
}

func TestMinClashDNSBindsSelectedAdapter(t *testing.T) {
	old := protection.Runtime
	t.Cleanup(func() { protection.Runtime = old })
	protection.Runtime = protection.Policy{Version: 1, StrictDNS: true, DNSProxy: "RULES"}
	profile := []byte(`
proxies:
  - {name: Node, type: socks5, server: 127.0.0.1, port: 1080}
proxy-groups:
  - {name: RULES, type: select, proxies: [Node]}
rules: ['MATCH,DIRECT']
`)
	for _, bootstrap := range []bool{false, true} {
		protection.Runtime.Bootstrap = bootstrap
		config, err := Parse(profile)
		if err != nil {
			t.Fatal(err)
		}
		for _, server := range []dns.NameServer{config.DNS.NameServer[0], config.DNS.DirectNameServer[0]} {
			bound, ok := server.ProxyAdapter.(minclashProtectedDNSAdapter)
			if !ok || bound.ProxyAdapter != config.Proxies["RULES"] || server.ProxyName != "" || server.MinClashDoHMode != dns.MinClashDoHProtected {
				t.Fatalf("strict DNS did not bind the selected adapter: %#v", server)
			}
		}
		if server := config.DNS.DefaultNameserver[0]; server.Net != "rcode" || server.Addr != "refused" {
			t.Fatalf("default resolver could contact the network: %#v", server)
		}
		server := config.DNS.ProxyServerNameserver[0]
		if bootstrap {
			if server.Net != "https" || server.Addr != config.DNS.NameServer[0].Addr || server.ProxyAdapter != nil || server.ProxyName != "" || server.MinClashDoHMode != dns.MinClashDoHBootstrap {
				t.Fatalf("bootstrap did not use isolated, fixed-endpoint DoH: %#v", server)
			}
		} else if server.Net != "rcode" || server.Addr != "refused" {
			t.Fatalf("disabled bootstrap could contact the network: %#v", server)
		}
	}
	protection.Runtime.DNSProxy = "Missing"
	if _, err := Parse(profile); err == nil {
		t.Fatal("configuration accepted an unavailable strict DNS proxy")
	}
}

func TestMinClashDNSMarksBothSocketTypes(t *testing.T) {
	adapter := minclashProtectedDNSAdapter{minclashTestDNSAdapter{}}
	tcp, udp := &C.Metadata{}, &C.Metadata{}
	if _, err := adapter.DialContext(context.Background(), tcp); !errors.Is(err, minclashTestDNSDial) || !tcp.MinClashProtected {
		t.Fatal("TCP did not reach the selected adapter with the protection flag")
	}
	if _, err := adapter.ListenPacketContext(context.Background(), udp); !errors.Is(err, minclashTestDNSDial) || !udp.MinClashProtected {
		t.Fatal("UDP did not reach the selected adapter with the protection flag")
	}
}

var minclashTestDNSDial = errors.New("test DNS adapter called")

type minclashTestDNSAdapter struct{ C.ProxyAdapter }

func (minclashTestDNSAdapter) DialContext(_ context.Context, metadata *C.Metadata) (C.Conn, error) {
	if !metadata.MinClashProtected {
		return nil, errors.New("TCP adapter did not receive the protection flag")
	}
	return nil, minclashTestDNSDial
}

func (minclashTestDNSAdapter) ListenPacketContext(_ context.Context, metadata *C.Metadata) (C.PacketConn, error) {
	if !metadata.MinClashProtected {
		return nil, errors.New("UDP adapter did not receive the protection flag")
	}
	return nil, minclashTestDNSDial
}
