package dns

import (
	"context"
	"errors"
	"testing"

	"github.com/metacubex/http"
	"github.com/metacubex/mihomo/component/ca"
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
