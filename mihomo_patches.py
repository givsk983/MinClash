"""Small, exact hooks into the SHA-pinned upstream. Fail on unexpected source."""

def apply(source):
    def replace(path, old, new, count=1):
        file = source / path
        text = file.read_text(encoding="utf-8")
        if text.count(old) != count:
            raise RuntimeError(f"Unexpected upstream hook: {path}: {old[:70]!r}")
        file.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

    replace("hub/route/server.go", 'r.Get("/memory", memory)',
            'r.Get("/memory", memory)\n\t\tr.Get("/minclash/statistics", minclashStatistics)')
    replace("tunnel/statistic/manager.go", "memory        uint64", "memory        atomic.Uint64")
    replace("tunnel/statistic/manager.go", "return m.memory", "return m.memory.Load()")
    replace("tunnel/statistic/manager.go", "Memory:        m.memory,", "Memory:        m.memory.Load(),")
    replace("tunnel/statistic/manager.go", "m.memory = stat.RSS", "m.memory.Store(stat.RSS)")

    replace("main.go", '"github.com/metacubex/mihomo/component/age"',
            '"github.com/metacubex/mihomo/component/age"\n "github.com/metacubex/mihomo/component/protection"')
    replace("main.go", 'version                       bool', 'protectionCommand string\n version                       bool')
    replace("main.go", 'func init() {', 'func init() {\n flag.StringVar(&protectionCommand, "minclash-protection", "", "manage opt-in protection (root only)")')
    replace("main.go", 'func main() {', '''func main() {
 if protectionCommand != "" {
   if err := protection.Manage(protectionCommand); err != nil { fmt.Fprintln(os.Stderr, err); os.Exit(1) }; return
 }
 if err := protection.LoadRuntime(); err != nil { fmt.Fprintln(os.Stderr, "invalid protection policy:", err); os.Exit(1) }
''')
    replace("constant/metadata.go", 'type Metadata struct {', 'type Metadata struct {\n MinClashProtected bool `json:"-"`\n MinClashIdentified bool `json:"-"`')
    replace("component/process/process_linux.go",
            "func resolveSocketByNetlink(network string, ip netip.Addr, srcPort int) (uid uint32, inode uint32, err error) {",
            "func querySocketByNetlink(network string, ip netip.Addr, srcPort int) ([]netlink.Message, error) {")
    replace("component/process/process_linux.go", "return 0, 0, ErrInvalidNetwork\n\t}\n\n\tcopy(request.Src",
            "return nil, ErrInvalidNetwork\n\t}\n\n\tcopy(request.Src")
    replace("component/process/process_linux.go", '''conn, err := netlink.Dial(unix.NETLINK_INET_DIAG, nil)
	if err != nil {
		return 0, 0, err
	}''', '''conn, err := netlink.Dial(unix.NETLINK_INET_DIAG, nil)
	if err != nil {
		return nil, err
	}''')
    replace("component/process/process_linux.go", "messages, err := conn.Execute(message)",
            """return conn.Execute(message)
}

func resolveSocketByNetlink(network string, ip netip.Addr, srcPort int) (uid uint32, inode uint32, err error) {
	messages, err := querySocketByNetlink(network, ip, srcPort)""")
    replace("adapter/outbound/direct.go", '"fmt"', '"fmt"\n "errors"')
    for signature in ['func (d *Direct) DialContext(ctx context.Context, metadata *C.Metadata) (C.Conn, error) {',
                      'func (d *Direct) ListenPacketContext(ctx context.Context, metadata *C.Metadata) (C.PacketConn, error) {']:
        replace("adapter/outbound/direct.go", signature, signature + '\n if metadata.MinClashProtected { return nil, errors.New("MINCLASH_DIRECT_BLOCKED") }')
    # This also rejects group indirections to rematch/pass/DNS before they can
    # replace metadata; Direct itself is checked again at the final socket boundary.
    for signature in ['func (p *Proxy) DialContext(ctx context.Context, metadata *C.Metadata) (C.Conn, error) {',
                      'func (p *Proxy) ListenPacketContext(ctx context.Context, metadata *C.Metadata) (C.PacketConn, error) {']:
        replace("adapter/adapter.go", signature, signature + '''
 if metadata.MinClashProtected {
   switch p.Type() { case C.Direct, C.Compatible, C.Pass, C.PassRule, C.Rematch, C.Dns:
     return nil, fmt.Errorf("MINCLASH_UNSAFE_OUTBOUND")
   }
 }
''')
    replace("tunnel/tunnel.go", 'func resolveMetadata(metadata *C.Metadata) (proxy C.Proxy, rule C.Rule, err error) {', '''func resolveMetadata(metadata *C.Metadata) (proxy C.Proxy, rule C.Rule, err error) {
 if protected, handled, protectErr := minclashResolve(metadata); handled { return protected, nil, protectErr }
''')
    replace("tunnel/tunnel.go", '\tpacketAdapter := C.NewPacketAdapter(packet, metadata)', '''	packetAdapter := minclashIsolatePacket(C.NewPacketAdapter(packet, metadata))
	if err := minclashIdentify(packetAdapter.Metadata()); err != nil {
		packet.Drop()
		return
	}''')
    replace("tunnel/tunnel.go", '\tkey := packet.Key()', '''	key := packet.Key()
	if metadata.MinClashIdentified {
		key = fmt.Sprintf("%s|uid:%d", key, metadata.Uid)
	}''')
    replace("tunnel/tunnel.go", '''	metadata := connCtx.Metadata()
	if !metadata.Valid() {''', '''	metadata := connCtx.Metadata()
	if err := minclashIdentify(metadata); err != nil {
		return
	}
	if !metadata.Valid() {''')
    replace("tunnel/tunnel.go", '''					} else {
						metadata.Process = filepath.Base(path)''', '''					} else if !metadata.MinClashIdentified || metadata.Uid == uid {
						metadata.Process = filepath.Base(path)''')
    replace("tunnel/tunnel.go", 'func handleUDPConn(packet C.PacketAdapter) {', '''func handleUDPConn(packet C.PacketAdapter) {
 if minclashRejectWebRTCUDP(packet) {
   packet.Drop()
   return
 }''')
    replace("tunnel/tunnel.go", 'if !isHandle(connCtx.Metadata().Type) {',
            'if !isHandle(connCtx.Metadata().Type) || minclashRejectWebRTCTCP(connCtx.Metadata()) {')
    replace("listener/sing_tun/prepare.go", '"github.com/metacubex/mihomo/component/dialer"',
            '"github.com/metacubex/mihomo/component/dialer"\n\t"github.com/metacubex/mihomo/component/protection"')
    replace("listener/sing_tun/prepare.go", '\t\tlog.Infoln("[ICMP] %s %s --> %s using DIRECT", network, source, destination)', '''		// TUN ICMP has no verified socket owner and this path dials directly.
		if len(protection.Runtime.Apps) != 0 {
			return nil, tun.ErrDrop
		}
		log.Infoln("[ICMP] %s %s --> %s using DIRECT", network, source, destination)''')
    replace("config/config.go", 'func ParseRawConfig(rawCfg *RawConfig) (*Config, error) {', '''func ParseRawConfig(rawCfg *RawConfig) (*Config, error) {
 minclashDNS(rawCfg)
''')
    replace("config/config.go", '\tconfig.Proxies = proxies', '\tif err := minclashValidateProxies(proxies); err != nil { return nil, err }\n\tconfig.Proxies = proxies')
    replace("config/config.go", '\tconfig.DNS = dnsCfg', '\tminclashBindDNS(dnsCfg, proxies)\n\tconfig.DNS = dnsCfg')
    replace("dns/resolver.go", 'type NameServer struct {', 'type NameServer struct {\n\tMinClashDoHMode MinClashDoHMode')
    replace("dns/resolver.go", '\t\tns.ProxyName == ns2.ProxyName &&',
            '\t\tns.ProxyName == ns2.ProxyName &&\n\t\tns.MinClashDoHMode == ns2.MinClashDoHMode &&', count=2)
    replace("dns/util.go", 'newDoHClient(s.Addr, resolver, s.PreferH3, s.Params, s.ProxyAdapter, s.ProxyName)',
            'newDoHClient(s, resolver)')
    replace("dns/doh.go", '\t"context"\n', '\t"context"\n\t"crypto/x509"\n')
    replace("dns/doh.go", '\tskipCertVerify bool', '\tminclashDoHMode MinClashDoHMode\n\tskipCertVerify bool')
    replace("dns/doh.go", '''func newDoHClient(urlString string, r resolver.Resolver, preferH3 bool, params map[string]string, proxyAdapter C.ProxyAdapter, proxyName string) dnsClient {
\tu, _ := url.Parse(urlString)
\thttpVersions := DefaultHTTPVersions
\tif preferH3 {''', '''func newDoHClient(ns NameServer, r resolver.Resolver) dnsClient {
\tu, _ := url.Parse(ns.Addr)
\tparams := ns.Params
\thttpVersions := DefaultHTTPVersions
\tif ns.PreferH3 {''')
    replace("dns/doh.go", 'newDNSDialer(r, proxyAdapter, proxyName)', 'newDNSDialer(r, ns.ProxyAdapter, ns.ProxyName)')
    replace("dns/doh.go", '\t\thttpVersions: httpVersions,',
            '\t\thttpVersions: httpVersions,\n\t\tminclashDoHMode: ns.MinClashDoHMode,')
    replace("dns/doh.go", '\tif params["skip-cert-verify"] == "true" {\n\t\tdoh.skipCertVerify = true\n\t}\n\tdoh.nameCertVerify = params["name-cert-verify"]',
            '\tif ns.MinClashDoHMode == MinClashDoHOrdinary {\n\t\tdoh.skipCertVerify = params["skip-cert-verify"] == "true"\n\t\tdoh.nameCertVerify = params["name-cert-verify"]\n\t}')
    replace("dns/doh.go", '\tdoh.client = client', '''\tif doh.minclashDoHMode != MinClashDoHOrdinary {
\t\tclient.CheckRedirect = func(*http.Request, []*http.Request) error {
\t\t\treturn http.ErrUseLastResponse
\t\t}
\t}
\tdoh.client = client''')
    replace("dns/doh.go", '\t\t\treturn doh.dialer.DialContext(ctx, network, addr)', '''\t\t\tif doh.minclashDoHMode == MinClashDoHBootstrap {
\t\t\t\treturn minclashDialDNSBootstrap(ctx, network, addr)
\t\t\t}
\t\t\treturn doh.dialer.DialContext(ctx, network, addr)''')
    replace("dns/doh.go", '\t\tNameCertVerify: doh.nameCertVerify,',
            '\t\tNameCertVerify: doh.nameCertVerify,\n\t\tZeroTrust: doh.minclashDoHMode != MinClashDoHOrdinary,')
    replace("dns/doh.go", '\tvar nextProtos []string', '''\tif doh.minclashDoHMode != MinClashDoHOrdinary && tlsConfig.RootCAs == nil {
\t\t// Pinned source builds may lack the optional embedded CA bundle.
\t\t// Load system trust directly, separate from subscription additions.
\t\ttlsConfig.RootCAs, err = x509.SystemCertPool()
\t\tif err != nil {
\t\t\treturn nil, fmt.Errorf("load strict DNS system trust: %w", err)
\t\t}
\t}
\tvar nextProtos []string''')

    # Android hands off by changing IP policy rules even when both links stay up.
    tun = "vendor/github.com/metacubex/sing-tun/"
    replace(tun + "monitor_linux.go", 'func (m *networkUpdateMonitor) Start() error {', '''func (m *networkUpdateMonitor) Start() error {
	if runtime.GOOS == "android" {
		return m.startMinClashAndroidMonitor()
	}''')
    replace(tun + "monitor_shared.go", '\t"errors"', '\t"errors"\n\t"runtime"')
    replace(tun + "monitor_shared.go", '\tnoRoute               atomic.Bool',
            '\tnoRoute               atomic.Bool\n\tandroidUplink         string\n\tandroidTunTable       int\n\tandroidFamilies       atomic.Pointer[androidUplinkInterfaces]')
    replace(tun + "monitor_shared.go", 'type defaultInterfaceMonitor struct {', '''type androidUplinkInterfaces struct {
	ipv4, ipv6 *control.Interface
	ipv4Blocked, ipv6Blocked bool
}

type defaultInterfaceMonitor struct {''')
    replace(tun + "monitor.go", '\tOverrideAndroidVPN    bool',
            '\tOverrideAndroidVPN    bool\n\tMinClashTunTable      int')
    replace(tun + "monitor_shared.go", '\t\toverrideAndroidVPN:    options.OverrideAndroidVPN,',
            '\t\toverrideAndroidVPN:    options.OverrideAndroidVPN,\n\t\tandroidTunTable:       options.MinClashTunTable,')
    replace("listener/sing_tun/server.go", 'OverrideAndroidVPN: overrideAndroidVPN})',
            'OverrideAndroidVPN: overrideAndroidVPN, MinClashTunTable: tableIndex})')
    replace("listener/sing_tun/server.go", '\tif netInterface := d.defaultInterfaceMonitor.DefaultInterface(); netInterface != nil {', '''	if destination.IsValid() && !destination.IsUnspecified() {
		if monitor, ok := d.defaultInterfaceMonitor.(interface {
			DefaultInterfaceForFamily(bool) (*control.Interface, bool)
		}); ok {
			netInterface, blocked := monitor.DefaultInterfaceForFamily(destination.Unmap().Is6())
			if blocked {
				return "<invalid>"
			}
			if netInterface != nil {
				return netInterface.Name
			}
		}
	}
	if netInterface := d.defaultInterfaceMonitor.DefaultInterface(); netInterface != nil {''')
    replace(tun + "monitor_shared.go", '\tm.element = m.networkMonitor.RegisterCallback(m.delayCheckUpdate)', '''	check := m.delayCheckUpdate
	if runtime.GOOS == "android" {
		check = m.postCheckUpdate
	}
	m.element = m.networkMonitor.RegisterCallback(check)''')
    replace(tun + "monitor_shared.go", '\t\t\tm.defaultInterface.Store(nil)\n\t\t\tm.emit(nil, 0)', '''			// Android may remove the old default rule before installing its replacement.
			if runtime.GOOS != "android" {
				m.defaultInterface.Store(nil)
				m.emit(nil, 0)
			}''')
    replace(tun + "monitor_android.go", 'func (m *defaultInterfaceMonitor) checkUpdate() error {',
            'func (m *defaultInterfaceMonitor) upstreamAndroidCheckUpdate() error {')

