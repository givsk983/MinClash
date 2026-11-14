//go:build go1.23

package keepalive

import (
	"errors"
	"io"
	"net"
	"runtime"
	"testing"
	"time"
)

type minclashTCP struct {
	net.Conn
	config     net.KeepAliveConfig
	disabled   bool
	failConfig bool
}

func (c *minclashTCP) SetKeepAlive(enabled bool) error { c.disabled = !enabled; return nil }
func (c *minclashTCP) SetKeepAliveConfig(config net.KeepAliveConfig) error {
	c.config = config
	if c.failConfig {
		return errors.New("unsupported option")
	}
	return nil
}

func TestMinClashKeepAliveReload(t *testing.T) {
	defer func() { SetKeepAliveIdle(0); SetKeepAliveInterval(0); SetDisableKeepAlive(false) }()
	for _, policy := range []struct {
		mode           string
		idle, interval time.Duration
	}{
		{"conservative", 180 * time.Second, 30 * time.Second},
		{"balanced", 120 * time.Second, 20 * time.Second},
		{"aggressive", 60 * time.Second, 15 * time.Second},
	} {
		t.Setenv("MINCLASH_TCP_KEEPALIVE", policy.mode)
		for _, disabled := range []bool{false, true, false} {
			// A subscription/config reload must not reintroduce Android's forced disable,
			// or a very long idle/interval that leaves messaging sockets unusable.
			SetKeepAliveIdle(7200 * time.Second)
			SetKeepAliveInterval(7200 * time.Second)
			SetDisableKeepAlive(disabled)
			if DisableKeepAlive() {
				t.Fatal("Root recovery disabled after config reload")
			}
			dialer := &net.Dialer{}
			SetNetDialer(dialer)
			listener := &net.ListenConfig{}
			SetNetListenConfig(listener)
			conn := &minclashTCP{}
			TCPKeepAlive(conn)
			for _, config := range []net.KeepAliveConfig{dialer.KeepAliveConfig, listener.KeepAliveConfig, conn.config} {
				if !config.Enable || config.Idle != policy.idle || config.Interval != policy.interval {
					t.Fatalf("wrong recovery policy: %+v", config)
				}
				if SupportTCPKeepAliveCount() && config.Count != 3 {
					t.Fatalf("wrong retry count: %+v", config)
				}
			}
			if conn.disabled {
				t.Fatal("TCP connection keepalive disabled")
			}
		}
	}
}

func TestMinClashKeepAliveOptIn(t *testing.T) {
	defer func() { SetKeepAliveIdle(0); SetKeepAliveInterval(0); SetDisableKeepAlive(false) }()
	for _, flag := range []string{"", "off", "0", "1", "true", "invalid"} {
		t.Setenv("MINCLASH_TCP_KEEPALIVE", flag)
		SetKeepAliveIdle(120 * time.Second)
		SetKeepAliveInterval(45 * time.Second)
		SetDisableKeepAlive(false)
		if DisableKeepAlive() != (runtime.GOOS == "android") || KeepAliveIdle() != 120*time.Second || KeepAliveInterval() != 45*time.Second {
			t.Fatalf("upstream behavior changed without exact opt-in: %q", flag)
		}
		SetDisableKeepAlive(true)
		dialer := &net.Dialer{}
		SetNetDialer(dialer)
		conn := &minclashTCP{}
		TCPKeepAlive(conn)
		if dialer.KeepAlive >= 0 || dialer.KeepAliveConfig.Enable || !conn.disabled {
			t.Fatal("ordinary disable-keep-alive no longer honored")
		}
	}
}

func TestMinClashHealthyIdleSocket(t *testing.T) {
	t.Setenv("MINCLASH_TCP_KEEPALIVE", "balanced")
	SetDisableKeepAlive(true)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	client, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	server, err := listener.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	TCPKeepAlive(client)
	TCPKeepAlive(server)
	// Applying/reapplying recovery must not close healthy established sockets.
	TCPKeepAlive(client)
	client.SetDeadline(time.Now().Add(2 * time.Second))
	server.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err = client.Write([]byte("ok")); err != nil {
		t.Fatal(err)
	}
	var buffer [2]byte
	if _, err = io.ReadFull(server, buffer[:]); err != nil || string(buffer[:]) != "ok" {
		t.Fatalf("healthy socket interrupted: %v", err)
	}
}

func TestMinClashUnsupportedKernel(t *testing.T) {
	t.Setenv("MINCLASH_TCP_KEEPALIVE", "balanced")
	original := minclashKeepAliveSupported
	minclashKeepAliveSupported = func() bool { return false }
	defer func() { minclashKeepAliveSupported = original }()
	SetKeepAliveIdle(240 * time.Second)
	SetKeepAliveInterval(60 * time.Second)
	SetDisableKeepAlive(false)
	if minclashKeepAlive() || KeepAliveIdle() != 240*time.Second || KeepAliveInterval() != 60*time.Second {
		t.Fatal("unsupported kernel did not fall back to upstream")
	}
	if runtime.GOOS == "android" && !DisableKeepAlive() {
		t.Fatal("unsupported Android kernel still enabled")
	}
}

func TestMinClashSocketOptionFailure(t *testing.T) {
	t.Setenv("MINCLASH_TCP_KEEPALIVE", "balanced")
	SetDisableKeepAlive(false)
	conn := &minclashTCP{failConfig: true}
	TCPKeepAlive(conn)
	if !conn.disabled {
		t.Fatal("partially configured keepalive left enabled")
	}
}

func BenchmarkMinClashDialerPolicy(b *testing.B) {
	for _, mode := range []string{"off", "conservative", "balanced", "aggressive"} {
		b.Run(mode, func(b *testing.B) {
			b.Setenv("MINCLASH_TCP_KEEPALIVE", mode)
			SetDisableKeepAlive(false)
			dialer := &net.Dialer{}
			SetNetDialer(dialer)
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				SetNetDialer(dialer)
			}
		})
	}
}
