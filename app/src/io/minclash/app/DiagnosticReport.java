package io.minclash.app;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

record DiagnosticReport(String text) {
    enum Fact {
        IFACE, ROUTE4, ROUTE6, UPLINK4, UPLINK6, APP_REJECT_RULE, LOG_SAMPLE,
        NODE_DNS, GEODATA, PROVIDER, CORE_TLS, TUN_SETUP, BOOT_UPLINK_WAIT, BOOT_UPLINK_RESTART, CONFIG
    }

    static DiagnosticReport collect(String appVersion, long appCode, int androidApi,
                                    Subscription.NetworkFailure subscriptionFailure, ExecutorService networkWorker)
        throws InterruptedException {
        Module.Status status = readStatus();
        Set<Fact> facts = null;
        Controller.Mode mode = null;
        Controller.OutletProbe outlet = null;
        if (status != null && status.installed()) {
            try { facts = parseFacts(Module.diagnosticFacts()); }
            catch (Exception ignored) { }
            if (status.state.equals("running") && status.control) {
                try { mode = Controller.mode(); }
                catch (Exception ignored) { }
            }
        }
        NetworkResults checked = checkNetwork(networkWorker);
        if (mode != null) {
            try { outlet = Controller.testPrimaryOutlet(mode); }
            catch (Exception ignored) { }
        }
        return format(appVersion, appCode, androidApi, status, facts, mode, outlet, checked.network(),
            subscriptionFailure == null ? null : subscriptionFailure.kind, checked.ipv4(), checked.ipv6());
    }

    static DiagnosticReport format(String appVersion, long appCode, int androidApi, Module.Status status,
                                   Set<Fact> facts, Controller.Mode mode, Controller.OutletProbe outlet,
                                   Map<NetworkCheck, NetworkCheck.Result> network, Subscription.NetworkFailure.Kind subscription) {
        return format(appVersion, appCode, androidApi, status, facts, mode, outlet, network, subscription, null, null);
    }

