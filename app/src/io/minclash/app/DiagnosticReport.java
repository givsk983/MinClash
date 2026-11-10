package io.minclash.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

record DiagnosticReport(String text) {
    static DiagnosticReport collect(String appVersion, int androidApi, Subscription.NetworkFailure subscriptionFailure,
                                    ExecutorService networkWorker)
        throws InterruptedException {
        StringBuilder report = new StringBuilder("MinClash 诊断报告\n")
            .append("App 版本  ").append(appVersion).append('\n')
            .append("Android API  ").append(androidApi).append('\n');

        Module.Status status = readStatus();
        if (status == null) {
            report.append("模块状态  无法读取，请检查 Root 授权\n");
        } else if (!status.installed()) {
            report.append("模块状态  未安装或尚未生效\n");
        } else {
            report.append("模块版本  ").append(safeVersion(status.version)).append('\n')
                .append("模块修订号  ").append(status.functionalRevision()).append('\n')
                .append("模块状态  ").append(status.enabled ? "已启用" : "已禁用").append('\n')
                .append("配置  ").append(status.profile ? "已导入" : "尚未导入").append('\n')
                .append("TUN  ").append(tunState(status.state)).append('\n');
            if (status.state.equals("running") && status.control) report.append("代理模式  ").append(readMode()).append('\n');
        }

        if (subscriptionFailure != null) report.append("订阅下载  ").append(subscriptionFailure.reason).append('\n');
        appendNetworkChecks(report, networkWorker);
        if (status != null && status.state.equals("running") && status.control) {
            String outlet;
            try { outlet = Controller.testPrimaryOutlet(Controller.mode()); }
            catch (Exception error) { outlet = "无法测试"; }
            report.append("主代理出口  ").append(outlet).append('\n');
        }
        if (status != null && status.installed() && status.profile) appendProxyDns(report);
        return new DiagnosticReport(report.toString().trim());
    }

    private static Module.Status readStatus() {
        try { return Module.status(); }
        catch (Exception error) { return null; }
    }

    private static String safeVersion(String version) {
        return version.matches("v?[0-9]{1,6}(\\.[0-9]{1,6}){1,3}") ? version : "未知";
    }

    private static String tunState(String state) {
        return switch (state) {
            case "running" -> "运行中";
            case "starting" -> "正在启动";
            case "retrying" -> "等待重试";
            case "orphaned" -> "需要清理";
            default -> "已停止";
        };
    }

    private static String readMode() {
        try { return Controller.mode().label; }
        catch (Exception error) { return "无法读取"; }
    }

    private static void appendNetworkChecks(StringBuilder report, ExecutorService networkWorker) throws InterruptedException {
        NetworkCheck[] targets = NetworkCheck.values();
        List<Callable<NetworkCheck.Result>> tasks = new ArrayList<>();
        for (NetworkCheck target : targets) tasks.add(target::run);
        List<Future<NetworkCheck.Result>> results = networkWorker.invokeAll(tasks, 20, TimeUnit.SECONDS);
        report.append("\nHTTPS 连通性（当前网络路径）\n");
        for (int i = 0; i < targets.length; i++) {
            Future<NetworkCheck.Result> result = results.get(i);
            String outcome = "未在诊断时间内完成";
            if (!result.isCancelled()) {
                try { outcome = result.get().display(); }
                catch (ExecutionException error) { outcome = "无法检测"; }
            }
            report.append(targets[i].label).append("  ").append(outcome).append('\n');
        }
    }

    private static void appendProxyDns(StringBuilder report) {
        report.append("\n节点 DNS\n");
        boolean loopback = false;
        try {
            loopback = Profile.usesLoopbackProxyDns(Module.readProfile());
            report.append(loopback ? "配置包含指向本机的节点 DNS\n" : "配置中未发现指向本机的节点 DNS\n");
        } catch (Exception error) {
            report.append("无法读取节点 DNS 配置\n");
        }
        boolean resolutionError = false;
        try {
            String logs = Module.run(Module.Command.LOGS, null);
            resolutionError = logs.contains("connect error: dns resolve failed");
            report.append(resolutionError ? "日志样本中出现节点域名解析失败\n" : "日志样本中未发现节点域名解析失败\n");
        } catch (Exception error) {
            report.append("无法读取内核日志样本\n");
        }
        if (loopback && resolutionError) {
            report.append("请确认手机上的本机 DNS 服务是否可用，或向订阅提供方索取适用于 Android 的配置。\n");
        }
    }
}
