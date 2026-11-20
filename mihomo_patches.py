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
    replace("tunnel/tunnel.go", '\tkey := packet.Key()', '''
 if err := minclashIdentify(metadata); err != nil { packet.Drop(); return }
 key := packet.Key()
 if metadata.MinClashIdentified { key = fmt.Sprintf("%s|uid:%d", key, metadata.Uid) }
''')
    replace("tunnel/tunnel.go", 'func handleUDPConn(packet C.PacketAdapter) {', '''func handleUDPConn(packet C.PacketAdapter) {
 if minclashRejectWebRTCUDP(packet) {
   packet.Drop()
   return
 }
 packet = minclashIsolatePacket(packet)''')
    replace("tunnel/tunnel.go", 'if !isHandle(connCtx.Metadata().Type) {',
            'if !isHandle(connCtx.Metadata().Type) || minclashRejectWebRTCTCP(connCtx.Metadata()) {')
    replace("config/config.go", 'func ParseRawConfig(rawCfg *RawConfig) (*Config, error) {', '''func ParseRawConfig(rawCfg *RawConfig) (*Config, error) {
 minclashDNS(rawCfg)
''')
    replace("config/config.go", '\tconfig.Proxies = proxies', '\tif err := minclashValidateProxies(proxies); err != nil { return nil, err }\n\tconfig.Proxies = proxies')
    replace("tunnel/dns_dialer.go", '"github.com/metacubex/mihomo/component/dialer"',
            '"github.com/metacubex/mihomo/component/dialer"\n "github.com/metacubex/mihomo/component/protection"')
    replace("tunnel/dns_dialer.go", '\tproxyName := d.proxyName', ''' proxyName := d.proxyName
 strict := protection.Runtime.StrictDNS && proxyName == protection.DNSProxy
 if strict { proxyName = protection.Runtime.DNSProxy }
''', count=2)
    replace("tunnel/dns_dialer.go", '\terr := metadata.SetRemoteAddress(addr)',
            ' metadata.MinClashProtected = strict\n\terr := metadata.SetRemoteAddress(addr)', count=2)
    replace("tunnel/dns_dialer.go", '\t\t\t} else {\n\t\t\t\topts = append(opts, dialer.WithInterface(proxyName))',
            '\t\t\t} else {\n if strict { return nil, fmt.Errorf("MINCLASH_DNS_PROXY_MISSING") }\n\t\t\t\topts = append(opts, dialer.WithInterface(proxyName))', count=2)

