package dns

import (
	"context"
	"errors"
	"net"
	"net/netip"

	"github.com/metacubex/mihomo/component/dialer"
)

const (
	minclashDoHHost     = "1.1.1.1"
	MinClashDoHEndpoint = "https://" + minclashDoHHost + "/dns-query"
	minclashDoHAddress  = minclashDoHHost + ":443"
)

type MinClashDoHMode uint8

const (
	MinClashDoHOrdinary MinClashDoHMode = iota
	MinClashDoHProtected
	MinClashDoHBootstrap
)

var errMinClashDNSBootstrapSocket = errors.New("unexpected MinClash DNS bootstrap socket")

func minclashDialDNSBootstrap(ctx context.Context, network, address string) (net.Conn, error) {
	if network != "tcp" || address != minclashDoHAddress {
		return nil, errMinClashDNSBootstrapSocket
	}
	return dialer.MinClashDialLiteral(ctx, "tcp4", netip.MustParseAddrPort(minclashDoHAddress))
}