    static DiagnosticReport format(String appVersion, long appCode, int androidApi, Module.Status status,
                                   Set<Fact> facts, Controller.Mode mode, Controller.OutletProbe outlet,
                                   Map<NetworkCheck, NetworkCheck.Result> network, Subscription.NetworkFailure.Kind subscription,
                                   IpFamilyCheck.Result ipv4, IpFamilyCheck.Result ipv6) {
        StringBuilder report = new StringBuilder("MinClash 诊断记录 / MC-D3\n")
            .append("App  ").append(safeVersion(appVersion)).append(" (").append(appCode).append(")\n")
            .append("Android API  ").append(androidApi).append('\n')
            .append("优先排查  ").append(finding(status, facts, mode, outlet, network)).append('\n');

        report.append("\n[TUN] 模块与路由\n");
        if (status == null) {
            report.append("ROOT_OR_STATUS_UNAVAILABLE  无法读取 Root 模块状态\n");
        } else if (!status.installed()) {
            report.append("MODULE_MISSING  模块未生效\n");
        } else {
            report.append("模块  ").append(safeVersion(status.version)).append(" / 修订 ").append(status.functionalRevision()).append('\n')
                .append("启用  ").append(status.enabled ? "是" : "否").append(" · 配置  ").append(status.profile ? "已导入" : "无").append('\n')
                .append("状态  ").append(status.phase().name()).append('\n');
        }
        report.append("TUN 接口  ").append(factResult(facts, Fact.IFACE)).append('\n')
            .append("IPv4 路由与规则  ").append(factResult(facts, Fact.ROUTE4)).append('\n')
            .append("IPv6 路由与规则  ").append(factResult(facts, Fact.ROUTE6)).append('\n')
            .append("控制接口  ").append(mode != null ? "OK" : status != null && status.state.equals("running")
                ? "CONTROL_UNAVAILABLE" : "SKIPPED").append('\n');

        report.append("\n[OUTLET] 主代理\n")
            .append("模式  ").append(mode == null ? "UNKNOWN" : mode.name()).append('\n')
            .append("出口探测  ").append(outlet == null ? "SKIPPED" : outlet.state().name());
        if (outlet != null && outlet.state() == Controller.OutletState.HTTPS_OK) report.append(" · ").append(outlet.delay()).append(" ms");
        report.append("\n\n[HTTPS] 手机实际网络路径\n");
        for (NetworkCheck target : NetworkCheck.values()) {
            NetworkCheck.Result result = network.get(target);
            report.append(target.label).append("  ").append(result == null ? "NO_RESULT"
                : result.state().name() + " · " + result.display()).append('\n');
        }
        if (subscription != null) report.append("订阅下载  SUB_").append(subscription.name()).append('\n');

        report.append("\n[APP] MinClash 自身联网\n")
            .append(facts == null ? "APP_CHECK_SKIPPED"
                : facts.contains(Fact.APP_REJECT_RULE) ? "APP_REJECT_RULE_FOUND  检测到系统对本 App 的拒绝联网规则，请在 MinClash 应用信息中核对 Wi-Fi／移动数据联网设置。"
                : "NO_KNOWN_APP_REJECT_RULE  未发现已知厂商拒绝规则。")
            .append('\n')
            .append("Root 内核与 App 使用不同 UID；内核能代理，不代表 App 可以下载订阅或检查更新。\n");

        report.append("\n[FAMILY] 上游路由与固定地址 HTTPS\n")
            .append("IPv4 上游默认路由  ").append(factResult(facts, Fact.UPLINK4)).append('\n')
            .append("IPv6 上游默认路由  ").append(factResult(facts, Fact.UPLINK6)).append('\n')
            .append("IPv4 目标  ").append(familyResult(ipv4)).append('\n')
            .append("IPv6 目标  ").append(familyResult(ipv6)).append('\n')
            .append("固定 Cloudflare 双栈目标，验证 TLS 证书；请求经过当前 TUN 与分流。\n")
            .append("路由存在不等于外网可用；代理也可能通过 IPv4 上游访问 IPv6 目标。单个站点失败不能代表整个地址族不可用。\n");
        if (ipv4 != null && ipv4.state() == IpFamilyCheck.State.REACHABLE
            && ipv6 != null && ipv6.state() != IpFamilyCheck.State.REACHABLE) {
            report.append("IPV6_PATH_UNCONFIRMED  IPv4 目标可达，IPv6 目标未通过；对照 IPv6 上游、代理节点支持情况，换节点或网络后重测。\n");
        }
        report.append("\n[BACKGROUND] 应用恢复\n")
            .append("BACKGROUND_NOT_CHECKED  本报告不判断其他应用是否被冻结或清理。\n")
            .append("TUN／保护规则就绪、节点测速正常，均不能证明目标应用后台连接或消息送达正常。\n")
            .append("仅返回应用时恢复慢：先检查目标应用后台与电池限制；持续前台也失败：对照出口、分流和以上 HTTPS 结果。\n")
            .append("可在设置打开「后台连接恢复指南」；本次检查不更改后台权限、不解除系统冻结。\n");

        report.append("\n[CORE] 最近日志信号\n");
        if (facts == null || !facts.contains(Fact.LOG_SAMPLE)) report.append("LOG_UNAVAILABLE\n");
        else {
            boolean found = false;
            for (Fact fact : EnumSet.range(Fact.NODE_DNS, Fact.CONFIG)) {
                if (!facts.contains(fact)) continue;
                report.append(fact.name()).append('\n');
                found = true;
            }
            if (!found) report.append("NO_KNOWN_SIGNAL\n");
        }
        return new DiagnosticReport(report.toString().trim());
    }

    private static String familyResult(IpFamilyCheck.Result result) {
        return result == null ? "SKIPPED" : result.state().name()
            + (result.state() == IpFamilyCheck.State.REACHABLE ? " · " + result.latencyMs() + " ms" : "");
    }

    static EnumSet<Fact> parseFacts(String output) {
        EnumSet<Fact> facts = EnumSet.noneOf(Fact.class);
        for (String line : output.split("\\r?\\n")) {
            for (Fact fact : Fact.values()) if (fact.name().equals(line)) facts.add(fact);
        }
        return facts;
    }

    private static Module.Status readStatus() {
        try { return Module.status(); }
        catch (Exception error) { return null; }
    }

    private static String safeVersion(String version) {
        return version.matches("v?[0-9]{1,6}(\\.[0-9]{1,6}){1,3}") ? version : "未知";
    }

