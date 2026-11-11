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
        IFACE, ROUTE4, ROUTE6, LOG_SAMPLE,
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
        Map<NetworkCheck, NetworkCheck.Result> network = checkNetwork(networkWorker);
        if (mode != null) {
            try { outlet = Controller.testPrimaryOutlet(mode); }
            catch (Exception ignored) { }
        }
        return format(appVersion, appCode, androidApi, status, facts, mode, outlet, network,
            subscriptionFailure == null ? null : subscriptionFailure.kind);
    }

    static DiagnosticReport format(String appVersion, long appCode, int androidApi, Module.Status status,
                                   Set<Fact> facts, Controller.Mode mode, Controller.OutletProbe outlet,
                                   Map<NetworkCheck, NetworkCheck.Result> network, Subscription.NetworkFailure.Kind subscription) {
        StringBuilder report = new StringBuilder("MinClash 诊断记录 / MC-D2\n")
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

    private static Map<NetworkCheck, NetworkCheck.Result> checkNetwork(ExecutorService networkWorker) throws InterruptedException {
        NetworkCheck[] targets = NetworkCheck.values();
        List<Callable<NetworkCheck.Result>> tasks = new ArrayList<>();
        for (NetworkCheck target : targets) tasks.add(target::runOnce);
        List<Future<NetworkCheck.Result>> results = networkWorker.invokeAll(tasks, 15, TimeUnit.SECONDS);
        Map<NetworkCheck, NetworkCheck.Result> output = new EnumMap<>(NetworkCheck.class);
        for (int i = 0; i < targets.length; i++) {
            if (results.get(i).isCancelled()) continue;
            try { output.put(targets[i], results.get(i).get()); }
            catch (ExecutionException ignored) { }
        }
        return output;
    }
}
