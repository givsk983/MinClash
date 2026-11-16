package config

import (
	"github.com/metacubex/mihomo/component/protection"
	"testing"
)

func TestMinClashDNSOverride(t *testing.T) {
	old := protection.Runtime
	defer func() { protection.Runtime = old }()
	protection.Runtime = protection.Policy{Version: 1, StrictDNS: true, DNSProxy: "Safe"}
	raw := &RawConfig{DNS: RawDNS{NameServer: []string{"system"}, Fallback: []string{"223.5.5.5"}, ProxyServerNameserver: []string{"system"}}}
	minclashDNS(raw)
	if len(raw.DNS.Fallback) != 0 || raw.DNS.ProxyServerNameserver[0] != "rcode://refused" || raw.DNS.NameServer[0] != "https://1.1.1.1/dns-query#MINCLASH-STRICT-DNS" {
		t.Fatal("subscription DNS survived")
	}
	if _, err := parseDNS(raw, nil); err != nil {
		t.Fatal(err)
	}
	protection.Runtime.Bootstrap = true
	minclashDNS(raw)
	if raw.DNS.ProxyServerNameserver[0] != "https://1.1.1.1/dns-query#DIRECT" {
		t.Fatal("bootstrap not explicit")
	}
	protection.Runtime.StrictDNS = false
	raw.DNS.NameServer = []string{"system"}
	minclashDNS(raw)
	if raw.DNS.NameServer[0] != "system" {
		t.Fatal("off changed behavior")
	}
}
