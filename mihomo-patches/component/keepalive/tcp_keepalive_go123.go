//go:build go1.23

// MinClash modification, 2026-11-14: bound retries for opted-in Root TCP sockets.
package keepalive

import "net"

type TCPConn interface {
	net.Conn
	SetKeepAlive(keepalive bool) error
	SetKeepAliveConfig(config net.KeepAliveConfig) error
}

func keepAliveConfig() net.KeepAliveConfig {
	config := net.KeepAliveConfig{
		Enable:   true,
		Idle:     KeepAliveIdle(),
		Interval: KeepAliveInterval(),
	}
	if minclashKeepAlive() && SupportTCPKeepAliveCount() {
		config.Count = 3
	} else if !SupportTCPKeepAliveCount() {
		// it's recommended to set both Idle and Interval to non-negative values in conjunction with a -1
		// for Count on those old Windows if you intend to customize the TCP keep-alive settings.
		config.Count = -1
	}
	return config
}

func tcpKeepAlive(tcp TCPConn) {
	if DisableKeepAlive() {
		_ = tcp.SetKeepAlive(false)
	} else {
		if err := tcp.SetKeepAliveConfig(keepAliveConfig()); err != nil && minclashKeepAlive() {
			// Do not leave partially applied options enabled on unusual kernels.
			_ = tcp.SetKeepAlive(false)
		}
	}
}

func setNetDialer(dialer *net.Dialer) {
	if DisableKeepAlive() {
		dialer.KeepAlive = -1 // If negative, keep-alive probes are disabled.
		dialer.KeepAliveConfig.Enable = false
	} else {
		dialer.KeepAliveConfig = keepAliveConfig()
	}
}

func setNetListenConfig(lc *net.ListenConfig) {
	if DisableKeepAlive() {
		lc.KeepAlive = -1 // If negative, keep-alive probes are disabled.
		lc.KeepAliveConfig.Enable = false
	} else {
		lc.KeepAliveConfig = keepAliveConfig()
	}
}
