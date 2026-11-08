# MinClash 0.0.1

MinClash 是 Android Root TUN 工具，APK 内置完整的 KernelSU / Magisk 模块和 Mihomo v1.19.33 Android ARM64 内核。发布时只需要分发 `dist/MinClash.apk`。适用于 ARM64、Android 9 及以上，且内核支持 `/dev/tun` 或 `/dev/net/tun` 的 Root 手机。

## 安装和使用

1. 安装 `dist/MinClash.apk`，在 KernelSU / Magisk 中为 MinClash 授予 Root 权限。
2. 在 App「设置 → 内置模块」点击「安装内置模块」，安装成功后重启手机。无需另外下载模块 ZIP。如果管理器不支持直接安装，可点击「导出模块安装包」，再到管理器中从本地安装。
3. 在「配置」页导入 Clash / Mihomo YAML 订阅链接或本地文件，再回到「概览」点击「启动 TUN」。也可以先导入 `direct-smoke.yaml` 验证直连转发。
4. 运行后可在「代理控制」切换规则、全局、直连模式，并在「代理组与节点」选择节点。全局模式使用 `GLOBAL` 组，规则模式使用配置中各条规则指定的组。

「设置」页管理开机自启、内置模块及诊断。开机自启默认开启，并在有有效配置时生效。「停止 TUN」停止当前会话；若要让下次开机也保持停止，请同时关掉自启开关。导入或更新订阅前请先停止 TUN。App 保存最近一次成功导入的订阅链接，可通过「更新当前订阅」重新下载。遇到错误时，可到「设置 → 内核日志」查看。

## 运行方式

- App 通过 `su` 调用固定的控制命令，配置经标准输入交给模块。模块用 `mihomo -t` 校验临时文件，再原子替换配置。App 退出后，模块继续独立运行。
- 内置模块使用 SHA-256 校验后，通过 `ksud module install` 或 `magisk --install-module` 安装。保留 `/data/adb/minclash` 中已有的配置和日志，安装完成后提示用户重启，不自动重启设备。App 识别已安装版本和待重启的模块更新。
- `service.sh` 在 Android 完成启动后启动守护进程。守护进程监测 `minclash0`、策略路由和内核进程，故障后按 3 秒至 60 秒的间隔退避重试。停止时先发 `SIGTERM`，再检查并清理本次启动占用的规则。模块保留 `20882` 路由表及 `8800–8810` 优先级段，启动前会检查冲突。
- 导入保留 YAML 的节点、策略组和规则。只有节点或节点提供者的 YAML 会补上默认选择组和匹配规则。模块统一使用 Mihomo `mips` TUN、自动路由和 53 端口 DNS 接管。导入配置中的额外监听、外部控制器和 NTP 设置会被移除。未填写 DNS 上游时使用 Mihomo 内置默认值。支持范围为手机本机流量。请在测试时先关闭其他 VPN 或透明代理模块，Android 的私人 DNS 也可能绕过 53 端口接管。
- App 通过 Root 访问数据目录里的 Unix 套接字控制 Mihomo。手动切换的模式会在下次启动 TUN 时恢复。节点选择由 Mihomo 的 `profile.store-selected` 设置控制是否记忆。
- 配置和最近日志位于仅 Root 可访问的 `/data/adb/minclash`。重新安装模块时继续使用此目录中的配置。需要恢复网络时，可在 App 停止 TUN，或者在 Magisk 禁用模块并重启。

## 构建

准备 Python 3.11 或以上、JDK 17 或以上、Android SDK Platform 36、Build Tools 36.0.0 和 NDK 27.0.12077973。通过 `ANDROID_HOME` 指定 SDK 目录，再运行 `python build.py`。如果 NDK 安装在其他位置，可通过 `ANDROID_NDK_HOME` 指定其目录。脚本校验下载文件的 SHA-256，在 `build` 中生成模块 ZIP，再将其和校验值嵌入 `dist/MinClash.apk`。后续升级应保留 `build/debug.keystore`。

App 和模块的展示版本统一为 `0.0.1`，内部 `versionCode=5`，可直接覆盖先前版本并保留数据。后续发布时递增内部版本号。构建会检查 App 与模块版本是否一致。

上游版本、模块安装规范和 TUN 参数可查阅 [Mihomo v1.19.33](https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.33)、[Magisk 开发文档](https://topjohnwu.github.io/Magisk/guides.html) 和 [Mihomo TUN 文档](https://wiki.metacubex.one/config/inbound/tun/)。

## 验证

保留了首次 PID 检测误报修复，以及每 4 秒静默刷新状态的逻辑，自动查询不会反复禁用按钮。PID 回归检查可使用 Root 管理器的 BusyBox 运行 `tests/pid-check.sh /path/to/control.sh`，覆盖有效 PID、PID 重用、旧开机标识及失效记录。
