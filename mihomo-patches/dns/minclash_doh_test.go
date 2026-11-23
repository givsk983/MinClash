package dns

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/metacubex/http"
	"github.com/metacubex/mihomo/component/ca"
	D "github.com/miekg/dns"
)

func TestMinClashDoHIsolatesTLSAndRejectsRedirects(t *testing.T) {
	ordinary := NameServer{Net: "https", Addr: MinClashDoHEndpoint}
	strict := ordinary
	strict.MinClashDoHMode = MinClashDoHProtected
	if ordinary.Equal(strict) || ordinary.transportEqual(strict) {
		t.Fatal("strict DNS can reuse an ordinary client's transport")
	}
	for _, mode := range []MinClashDoHMode{MinClashDoHOrdinary, MinClashDoHProtected, MinClashDoHBootstrap} {
		server := NameServer{
			Addr:            MinClashDoHEndpoint,
			Params:          map[string]string{"skip-cert-verify": "true", "name-cert-verify": "example.invalid"},
			MinClashDoHMode: mode,
		}
		resolver := newDoHClient(server, nil).(*dnsOverHTTPS)
		t.Cleanup(func() { _ = resolver.Close() })
		client, err := resolver.createClient(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		transport := client.Transport.(*http.Transport)
		config := transport.TLSClientConfig
		if mode != MinClashDoHOrdinary {
			if config.RootCAs == nil || config.RootCAs == ca.GetCertPool() || config.InsecureSkipVerify || config.VerifyConnection != nil {
				t.Fatal("strict DoH is using the global trust pool or a configurable verification override")
			}
			if client.CheckRedirect == nil || !errors.Is(client.CheckRedirect(nil, nil), http.ErrUseLastResponse) {
				t.Fatal("strict DoH can redirect away from its fixed resolver")
			}
		} else if config.RootCAs != ca.GetCertPool() || !config.InsecureSkipVerify || config.VerifyConnection == nil || client.CheckRedirect != nil {
			t.Fatal("ordinary DoH configuration changed")
		}
		if mode == MinClashDoHBootstrap {
			if _, err := transport.DialContext(context.Background(), "tcp", "unexpected.invalid:443"); !errors.Is(err, errMinClashDNSBootstrapSocket) {
				t.Fatalf("bootstrap allowed an unexpected target: %v", err)
			}
		}
	}
}

func TestMinClashDoHResetsEveryTransportOnNetworkChange(t *testing.T) {
	endpoint := func(name string) NameServer {
		return NameServer{Net: "https", Addr: "https://" + name + ".invalid/dns-query"}
	}
	shared := endpoint("main")
	shared.MinClashDoHMode = MinClashDoHProtected
	wrapped := shared
	wrapped.Params = map[string]string{"disable-ipv6": "true"}
	r := NewResolver(Config{
		Default:     []NameServer{endpoint("default")},
		Main:        []NameServer{shared},
		Fallback:    []NameServer{endpoint("fallback"), wrapped},
		Policy:      []Policy{{Domain: "app.invalid", NameServers: []NameServer{endpoint("app-policy")}}},
		ProxyServer: []NameServer{endpoint("proxy")},
		ProxyServerPolicy: []Policy{{
			Domain: "proxy.invalid", NameServers: []NameServer{endpoint("proxy-policy")},
		}},
		DirectServer: []NameServer{endpoint("direct")},
	})
	if rewrapClient(r.fallback[1], nil) != r.main[0] || len(r.resetClients) != 6 {
		t.Fatal("a shared resolver transport was not registered exactly once")
	}
	transports := map[string]dnsClient{
		"default":      r.defaultResolver.main[0],
		"main":         r.main[0],
		"fallback":     r.fallback[0],
		"app policy":   r.policy[0].Match("app.invalid")[0],
		"proxy":        r.ProxyResolver.main[0],
		"proxy policy": r.ProxyResolver.policy[0].Match("proxy.invalid")[0],
		"direct":       r.DirectResolver.main[0],
	}
	for _, transport := range transports {
		transport.(*dnsOverHTTPS).client = &http.Client{Transport: &http.Transport{}}
	}
	r.cache.SetWithExpire("cached", &D.Msg{}, time.Now().Add(time.Minute))

	// This is the resolver called by the interface monitor on a network change.
	r.Resolver.ResetConnection()

	for name, transport := range transports {
		if transport.(*dnsOverHTTPS).client != nil {
			t.Errorf("%s still has its old connection", name)
		}
	}
	if _, _, ok := r.cache.GetWithExpire("cached"); !ok {
		t.Error("reset was also applied to the DNS response cache")
	}
}
