# MinClash Mihomo TCP keepalive modification

Modified on 2026-11-14, based on Mihomo v1.19.33, commit
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
  -ldflags '-X github.com/metacubex/mihomo/constant.Version=v1.19.33-minclash.1 -X github.com/metacubex/mihomo/constant.BuildTime=2026-11-14T00:00:00Z -w -s -buildid=' \
  -o mihomo .
```

Here `clang` must be the NDK compiler. On Windows set the environment variables in
PowerShell and quote the absolute compiler path in `CC` if needed.
