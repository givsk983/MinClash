package config_test

import (
	"context"
	"fmt"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/component/protection"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	_ "github.com/metacubex/mihomo/hub/executor"
	"io"
	"net"
	"testing"
	"time"
)

// Use a real SOCKS server and destination listener. A successful protected
// request must reach SOCKS; selecting an aliased DIRECT inside a nested group
// must not even open a destination socket, for either network family.
func TestMinClashRealProxyAndNestedDirect(t *testing.T) {
	old := protection.Runtime
	protection.Runtime = protection.Policy{Version: 1}
	defer func() { protection.Runtime = old }()
	proxy, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	received := make(chan string, 1)
	go func() {
		c, err := proxy.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(3 * time.Second))
		h := make([]byte, 2)
		if _, err = io.ReadFull(c, h); err != nil {
			return
		}
		methods := make([]byte, int(h[1]))
		if _, err = io.ReadFull(c, methods); err != nil {
			return
		}
		c.Write([]byte{5, 0})
		head := make([]byte, 4)
		if _, err = io.ReadFull(c, head); err != nil {
			return
		}
		n := 4
		if head[3] == 4 {
			n = 16
		}
		if head[3] == 3 {
			io.ReadFull(c, h[:1])
			n = int(h[0])
		}
		addr := make([]byte, n+2)
		if _, err = io.ReadFull(c, addr); err != nil {
			return
		}
		c.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 1})
		payload := make([]byte, 7)
		if _, err = io.ReadFull(c, payload); err == nil {
			received <- string(payload)
		}
	}()
	port := proxy.Addr().(*net.TCPAddr).Port
	cfg, err := config.Parse([]byte(fmt.Sprintf(`dns: {enable: false}
proxies:
  - {name: Node, type: socks5, server: 127.0.0.1, port: %d, udp: true}
  - {name: AliasDirect, type: direct}
proxy-groups:
  - {name: Nested, type: select, proxies: [Node, AliasDirect]}
  - {name: Safe, type: select, proxies: [Nested]}
rules: ['MATCH,DIRECT']
`, port)))
	if err != nil {
		t.Fatal(err)
	}
	m := &C.Metadata{MinClashProtected: true, NetWork: C.TCP}
	m.SetRemoteAddress("198.51.100.23:443")
	c, err := cfg.Proxies["Safe"].DialContext(context.Background(), m)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = c.Write([]byte("private")); err != nil {
		t.Fatal(err)
	}
	c.Close()
	select {
	case got := <-received:
		if got != "private" {
			t.Fatal(got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("proxy did not receive request")
	}
	if err = cfg.Proxies["Nested"].Adapter().(*outboundgroup.Selector).Set("AliasDirect"); err != nil {
		t.Fatal(err)
	}
	for _, address := range []string{"127.0.0.1:0", "[::1]:0"} {
		l, err := net.Listen("tcp", address)
		if err != nil {
			if address[0] == '[' {
				continue
			}
			t.Fatal(err)
		}
		m.SetRemoteAddress(l.Addr().String())
		if c, e := cfg.Proxies["Safe"].DialContext(context.Background(), m); e == nil {
			c.Close()
			t.Fatal("nested direct TCP allowed")
		}
		if c, e := cfg.Proxies["Safe"].ListenPacketContext(context.Background(), m); e == nil {
			c.Close()
			t.Fatal("nested direct UDP allowed")
		}
		l.(*net.TCPListener).SetDeadline(time.Now().Add(20 * time.Millisecond))
		if c, e := l.Accept(); e == nil {
			c.Close()
			t.Fatal("real direct endpoint received a protected connection")
		}
		l.Close()
	}
}
