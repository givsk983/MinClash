package io.minclash.app;

import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ServiceCheckActivity extends ThemedActivity {
    private record Route(Controller.Mode mode, String mainNode, int configuration, String session) {}
    private record Reading(ServiceProbe.Result result, String time) {}
    private record ServiceRow(TextView status, Button test) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final List<Future<?>> tasks = new ArrayList<>();
    private final Map<ServiceProbe.Site, ServiceRow> rows = new EnumMap<>(ServiceProbe.Site.class);
    private final Map<ServiceProbe.Site, Reading> results = new EnumMap<>(ServiceProbe.Site.class);
    private final Map<ServiceProbe.Site, Reading> samples = new EnumMap<>(ServiceProbe.Site.class);
    private final EnumSet<ServiceProbe.Site> pending = EnumSet.noneOf(ServiceProbe.Site.class);
    private TextView status;
    private Button testAll;
    private Route resultRoute;
    private int generation;
    private boolean testing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppUi ui = new AppUi(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(AppUi.BG);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(content);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        Button back = ui.button("‹ 返回");
        back.setOnClickListener(view -> finish());
        heading.addView(back);
        TextView title = ui.label("封锁测试", 22, AppUi.TEXT, true);
        title.setAccessibilityHeading(true);
        title.setPaddingRelative(ui.dp(12), 0, 0, 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ui.add(content, heading, 0);

        LinearLayout panel = ui.card();
        status = ui.label("按当前分流测试服务的公开入口。", 13, AppUi.TEXT, false);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.add(panel, status, 0);
        ui.add(panel, ui.label("登录、播放和订阅资格需在对应服务内确认。", 12, AppUi.MUTED, false), 4);
        testAll = ui.button("测试全部");
        testAll.setOnClickListener(view -> {
            if (testing) {
                stop();
                status.setText("已停止测试。");
                render();
            } else start(Arrays.asList(ServiceProbe.Site.values()));
        });
        ui.add(panel, testAll, 10);
        ui.add(content, panel, 12);
        for (ServiceProbe.Site site : ServiceProbe.Site.values()) addService(ui, content, site);
        setContentView(scroll);
        render();
    }

    private void addService(AppUi ui, LinearLayout content, ServiceProbe.Site site) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(ui.dp(14), ui.dp(9), ui.dp(10), ui.dp(9));
        row.setBackground(ui.shape(AppUi.CARD, 14));
        LinearLayout labels = ui.column();
        TextView title = ui.label(site.label, 15, AppUi.TEXT, true);
        ui.add(labels, title, 0);
        TextView message = ui.label("", 12, AppUi.MUTED, false);
        ui.add(labels, message, 3);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Button test = ui.button("测试");
        test.setContentDescription("测试 " + site.label);
        test.setOnClickListener(view -> start(java.util.Collections.singletonList(site)));
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-2, -2);
        button.setMarginStart(ui.dp(8));
        row.addView(test, button);
        rows.put(site, new ServiceRow(message, test));
        ui.add(content, row, 8);
    }

    private void start(List<ServiceProbe.Site> sites) {
        if (testing) return;
        int run = ++generation;
        testing = true;
        pending.addAll(sites);
        status.setText("正在检查 TUN…");
        render();
        tasks.add(workers.submit(() -> {
            try {
                Route route = readRoute();
                main.post(() -> {
                    if (current(run)) launch(run, route, sites);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (current(run)) finishRun("TUN 或控制接口未就绪，请连接后重试。");
                });
            }
        }));
    }

    private static Route readRoute() throws Exception {
        Module.Status module = Module.status();
        if (!module.running() || !module.control) throw new IOException("TUN 未就绪");
        Controller.Snapshot current = Controller.snapshot();
        String session = module.runtime.trafficSummary() ? Controller.trafficStatistics(false).optString("session") : "";
        return new Route(current.mode(), current.node(), RoutingStore.generation(), session);
    }

    private void launch(int run, Route route, List<ServiceProbe.Site> sites) {
        if (resultRoute != null && !resultRoute.equals(route)) {
            results.clear();
            resultRoute = null;
        }
        status.setText(route.mode().label + "模式 · 已测 0/" + sites.size());
        render();
        for (ServiceProbe.Site site : sites) {
            tasks.add(workers.submit(() -> {
                ServiceProbe.Result result = ServiceProbe.run(site);
                main.post(() -> {
                    if (current(run)) complete(run, route, site, result, sites.size());
                });
            }));
        }
    }

    private void complete(int run, Route route, ServiceProbe.Site site, ServiceProbe.Result result, int total) {
        pending.remove(site);
        samples.put(site, new Reading(result, DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date())));
        status.setText(route.mode().label + "模式 · 已测 " + samples.size() + "/" + total);
        render();
        if (!pending.isEmpty()) return;
        tasks.add(workers.submit(() -> {
            boolean sameRoute;
            try { sameRoute = route.equals(readRoute()); }
            catch (Exception error) { sameRoute = false; }
            boolean confirmed = sameRoute;
            main.post(() -> {
                if (!current(run)) return;
                if (confirmed) {
                    resultRoute = route;
                    results.putAll(samples);
                    finishRun(route.mode().label + "模式 · 已测试 " + total + " 项");
                } else {
                    results.clear();
                    resultRoute = null;
                    finishRun("连接或分流已变化，请重新测试。");
                }
            });
        }));
    }

    private boolean current(int run) { return run == generation && testing && !isDestroyed(); }

    private void finishRun(String message) {
        stop();
        status.setText(message);
        render();
    }

    private void stop() {
        generation++;
        testing = false;
        pending.clear();
        samples.clear();
        for (Future<?> task : tasks) task.cancel(true);
        tasks.clear();
    }

    private void render() {
        testAll.setText(testing ? "停止测试" : "测试全部");
        for (Map.Entry<ServiceProbe.Site, ServiceRow> entry : rows.entrySet()) {
            ServiceProbe.Site site = entry.getKey();
            ServiceRow row = entry.getValue();
            Reading reading = samples.get(site);
            if (reading == null) reading = results.get(site);
            if (pending.contains(site)) {
                row.status().setText("测试中…");
                row.status().setTextColor(AppUi.MUTED);
            } else if (reading == null) {
                row.status().setText("未测试");
                row.status().setTextColor(AppUi.MUTED);
            } else {
                row.status().setText(reading.result().summary() + " · " + reading.time());
                row.status().setTextColor(switch (reading.result().state()) {
                    case REACHABLE -> AppUi.MINT;
                    case RESTRICTED, CONNECTION_FAILED -> AppUi.RED;
                    case SIGN_IN, CHALLENGE, RATE_LIMITED -> AppUi.AMBER;
                    case UNCONFIRMED -> AppUi.MUTED;
                });
            }
            row.test().setText(reading == null ? "测试" : "重测");
            row.test().setEnabled(!testing);
        }
    }

    @Override protected void onDestroy() {
        stop();
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
