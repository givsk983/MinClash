//go:build !go1.23

package keepalive

var minclashKeepAliveSupported = func() bool { return false }
