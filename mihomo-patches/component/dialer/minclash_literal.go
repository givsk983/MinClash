package dialer

import (
	"context"
	"net"
	"net/netip"
	"strconv"
)

// MinClashDialLiteral uses the normal outbound socket policy without first
// consulting subscription hosts or a DNS resolver.
func MinClashDialLiteral(ctx context.Context, network string, address netip.AddrPort) (net.Conn, error) {
	return dialContext(ctx, network, address.Addr(), strconv.Itoa(int(address.Port())), applyOptions())
}
