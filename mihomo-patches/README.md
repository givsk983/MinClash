# MinClash Mihomo modifications

Modified on 2026-11-16, based on Mihomo v1.19.33, commit
`3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8`. The upstream GPL v3 license applies.
The source archive SHA-256 is
`ecda5f8c8a8a1d60bc088c2640c85d92c8f002c0686234a26bdf671735afe216`.

Upstream Android forcibly disables TCP keepalive. This feature is OFF by default.
Users can opt in from Settings and select one of these exact environment values
for `MINCLASH_TCP_KEEPALIVE`:

| Policy | Idle time | Retry interval | Unanswered probes |
| --- | --- | --- | --- |
| conservative | 180 seconds | 30 seconds | 3 |
| balanced | 120 seconds | 20 seconds | 3 |
| aggressive | 60 seconds | 15 seconds | 3 |

The Root supervisor reads the user's saved mode only when starting the core.
Saving a setting does not interrupt existing connections. The App reports a
pending change and offers an explicit TUN reconnect that restores node choices.
When disabled, missing, invalid, or unsupported, upstream connection behavior is
retained. Config reloads preserve an enabled policy for that core session.

Before enabling, the socket-request helper checks the selected policy's options
on an unconnected socket. The core independently checks support once per process.
Any unsupported option disables the optional policy, and the App reports fallback.
An error while configuring an individual connection disables its partially
applied keepalive settings. Capability checks do not send network traffic.
Normal TCP data delays probes; healthy idle sockets are retained. UDP/QUIC behavior
is unchanged. No new polling, periodic connection deletion, wakelock, Android
battery exemption or system-wide TCP setting is introduced.

This can keep NAT mappings alive and detect dead outbound TCP peers sooner. It
does not promise immediate Android background delivery or prevent Doze from
suspending the CPU/radio. Probes add a small amount of traffic and can increase
power use depending on the number of connections. Recovery timings depend on
kernel scheduling and suspend time; idle + 3 × interval is not a wakeup deadline.

Install Go 1.26.11, Android NDK 27.0.12077973, and the usual App build prerequisites.
Set `MINCLASH_GO` to the Go executable if it is not on PATH. Run
`python build.py --release` in the MinClash repository. `build_core.py` verifies the
fixed upstream archive, overlays this directory's Go files, runs the keepalive
tests, and builds Android ARM64 with CGO enabled, `with_gvisor`, `-trimpath`,
`--target=aarch64-linux-android28`, and a 16 KiB maximum ELF page size.
The official v1.19.33 vendor archive is also SHA-256 pinned
(`7ac4d4da0e6a29ef6ed18d2cf8f4c58d361d049ae364fd841acc883aa3a9e1cc`),
and builds use `-mod=vendor` without fetching individual Go modules.
All caches remain in the project build directory. The resulting module, APK,
and patched source archive are saved in `dist`.

The separate source archive contains the complete upstream source plus these
modifications, tests and vendored dependencies. From its root, use Go 1.26.11 and run:

```sh
go test -mod=vendor ./component/keepalive -count=1
GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
  CC='clang --target=aarch64-linux-android28' \
  CGO_LDFLAGS='-Wl,-z,max-page-size=16384' \
  go build -mod=vendor -tags with_gvisor -trimpath \
  -ldflags '-X github.com/metacubex/mihomo/constant.Version=v1.19.33-minclash.2 -X github.com/metacubex/mihomo/constant.BuildTime=2026-11-16T00:00:00Z -w -s -buildid=' \
  -o mihomo .
```

Here `clang` must be the NDK compiler. On Windows set the environment variables in
PowerShell and quote the absolute compiler path in `CC` if needed.

## Opt-in application leak protection (2026-11-16)

Root module 0.0.10 / revision 9 introduces a separate root-owned policy at
`/data/adb/minclash/protection.json`. Default is off. Source YAML and portable
backups do not carry the policy. The App gates controls on both revision and
`protection=1`; legacy modules retain their original capabilities.

`mihomo_patches.py` adds exact hooks to the SHA-pinned upstream; a mismatched
hook aborts the build. The complete source archive already contains applied
hooks and does not need the patch generator to rebuild.

- TUN origins are looked up by socket UID independently of find-process-mode.
  Protected UIDs resolve to the saved target before SpecialProxy and global /
  direct modes. Unknown origins (including UID 0) fail closed while app
  protection is enabled. Isolated processes and delegated service requests
  are outside the supported app scope.
- Each UDP packet gets private metadata and an origin check. NAT keys include
  UID so a reused source port cannot inherit another app's direct connection.
- The metadata guard survives Clone/Pure and nested groups. Proxy entry points
  reject direct/compatible/pass/rematch/DNS leaves, with a second check in the
  actual Direct TCP and UDP socket methods. No unsupported-UDP direct fallback
  is added. Proxy infrastructure still needs ordinary connections to its server.
- Strict DNS is applied on every raw-config parse, including controller reloads.
  Subscription resolver policies, fallback, appended system DNS, and ECS settings
  are replaced. Application-domain DNS uses Cloudflare DoH through the explicitly
  selected proxy. Missing proxies fail closed rather than becoming interface
  names. Node-name bootstrap is REFUSED unless separately enabled, in which case
  only the proxy-server resolver uses direct Cloudflare DoH. Explicit config
  hosts mappings or literal node IPs work without this exception.
- The native manager accepts bounded JSON over stdin, checks expected policy
  digest, validates package/UID bindings using Android PackageManager, and rejects
  conflicting targets for shared UIDs. No input is evaluated by a shell.
- Before replacing policy or stopping an existing core, HOLD rejects the union
  of old and new UIDs for both families. RUN rejects protected sockets not routed
  to minclash0, including loopback bypass. Strict DNS additionally blocks direct
  port 53 and app/system Private DNS port 853. Both families must be installed
  before HOLD is removed. Failures retain existing blocks. Only owned chains and
  their jumps are changed; system firewall policy is not flushed or ACCEPTed.
- Rule ownership is recorded before mutation. An unused, disabled feature does
  not require firewall capabilities or install rules. Explicit disable can
  recover a corrupt policy. Ordinary stop/retry/route cleanup leaves protection
  intact. Early boot attempts restore saved UID blocks without networking or
  PackageManager; service/start later verify and reinstall them. The early hook
  is asynchronous to avoid stalling Android boot and cannot cover the interval
  before it executes. Uninstall explicitly removes protection.

The UI distinguishes firewall readiness, current package bindings and the
running policy marker. These are snapshots, not continuous zero-leak guarantees.
Other root software can change rules. Module disabling, reinstall/UID changes,
other app profiles, delegated requests and hotspot clients require separate
handling; hotspot proxy sharing is rejected while protection is enabled.
Strict mode may affect OS connectivity validation, LAN names and CDN choices.
It does not change device identifiers, SIM, GPS, uploaded data or account identity.

Run local regression tests (loopback sockets are required):

```sh
go test -mod=vendor ./component/protection ./adapter/outbound ./config ./tunnel -run 'MinClash|Protection' -count=1
```

The tests include real SOCKS payload transfer, nested aliased DIRECT denial for
TCP/UDP and IPv4/IPv6 when available, strict-DNS missing-target rejection,
mode precedence, unknown-origin rejection, policy compare-and-swap, both-family
firewall staging/failure/cleanup through a stateful runner, and explicit recovery
from corrupt policy. Android kernel firewall and phone handoff/reboot acceptance
remain separate device checks; host tests do not certify those behaviors.
