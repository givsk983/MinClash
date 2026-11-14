//go:build go1.23 && !linux && !android

package keepalive

var minclashKeepAliveSupported = func() bool {
	return SupportTCPKeepAliveIdle() && SupportTCPKeepAliveInterval() && SupportTCPKeepAliveCount()
}