    private static String factResult(Set<Fact> facts, Fact fact) {
        return facts == null ? "SKIPPED" : facts.contains(fact) ? "OK" : "NOT_FOUND";
    }

    private static String finding(Module.Status status, Set<Fact> facts, Controller.Mode mode,
                                  Controller.OutletProbe outlet, Map<NetworkCheck, NetworkCheck.Result> network) {
        if (status == null) return "ROOT_OR_STATUS_UNAVAILABLE · 检查 Root 授权和模块状态";
        if (!status.installed() || !status.enabled) return "MODULE_INACTIVE · 检查模块是否安装并启用";
        if (!status.profile) return "NO_PROFILE · 导入有效配置";
        if (!status.state.equals("running")) return "TUN_INACTIVE · 查看模块状态及 CORE 信号";
        if (facts != null && (!facts.contains(Fact.IFACE) || !facts.contains(Fact.ROUTE4))) {
            return "TUN_ROUTE_MISSING · 检查 TUN 接口与 IPv4 路由";
        }
        if (mode == null) return "CONTROL_UNAVAILABLE · 检查 Mihomo 控制接口";
        if (facts != null && facts.contains(Fact.APP_REJECT_RULE)) {
            return "APP_REJECT_RULE_FOUND · 核对系统是否允许 MinClash 使用 Wi-Fi／移动数据";
        }
        if (mode == Controller.Mode.DIRECT) return "DIRECT_MODE · 当前模式为直连";
        if (outlet != null && outlet.state() == Controller.OutletState.HTTPS_FAILED) {
            if (facts != null && facts.contains(Fact.NODE_DNS)) return "NODE_DNS · 内核日志样本出现解析失败";
            return "OUTLET_FAILED · 主代理出口探测失败";
        }
        if (outlet != null && (outlet.state() == Controller.OutletState.DIRECT || outlet.state() == Controller.OutletState.REJECTED)) {
            return "OUTLET_POLICY · 主代理组选中了直连或拒绝出口";
        }
        if (reachable(network, NetworkCheck.GOOGLE) || reachable(network, NetworkCheck.CLOUDFLARE)) {
            return "HTTPS_OK · 至少一个境外 HTTPS 探测通过";
        }
        if (outlet != null && outlet.state() == Controller.OutletState.HTTPS_OK) {
            return "PATH_MISMATCH · 主代理可达，请检查规则、应用分流和 TUN 路径";
        }
        return "HTTPS_NOT_CONFIRMED · 对照出口探测与各站点结果";
    }

    private static boolean reachable(Map<NetworkCheck, NetworkCheck.Result> network, NetworkCheck target) {
        NetworkCheck.Result result = network.get(target);
        return result != null && result.state() == NetworkCheck.State.REACHABLE;
    }

    private record NetworkResults(Map<NetworkCheck, NetworkCheck.Result> network, IpFamilyCheck.Result ipv4, IpFamilyCheck.Result ipv6) {}

    private static NetworkResults checkNetwork(ExecutorService networkWorker) throws InterruptedException {
        NetworkCheck[] targets = NetworkCheck.values();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (NetworkCheck target : targets) tasks.add(target::runOnce);
        tasks.add(() -> IpFamilyCheck.run(false));
        tasks.add(() -> IpFamilyCheck.run(true));
        List<Future<Object>> results = networkWorker.invokeAll(tasks, 22, TimeUnit.SECONDS);
        Map<NetworkCheck, NetworkCheck.Result> output = new EnumMap<>(NetworkCheck.class);
        for (int i = 0; i < targets.length; i++) {
            if (results.get(i).isCancelled()) continue;
            try { output.put(targets[i], (NetworkCheck.Result) results.get(i).get()); }
            catch (ExecutionException ignored) { }
        }
        IpFamilyCheck.Result[] families = new IpFamilyCheck.Result[2];
        for (int i = 0; i < 2; i++) {
            Future<Object> result = results.get(targets.length + i);
            if (!result.isCancelled()) try { families[i] = (IpFamilyCheck.Result) result.get(); }
            catch (ExecutionException ignored) { }
        }
        return new NetworkResults(output, families[0], families[1]);
    }
}
