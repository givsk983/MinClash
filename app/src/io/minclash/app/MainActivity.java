package io.minclash.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_PROFILE = 1;
    private static final int EXPORT_MODULE = 2;
    private static final int REQUEST_APP_LIST = 3;
    private static final int APP_LIST_SETTINGS = 4;
    private static final int REQUEST_NOTIFICATIONS = 5;
    private static final int UPDATE_INSTALL_PERMISSION = 6;
    private static final int EXPORT_BACKUP = 7;
    private static final int IMPORT_BACKUP = 8;
    static final String NOTIFICATION_CONTROL = "notification_control";
    private static final String ROOT_INVENTORY = "root_app_inventory";
    private static final int BG = 0xff0d131a;
    private static final int CARD = 0xff17212b;
    private static final int BORDER = 0xff293641;
    private static final int TEXT = 0xffedf4f7;
    private static final int MUTED = 0xff9baebc;
    private static final int MINT = 0xff79e7c9;
    private static final int AMBER = 0xfff5ca88;

    private static final ExecutorService worker = RoutingStore.WORKER;
    private final ExecutorService networkWorker = Executors.newFixedThreadPool(NetworkCheck.values().length);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollStatus;
    private final Runnable pollLatency = () -> checkNetwork(false);
    private Module.Status status;
    private BundledModule.Info bundledModule = new BundledModule.Info("", 0, 0);
    private boolean busy;
    private boolean polling;
    private boolean visible;
    private NetworkObserver networkObserver;
    private boolean updatingSwitch;
    private TextView stateText;
    private TextView detailText;
    private TextView messageText;
    private Button toggle;
    private Button importButton;
    private Button subscriptionButton;
    private Button updateButton;
    private Button checkAppUpdateButton;
    private Button repairDnsButton;
    private Switch autoUpdateSwitch;
    private TextView autoUpdateSummary;
    private AppUpdate.Release pendingUpdate;
    private BackgroundWork background;
    private AlertDialog backgroundDialog;
    private AlertDialog policyDialog;
    private AlertDialog backupPasswordDialog;
    private AlertDialog managementDialog;
    private boolean resumed;
    private TextView subscriptionSummary;
    private SubscriptionCatalog subscriptionCatalog;
    private SubscriptionCatalog.Snapshot subscriptions;
    private Button modeButton;
    private Button groupsButton;
    private record ProxySummary(Controller.Mode mode, String node) {}
    private ProxySummary proxySummary;
    private Button routingButton;
    private Button personalRulesButton;
    private Button connectionsButton;
    private record RulesScreen(RoutingStore.State state, int generation, List<String> targets) {}
    private String routingFilter = AppCategories.APPS;
    private String routingQuery = "";
    private final AppRouting.Loader routingLoader = new AppRouting.Loader();
    private record RoutingSnapshot(AppRouting.Screen screen, int epoch, int version, long time) {}
    private record RoutingRead(boolean root, int epoch, CompletableFuture<RoutingSnapshot> result) {}
    private RoutingSnapshot routingSnapshot;
    private RoutingRead routingRead;
    private int routingEpoch;
    private long lastRoutingAttempt = -30000;
    private AppIconLoader routingIcons;
    private TextView proxyHint;
    private Button refreshButton;
    private Button logsButton;
    private Button diagnosticsButton;
    private Switch bootSwitch;
    private Switch hotspotSwitch;
    private TextView hotspotSummary;
    private Button notificationButton;
    private Button backgroundPermissionButton;
    private Button overlayPermissionButton;
    private Button foregroundAppButton;
    private Button launcherIconButton;
    private Button backupExportButton;
    private Button backupImportButton;
    private ImageView aboutLogo;
    private Button installButton;
    private Button exportButton;
    private TextView moduleSummary;
    private TextView profileSummary;
    private TextView statusBadge;
    private TextView setupHint;
    private View networkCheckArea;
    private final TextView[] networkResults = new TextView[NetworkCheck.values().length];
    private int networkChecksPending;
    private volatile int networkCheckGeneration;
    private boolean googleReachable;
    private boolean cloudflareReachable;
    private boolean nodeLatencyAvailable;
    private boolean confirmNextLatency = true;

    private TextView networkWarning;
    private final View[] pages = new View[4];
    private final Button[] navigation = new Button[4];
    private int selectedPage;

    private SharedPreferences appPreferences() {
        // Keep the original Activity preference file across all launcher aliases.
        return getSharedPreferences("MainActivity", MODE_PRIVATE);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Object retained = getLastNonConfigurationInstance();
        background = retained instanceof BackgroundWork work ? work : new BackgroundWork();
        busy = background.backupRestoring;
        routingIcons = new AppIconLoader(this);
        subscriptionCatalog = new SubscriptionCatalog(appPreferences(), new RoutingStore(this));
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });

        setContentView(root);
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout overview = page(content, 0);
        header(overview, "MinClash", "连接概览");

        LinearLayout dashboard = card(overview, 22);
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(label("连接状态", 13, MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        statusBadge = label("待检测", 11, MINT, true);
        statusBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusBadge.setBackground(shape(0xff203a35, 10, false));
        statusRow.addView(statusBadge);
        add(dashboard, statusRow, 0);
        stateText = label("准备连接", 28, TEXT, true);
        add(dashboard, stateText, 18);
        detailText = label("先检测环境，再导入你的配置。", 13, MUTED, false);
        add(dashboard, detailText, 6);
        toggle = button("启动 TUN", true);
        toggle.setOnClickListener(view -> {
            if (status == null) { refresh(); return; }
            if (!status.installed()) { selectPage(2); return; }
            boolean stop = isActive();
            execute("", true, () -> {
                Module.run(stop ? Module.Command.STOP : Module.Command.START, null);
                return Module.status();
            });
        });
        add(dashboard, toggle, 22);
        TextView backgroundHint = label("退出 App 后，连接仍会保持", 11, MUTED, false);
        backgroundHint.setGravity(Gravity.CENTER);
        add(dashboard, backgroundHint, 12);

        setupHint = label("", 13, AMBER, false);
        setupHint.setPadding(dp(14), dp(12), dp(14), dp(12));
        setupHint.setBackground(shape(0xff302b22, 14, false));
        setupHint.setOnClickListener(view -> selectPage(2));
        add(overview, setupHint, 12);
        addNetworkCheckCard(overview);
        section(overview, "代理控制");
        modeButton = button("代理模式", false);
        modeButton.setOnClickListener(view -> execute("", Controller::mode));
        groupsButton = button("代理组与节点", false);
        groupsButton.setOnClickListener(view -> execute("", Controller::groups));
        for (Button control : new Button[]{modeButton, groupsButton}) {
            control.setLines(2);
            control.setLineSpacing(dp(5), 1f);
            control.setEllipsize(TextUtils.TruncateAt.END);
        }
        buttonRow(overview, modeButton, groupsButton, 10);
        connectionsButton = button("连接管理", false);
        connectionsButton.setOnClickListener(view -> startActivity(new Intent(this, ConnectionsActivity.class)));
        add(overview, connectionsButton, 10);
        proxyHint = label("", 13, MUTED, false);
        add(overview, proxyHint, 10);

        LinearLayout current = card(overview, 22);
        add(current, label("当前配置", 15, TEXT, true), 0);
        profileSummary = label("尚未导入配置", 13, MUTED, false);
        add(current, profileSummary, 6);
        Button manage = button("管理配置  →", false);
        manage.setOnClickListener(view -> selectPage(1));
        add(current, manage, 14);

        LinearLayout profiles = page(content, 1);
        header(profiles, "配置", "管理订阅与本地文件");
        LinearLayout settings = card(profiles, 22);
        add(settings, label("订阅与配置", 17, TEXT, true), 0);
        subscriptionSummary = label("正在读取订阅…", 13, MUTED, false);
        add(settings, subscriptionSummary, 8);
        subscriptionButton = button("管理订阅", true);
        subscriptionButton.setOnClickListener(view -> showSubscriptions());
        add(settings, subscriptionButton, 20);
        updateButton = button("更新当前订阅", false);
        updateButton.setOnClickListener(view -> {
            if (subscriptions != null) activateSubscription(subscriptions.active());
        });
        add(settings, updateButton, 10);
        importButton = button("导入 Mihomo YAML", false);
        importButton.setOnClickListener(view -> pickProfile());
        add(settings, importButton, 10);
        autoUpdateSwitch = new Switch(this);
        autoUpdateSwitch.setText("自动更新当前订阅");
        autoUpdateSwitch.setTextColor(TEXT);
        autoUpdateSwitch.setChecked(appPreferences().getBoolean(SubscriptionUpdateJob.ENABLED, false));
        autoUpdateSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled == appPreferences().getBoolean(SubscriptionUpdateJob.ENABLED, false)) return;
            appPreferences().edit().putBoolean(SubscriptionUpdateJob.ENABLED, enabled).apply();
            if (!SubscriptionUpdateJob.schedule(this, enabled)) {
                appPreferences().edit().putBoolean(SubscriptionUpdateJob.ENABLED, false).apply();
                autoUpdateSwitch.setChecked(false);
                showMessage("Android 无法安排订阅自动更新，请重试。");
            }
            autoUpdateSummary.setText(SubscriptionUpdateJob.summary(this, appPreferences()));
        });
        add(settings, autoUpdateSwitch, 12);
        autoUpdateSummary = label(SubscriptionUpdateJob.summary(this, appPreferences()), 12, MUTED, false);
        add(settings, autoUpdateSummary, 6);
        add(settings, label("运行中切换或更新订阅会短暂重连。导入本地文件前请先停止 TUN。", 12, MUTED, false), 14);
        LinearLayout routing = card(profiles, 14);
        add(routing, label("应用分流", 17, TEXT, true), 0);
        add(routing, label("给应用指定直连或代理组。你的设置优先于机场规则，更新订阅后继续保留。", 13, MUTED, false), 8);
        routingButton = button("管理应用分流", false);
        routingButton.setOnClickListener(view -> openAppRouting());
        add(routing, routingButton, 16);
        personalRulesButton = button("自定义域名／IP 分流", false);
        personalRulesButton.setOnClickListener(view -> execute("", this::readPersonalRules));
        add(routing, personalRulesButton, 10);
        add(routing, label("在规则模式下生效。运行中保存会短暂重连。", 12, MUTED, false), 12);
        LinearLayout instructions = card(profiles, 14);
        add(instructions, label("开始使用", 15, TEXT, true), 0);
        add(instructions, label("01   在设置页安装内置模块并重启\n02   导入订阅链接或本地配置\n03   回到概览，启动 TUN", 13, MUTED, false), 12);

        LinearLayout preferences = page(content, 2);
        header(preferences, "设置", "服务与应用管理");
        LinearLayout service = card(preferences, 22);

        bootSwitch = new Switch(this);
        bootSwitch.setText("开机自动启动");
        bootSwitch.setTextColor(TEXT);
        bootSwitch.setTextSize(16);
        bootSwitch.setMinHeight(dp(48));
        bootSwitch.setButtonTintList(ColorStateList.valueOf(MINT));
        bootSwitch.setThumbTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
            new int[] {MINT, MUTED}));
        bootSwitch.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
            new int[] {0xff325f54, BORDER}));
        bootSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitch) return;
            execute(checked ? "已开启开机自启。" : "已关闭开机自启。", () -> {
                Module.run(checked ? Module.Command.BOOT_ON : Module.Command.BOOT_OFF, null);
                return Module.status();
            });
        });
        add(service, bootSwitch, 0);
        hotspotSwitch = new Switch(this);
        hotspotSwitch.setText("个人热点共享代理");
        hotspotSwitch.setTextColor(TEXT);
        hotspotSwitch.setTextSize(16);
        hotspotSwitch.setMinHeight(dp(48));
        hotspotSwitch.setThumbTintList(bootSwitch.getThumbTintList());
        hotspotSwitch.setTrackTintList(bootSwitch.getTrackTintList());
        hotspotSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitch) return;
            execute(checked ? "已开启热点共享代理，请重新开启系统热点；若下方提示需重启手机，请重启后使用。" : "已关闭热点共享代理。", () -> {
                Module.run(checked ? Module.Command.HOTSPOT_ON : Module.Command.HOTSPOT_OFF, null);
                return Module.status();
            });
        });
        add(service, hotspotSwitch, 10);
        hotspotSummary = label("", 12, MUTED, false);
        add(service, hotspotSummary, 6);
        notificationButton = button("开启通知栏控制", false);
        notificationButton.setOnClickListener(view -> toggleNotificationControl());
        add(service, notificationButton, 14);
        add(service, label("通知显示连接和网速，点模式或节点名可切换。若划掉后台后通知消失，请检查后台运行设置。", 12, MUTED, false), 6);
        backgroundPermissionButton = button("后台运行设置", false);
        backgroundPermissionButton.setOnClickListener(view -> showBackgroundRunHelp());
        add(service, backgroundPermissionButton, 8);
        overlayPermissionButton = button("允许悬浮窗", false);
        overlayPermissionButton.setOnClickListener(view -> showOverlayPermissionHelp(true));
        add(service, overlayPermissionButton, 8);
        foregroundAppButton = button("授权显示当前应用", false);
        foregroundAppButton.setOnClickListener(view -> {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
            catch (android.content.ActivityNotFoundException error) { showMessage("此设备没有使用情况访问的设置入口。"); }
        });
        add(service, foregroundAppButton, 8);
        add(service, label("手机启动后自动连接，需先导入有效配置。", 12, MUTED, false), 6);

        LinearLayout appearance = card(preferences, 14);
        add(appearance, label("外观", 17, TEXT, true), 0);
        launcherIconButton = button("", false);
        launcherIconButton.setOnClickListener(view -> showLauncherIcons());
        add(appearance, launcherIconButton, 10);

        LinearLayout backups = card(preferences, 14);
        add(backups, label("备份与恢复", 17, TEXT, true), 0);
        add(backups, label("备份配置、订阅、应用分流、自定义分流、分类、节点收藏及偏好。支持密码加密，恢复前可预览。", 13, MUTED, false), 8);
        backupExportButton = button("导出备份", false);
        backupExportButton.setOnClickListener(view -> showBackupExport());
        backupImportButton = button("从备份恢复", false);
        backupImportButton.setOnClickListener(view -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(picker, IMPORT_BACKUP);
        });
        buttonRow(backups, backupExportButton, backupImportButton, 14);
        add(backups, label("恢复会替换备份范围内的数据，请先停止 TUN。系统授权、通知、图标、开机与热点设置保留本机选择。", 12, MUTED, false), 10);

        LinearLayout moduleCard = card(preferences, 14);
        add(moduleCard, label("内置模块", 17, TEXT, true), 0);
        moduleSummary = label("", 13, MUTED, false);
        add(moduleCard, moduleSummary, 8);
        installButton = button("安装内置模块", true);
        installButton.setOnClickListener(view -> confirmInstall());
        add(moduleCard, installButton, 18);
        exportButton = button("导出模块安装包", false);
        exportButton.setOnClickListener(view -> exportModule());
        add(moduleCard, exportButton, 10);

        LinearLayout tools = card(preferences, 14);
        add(tools, label("诊断", 17, TEXT, true), 0);
        refreshButton = button("检测环境", false);
        refreshButton.setOnClickListener(view -> refresh());
        logsButton = button("内核日志", false);
        logsButton.setOnClickListener(view -> execute("", () -> Module.run(Module.Command.LOGS, null)));
        buttonRow(tools, refreshButton, logsButton, 14);
        TextView about = label("MinClash " + appVersion() + "  ·  Mihomo v1.19.33\nKernelSU / Magisk · ARM64", 11, MUTED, false);
        about.setGravity(Gravity.CENTER);
        add(preferences, about, 24);

        LinearLayout information = page(content, 3);
        header(information, "关于", "软件信息与项目动态");
        LinearLayout identity = card(information, 22);
        identity.setGravity(Gravity.CENTER_HORIZONTAL);
        aboutLogo = new ImageView(this);
        aboutLogo.setBackground(shape(CARD, 16, false));
        aboutLogo.setClipToOutline(true);
        aboutLogo.setContentDescription("MinClash 图标");
        identity.addView(aboutLogo, new LinearLayout.LayoutParams(dp(60), dp(60)));
        updateLauncherIcon();
        TextView applicationName = label("MinClash", 24, TEXT, true);
        applicationName.setGravity(Gravity.CENTER);
        add(identity, applicationName, 16);
        TextView applicationVersion = label("版本 " + appVersion(), 12, MINT, true);
        applicationVersion.setGravity(Gravity.CENTER);
        add(identity, applicationVersion, 6);
        TextView description = label("轻量的 Android Root TUN 工具\n管理订阅、代理模式与节点连接", 13, MUTED, false);
        description.setGravity(Gravity.CENTER);
        add(identity, description, 14);
        checkAppUpdateButton = button("检查更新", false);
        checkAppUpdateButton.setOnClickListener(view -> {
            if (UpdateInstallReceiver.installing) return;
            String version = appVersion();
            if (background.updateCheck.start(BackgroundWork.DOWNLOADS, () -> AppUpdate.check(version))) {
                showMessage("正在检查 App 更新…");
                render();
            }
        });
        add(identity, checkAppUpdateButton, 14);

        LinearLayout diagnostics = card(information, 14);
        add(diagnostics, label("诊断与反馈", 17, TEXT, true), 0);
        add(diagnostics, label("检查 TUN 路由、代理出口与 HTTPS。分享版只包含必要的版本、探测结果和固定故障码。",
            13, MUTED, false), 8);
        diagnosticsButton = button("生成诊断报告", false);
        diagnosticsButton.setOnClickListener(view -> runDiagnostics(null));
        add(diagnostics, diagnosticsButton, 14);
        repairDnsButton = button("修复本机 DNS 配置", false);
        repairDnsButton.setOnClickListener(view -> new AlertDialog.Builder(this)
            .setTitle("修复本机 DNS 配置")
            .setMessage("如果配置的 DNS 上游全部指向本机，将改用直连的公共加密 DNS。订阅、节点和分流规则会保留，运行中的 TUN 会短暂重连。")
            .setNegativeButton("取消", null).setPositiveButton("修复", (dialog, which) ->
                execute("DNS 配置已修复。", true, () -> {
                    if (!new RoutingStore(this).repairDns()) throw new IOException("当前配置没有需要修复的纯本机 DNS 上游。");
                    return Module.status();
                })).show());
        add(diagnostics, repairDnsButton, 10);

        LinearLayout links = card(information, 14);
        add(links, label("项目与社区", 17, TEXT, true), 0);
        Button repository = button("GitHub 仓库  ↗", false);
        repository.setOnClickListener(view -> openLink("https://github.com/givsk983/MinClash"));
        add(links, repository, 16);
        add(links, label("givsk983 / MinClash\n查看源码、版本发布与问题反馈", 12, MUTED, false), 8);
        Button channel = button("Telegram 频道  ↗", false);
        channel.setOnClickListener(view -> openLink("https://t.me/mbazhuan"));
        add(links, channel, 18);
        add(links, label("@mbazhuan · 关注项目动态", 12, MUTED, false), 8);

        LinearLayout software = card(information, 14);
        add(software, label("软件信息", 17, TEXT, true), 0);
        add(software, label("代理内核    Mihomo v1.19.33\n运行环境    Android 9+ · ARM64\nRoot 支持   KernelSU / Magisk", 13, MUTED, false), 12);

        messageText = label("", 12, AMBER, false);
        messageText.setPadding(dp(18), dp(12), dp(18), dp(12));
        messageText.setBackgroundColor(0xff302b22);
        messageText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(messageText);
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(16), dp(10), dp(16), dp(10));
        bar.setBackgroundColor(CARD);
        String[] titles = {"概览", "配置", "设置", "关于"};
        for (int i = 0; i < navigation.length; i++) {
            final int index = i;
            Button item = button(titles[i], false);
            item.setOnClickListener(view -> selectPage(index));
            navigation[i] = item;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
            if (i > 0) params.leftMargin = dp(8);
            bar.addView(item, params);
        }
        root.addView(bar);
        try { bundledModule = BundledModule.info(this); }
        catch (IOException error) { showMessage("无法读取内置模块信息，现有模块仍可继续运行。"); }
        if (savedInstanceState != null) pendingUpdate = (AppUpdate.Release) savedInstanceState.getSerializable("pendingUpdate");
        if (autoUpdateSwitch.isChecked() && !SubscriptionUpdateJob.schedule(this, true)) {
            appPreferences().edit().putBoolean(SubscriptionUpdateJob.ENABLED, false).apply();
            autoUpdateSwitch.setChecked(false);
            showMessage("Android 无法安排订阅自动更新，请重新开启。");
        }
        selectPage(savedInstanceState == null ? 0 : savedInstanceState.getInt("page", 0));
        render();
        worker.execute(this::refreshSubscriptionCatalog);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", selectedPage);
        state.putSerializable("pendingUpdate", pendingUpdate);
        super.onSaveInstanceState(state);
    }

    @Override public Object onRetainNonConfigurationInstance() { return background; }

    @Override protected void onStart() {
        super.onStart();
        // Permissions and installed packages may have changed while outside the app.
        invalidateAppRouting();
        visible = true;
        networkObserver = new NetworkObserver(this, () -> {
            if (!visible || !appPreferences().getBoolean("connected", false)) return;
            ProxyNotificationService.connectionChanged(this);
            resetNetworkChecks();
            scheduleLatency(1000);
            main.removeCallbacks(poll);
            main.post(poll);
        });
        confirmNextLatency = true;
        if (appPreferences().getBoolean("connected", false)) main.post(poll);
        scheduleLatency(0);
        warmAppRouting();
    }

    private boolean notificationEnabled() {
        return ProxyNotificationService.enabled(this);
    }

    private void toggleNotificationControl() {
        if (notificationEnabled()) {
            if (!ProxyNotificationService.setEnabled(this, false)) {
                showMessage("无法保存通知设置，请重试。");
                return;
            }
            ProxyNotificationService.sync(this, false);
            render();
        } else if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else {
            enableNotificationControl();
        }
    }

    private void enableNotificationControl() {
        if (!ProxyNotificationService.canShow(this)) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        }
        if (!ProxyNotificationService.setEnabled(this, true)) {
            showMessage("无法保存通知设置，请重试。");
            return;
        }
        render();
        if (!Settings.canDrawOverlays(this)) showOverlayPermissionHelp(true);
    }

    private void showOverlayPermissionHelp(boolean mode) {
        boolean allowed = Settings.canDrawOverlays(this);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle(allowed ? "检查悬浮窗权限" : "允许悬浮窗")
            .setMessage(allowed
                ? "Android 当前已允许 MinClash 显示在其他应用上层。如果仍打不开，请在系统设置检查 MinClash 的悬浮窗权限和后台弹出限制，或点「重试」。"
                : "要从通知栏切换模式或节点，请在系统设置中选择 MinClash，开启「显示在其他应用上层」。")
            .setNegativeButton("稍后", null)
            .setPositiveButton(allowed ? "权限设置" : "去授权", (prompt, which) -> openOverlaySettings());
        if (allowed) {
            dialog.setNeutralButton("重试", (prompt, which) -> {
                if (!ProxyNotificationService.requestPanel(this, mode)) {
                    showMessage("请确认 TUN 正在运行且通知栏控制已开启，再从通知重试。");
                }
            });
        }
        dialog.show();
    }

    private void showBackgroundRunHelp() {
        boolean xiaomi = "Xiaomi".equalsIgnoreCase(Build.MANUFACTURER)
            || "Redmi".equalsIgnoreCase(Build.MANUFACTURER);
        AlertDialog.Builder help = new AlertDialog.Builder(this)
            .setTitle("允许通知在后台运行")
            .setMessage(xiaomi
                ? "请为 MinClash 开启「自启动」，并在「应用信息 → 省电策略」选择「无限制」。\n\n"
                    + "部分小米系统会在划掉后台时清理前台服务。允许自启动后，通知可在清理后自动恢复，期间可能短暂消失。"
                : "请在 MinClash 的应用信息中允许后台运行，并检查电池或省电限制。部分系统会在划掉后台时连同前台通知一起清理。")
            .setNegativeButton("关闭", null);
        if (xiaomi) {
            help.setPositiveButton("自启动设置", (dialog, which) -> openBackgroundSettings(true));
            help.setNeutralButton("应用信息", (dialog, which) -> openBackgroundSettings(false));
        } else {
            help.setPositiveButton("应用信息", (dialog, which) -> openBackgroundSettings(false));
        }
        help.show();
    }

    private void openBackgroundSettings(boolean autostart) {
        if (autostart) {
            try {
                startActivity(new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")));
                return;
            } catch (android.content.ActivityNotFoundException | SecurityException unavailable) { }
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            showMessage("请在系统设置中打开 MinClash 的应用信息，允许自启动和后台运行。");
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(ProxyNotificationService.overlaySettings(this));
        } catch (android.content.ActivityNotFoundException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
            catch (android.content.ActivityNotFoundException error) {
                showMessage("请在系统设置的特殊应用权限中允许 MinClash 显示在其他应用上层。");
            }
        }
    }

    @Override protected void onStop() {
        background.stopObserving();
        if (backgroundDialog != null) {
            backgroundDialog.dismiss();
            backgroundDialog = null;
        }
        if (networkObserver != null) { networkObserver.close(); networkObserver = null; }
        visible = false;
        main.removeCallbacks(poll);
        main.removeCallbacks(pollLatency);
        resetNetworkChecks();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        background.observe(main::post, this::deliverBackgroundResults);
        UpdateInstallReceiver.listen(this::onUpdateInstallResult);
        if (appPreferences().getBoolean("connected", false)) main.post(poll);
        render();
        Intent intent = getIntent();
        if (ProxyNotificationService.ACTION_OVERLAY_HELP.equals(intent.getAction())) {
            intent.setAction(null);
            selectPage(2);
            showOverlayPermissionHelp(intent.getBooleanExtra(ProxyNotificationService.EXTRA_MODE_PANEL, true));
        }
        deliverBackgroundResults();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        resumed = false;
        background.stopObserving();
        UpdateInstallReceiver.listen(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (managementDialog != null) managementDialog.dismiss();
        if (policyDialog != null) policyDialog.dismiss();
        if (backupPasswordDialog != null) backupPasswordDialog.dismiss();
        if (backgroundDialog != null) backgroundDialog.dismiss();
        background.stopObserving();
        main.removeCallbacks(poll);
        main.removeCallbacks(pollLatency);
        routingIcons.close();
        networkWorker.shutdownNow();
        super.onDestroy();
    }

    private void addNetworkCheckCard(LinearLayout overview) {
        add(overview, label("连接延迟", 12, MUTED, true), 14);
        FrameLayout checks = new FrameLayout(this);
        checks.setMinimumHeight(dp(48));
        checks.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9), shape(CARD, 14, true), null));
        checks.setFocusable(true);
        checks.setContentDescription("Google 和 Cloudflare 节点延迟，百度直连延迟，点按重新测试");
        checks.setOnClickListener(view -> checkNetwork(true));
        networkCheckArea = checks;

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (NetworkCheck target : NetworkCheck.values()) {
            LinearLayout site = column();
            TextView name = label(target == NetworkCheck.BAIDU ? "百度 · 直连" : target.label, 11, TEXT, true);
            name.setGravity(Gravity.CENTER);
            add(site, name, 0);
            TextView result = label("—", 12, MUTED, true);
            result.setGravity(Gravity.CENTER);
            add(site, result, 4);
            row.addView(site, new LinearLayout.LayoutParams(0, -2, 1));
            networkResults[target.ordinal()] = result;
        }
        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        rowParams.leftMargin = dp(12);
        rowParams.rightMargin = dp(12);
        rowParams.topMargin = dp(8);
        rowParams.bottomMargin = dp(8);
        checks.addView(row, rowParams);
        add(overview, checks, 6);
        networkWarning = label("", 12, AMBER, false);
        networkWarning.setVisibility(View.GONE);
        networkWarning.setOnClickListener(view -> selectPage(3));
        add(overview, networkWarning, 6);
    }

    private void scheduleLatency(long delay) {
        main.removeCallbacks(pollLatency);
        if (visible && selectedPage == 0) main.postDelayed(pollLatency, delay);
    }

    private void checkNetwork(boolean manual) {
        if (!visible || selectedPage != 0 || networkChecksPending != 0) return;
        if (busy) {
            scheduleLatency(2500);
            return;
        }
        if (!hasProxyControl(status)) return;
        main.removeCallbacks(pollLatency);
        int generation = ++networkCheckGeneration;
        networkChecksPending = networkResults.length;
        googleReachable = false;
        cloudflareReachable = false;
        nodeLatencyAvailable = false;
        boolean confirm = !manual && confirmNextLatency;
        if (confirm) confirmNextLatency = false;
        networkCheckArea.setEnabled(false);
        for (NetworkCheck target : NetworkCheck.values()) {
            if (manual || networkResults[target.ordinal()].getText().toString().equals("—")) {
                showNetworkResult(target, "…", MUTED);
            }
        }
        networkWorker.execute(() -> {
            if (generation != networkCheckGeneration) return;
            String node = null;
            try { node = Controller.primaryProxy(); }
            catch (Exception ignored) { }
            String outlet = node;
            main.post(() -> {
                if (isDestroyed() || generation != networkCheckGeneration) return;
                if (!visible || selectedPage != 0) {
                    resetNetworkChecks();
                    return;
                }
                nodeLatencyAvailable = outlet != null;
                for (NetworkCheck target : NetworkCheck.values()) {
                    String exit = target == NetworkCheck.BAIDU ? "DIRECT" : outlet;
                    if (exit == null) finishNetworkCheck(generation, target, -1);
                    else measureNetworkTarget(generation, exit, target, confirm);
                }
            });
        });
    }

    private void measureNetworkTarget(int generation, String outlet, NetworkCheck target, boolean confirm) {
        networkWorker.execute(() -> {
            if (generation != networkCheckGeneration) return;
            int delay;
            try { delay = Controller.testDelay(outlet, target); }
            catch (Exception ignored) { delay = 0; }
            int result = delay;
            main.post(() -> {
                if (isDestroyed() || generation != networkCheckGeneration) return;
                if (!confirm || result > 0 || !visible || selectedPage != 0) {
                    finishNetworkCheck(generation, target, result);
                    return;
                }
                showNetworkResult(target, "复测中…", MUTED);
                main.postDelayed(() -> {
                    if (isDestroyed() || generation != networkCheckGeneration) return;
                    if (visible && selectedPage == 0) measureNetworkTarget(generation, outlet, target, false);
                    else finishNetworkCheck(generation, target, result);
                }, 1200);
            });
        });
    }

    private void finishNetworkCheck(int generation, NetworkCheck target, int delay) {
        if (isDestroyed() || generation != networkCheckGeneration) return;
        boolean reachable = delay > 0;
        if (target == NetworkCheck.GOOGLE) googleReachable = reachable;
        if (target == NetworkCheck.CLOUDFLARE) cloudflareReachable = reachable;
        showNetworkResult(target, reachable ? delay + " ms" : delay < 0 ? "—" : "失败",
            reachable ? MINT : delay < 0 ? MUTED : AMBER);
        if (--networkChecksPending == 0) {
            networkCheckArea.setEnabled(!busy);
            boolean foreignUnavailable = nodeLatencyAvailable && !googleReachable && !cloudflareReachable;
            if (hasProxyControl(status) && foreignUnavailable) {
                networkWarning.setText("节点的 Google 和 Cloudflare 测速均未通过 · 查看诊断  →");
                networkWarning.setVisibility(View.VISIBLE);
            } else {
                networkWarning.setVisibility(View.GONE);
            }
            scheduleLatency(foreignUnavailable ? 15000 : 5000);
        }
    }

    private void resetNetworkChecks() {
        networkCheckGeneration++;
        networkChecksPending = 0;
        confirmNextLatency = true;
        networkWarning.setVisibility(View.GONE);
        for (NetworkCheck target : NetworkCheck.values()) showNetworkResult(target, "—", MUTED);
        networkCheckArea.setEnabled(!busy);
    }

    private void showNetworkResult(NetworkCheck target, String value, int color) {
        TextView result = networkResults[target.ordinal()];
        result.setText(value);
        result.setTextColor(color);
    }

    private void refresh() { execute("", Module::status); }

    private void pollStatus() {
        if (!visible || busy || polling) return;
        if (status == null) {
            refresh();
            return;
        }
        // A background status check must not change the controls' enabled state.
        polling = true;
        worker.execute(() -> {
            try {
                Module.Status updated = Module.status();
                ProxySummary summary = readProxySummary(updated);
                main.post(() -> {
                    if (isDestroyed()) return;
                    polling = false;
                    if (!busy) checkNetworkAfterStatus(updated, summary, false);
                    status = updated;
                    proxySummary = summary;
                    render();
                    schedulePoll();
                    deliverBackgroundResults();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    polling = false;
                    if (!busy) showMessage(error.getMessage() == null
                        ? "状态刷新失败，请重新检测环境。" : error.getMessage());
                    schedulePoll();
                });
            }
        });
    }

    private void schedulePoll() {
        main.removeCallbacks(poll);
        // Root's boot service, a tile, or a repair can start TUN while this screen says stopped.
        if (visible && !busy && status != null && status.installed()) main.postDelayed(poll, 4000);
    }

    private static boolean hasProxyControl(Module.Status status) {
        return status != null && status.running() && status.control;
    }

    private static ProxySummary readProxySummary(Module.Status status) {
        if (!hasProxyControl(status)) return null;
        Controller.Mode mode;
        try { mode = Controller.mode(); }
        catch (Exception error) { return null; }
        try { return new ProxySummary(mode, Controller.primaryNode(mode)); }
        catch (Exception error) { return new ProxySummary(mode, "暂不可用"); }
    }

    private void refreshSubscriptionCatalog() {
        try {
            SubscriptionCatalog.Snapshot loaded = subscriptionCatalog.load();
            main.post(() -> {
                if (isDestroyed()) return;
                subscriptions = loaded;
                render();
            });
        } catch (Exception error) {
            main.post(() -> {
                if (isDestroyed()) return;
                if (subscriptions == null) subscriptionSummary.setText("无法读取订阅列表");
                showMessage("无法读取订阅列表。");
            });
        }
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
    }

    private long appVersionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
    }

    private void showAppUpdate(AppUpdate.Release release) {
        showMessage("");
        if (release == null) {
            background.updateCheck.clearResult();
            showMessage("当前已是最新版本 " + appVersion() + "。");
            return;
        }
        backgroundDialog = new AlertDialog.Builder(this).setTitle("发现新版 MinClash " + release.version())
            .setMessage(release.notes().isEmpty() ? "下载完成后，由 Android 确认安装。" : release.notes())
            .setNegativeButton("稍后", (dialog, which) -> dismissBackgroundResult(background.updateCheck))
            .setPositiveButton("下载并安装", (dialog, which) -> {
                dismissBackgroundResult(background.updateCheck);
                startAppUpdate(release);
            }).setOnCancelListener(dialog -> dismissBackgroundResult(background.updateCheck)).show();
    }

    private void startAppUpdate(AppUpdate.Release release) {
        if (UpdateInstallReceiver.installing || background.updateDownload.running()
            || background.updateDownload.outcome() != null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingUpdate = release;
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())), UPDATE_INSTALL_PERMISSION);
                showMessage("请允许 MinClash 安装应用，返回后继续下载。");
            } catch (android.content.ActivityNotFoundException error) {
                pendingUpdate = null;
                showMessage("系统没有提供允许安装应用的设置入口。");
            }
            return;
        }
        showMessage("正在下载新版 MinClash…");
        UpdateInstallReceiver.installing = true;
        Context context = getApplicationContext();
        background.updateDownload.start(BackgroundWork.DOWNLOADS, () -> {
            try { AppUpdate.downloadAndInstall(context, release); }
            catch (Exception error) {
                UpdateInstallReceiver.installing = false;
                throw error;
            }
            return null;
        });
        render();
    }

    private void dismissBackgroundResult(RetainedTask<?> task) {
        task.clearResult();
        backgroundDialog = null;
        main.post(this::deliverBackgroundResults);
    }

    private boolean showBackgroundError(RetainedTask<?> task) {
        RetainedTask.Outcome<?> outcome = task.outcome();
        if (outcome == null || outcome.error() == null) return false;
        if (task == background.updateDownload) UpdateInstallReceiver.installing = false;
        Exception error = outcome.error();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("操作未完成")
            .setMessage(error.getMessage() == null ? "操作失败，请重试。" : error.getMessage())
            .setNegativeButton("关闭", (view, which) -> dismissBackgroundResult(task))
            .setOnCancelListener(view -> dismissBackgroundResult(task));
        if (error instanceof Subscription.NetworkFailure failure) {
            dialog.setPositiveButton("诊断网络", (view, which) -> {
                dismissBackgroundResult(task);
                runDiagnostics(failure);
            });
        }
        backgroundDialog = dialog.show();
        return true;
    }

    private void deliverBackgroundResults() {
        if (!resumed || isDestroyed()) return;
        if (background.backupRestoring && !background.backup.running() && background.backup.outcome() != null) {
            background.backupRestoring = false;
            busy = false;
            invalidateAppRouting();
            worker.execute(this::refreshSubscriptionCatalog);
            autoUpdateSwitch.setChecked(appPreferences().getBoolean(SubscriptionUpdateJob.ENABLED, false));
        }
        render();
        if (busy || backgroundDialog != null || managementDialog != null && managementDialog.isShowing()) return;
        if (showBackgroundError(background.usage) || showBackgroundError(background.backup) || showBackgroundError(background.updateDownload) || showBackgroundError(background.preview)
            || showBackgroundError(background.updateCheck)) {
            render();
            return;
        }
        if (background.updateDownload.outcome() != null) background.updateDownload.clearResult();
        if (background.usage.outcome() != null) {
            BackgroundWork.UsageResult result = background.usage.outcome().value();
            AlertDialog shown = usageDialog(result.entry(), result.value(), () -> dismissBackgroundResult(background.usage));
            backgroundDialog = shown;
            worker.execute(this::refreshSubscriptionCatalog);
            return;
        }
        if (background.backup.outcome() != null) {
            showBackupResult(background.backup.outcome().value());
            return;
        }
        if (background.preview.outcome() != null) {
            if (status == null) return;
            showSubscriptionPreview(background.preview.outcome().value());
        }
        else if (background.updateCheck.outcome() != null) showAppUpdate(background.updateCheck.outcome().value());
        render();
    }

    private void onUpdateInstallResult(Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                try {
                    showMessage("请在系统安装界面确认更新。");
                    startActivity(confirmation);
                    return;
                } catch (android.content.ActivityNotFoundException ignored) { }
            }
            UpdateInstallReceiver.installing = false;
            showMessage("无法打开 Android 安装界面。");
        } else {
            showMessage(switch (status) {
                case PackageInstaller.STATUS_SUCCESS -> "App 更新已安装。";
                case PackageInstaller.STATUS_FAILURE_ABORTED -> "安装已取消。";
                case PackageInstaller.STATUS_FAILURE_CONFLICT -> "无法覆盖安装，请确认新旧 APK 使用同一签名。";
                case PackageInstaller.STATUS_FAILURE_STORAGE -> "安装失败，设备可用存储空间不足。";
                default -> "Android 安装更新失败，请检查安装权限后重试。";
            });
        }
        render();
    }

    private void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException error) {
            showMessage("未找到可打开链接的应用，请安装浏览器后重试。");
        }
    }

    private void confirmInstall() {
        new AlertDialog.Builder(this).setTitle("安装内置模块 " + bundledModule.version())
            .setMessage("通过 Root 管理器安装模块，保留已有配置。安装完成后，请手动重启手机使其生效。")
            .setNegativeButton("取消", null).setPositiveButton("安装", (dialog, which) -> {
                showMessage("正在安装内置模块，请稍候…");
                execute("模块安装完成，重启手机后生效。", () -> {
                    BundledModule.install(this);
                    return Module.status();
                });
            }).show();
    }

    private void exportModule() {
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        picker.setType("application/zip");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.putExtra(Intent.EXTRA_TITLE, "MinClash-Root-v" + bundledModule.version() + ".zip");
        startActivityForResult(picker, EXPORT_MODULE);
    }

    private void showSubscriptions() {
        if (subscriptions == null || busy) return;
        List<SubscriptionCatalog.Entry> entries = subscriptions.entries();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("管理订阅")
            .setNegativeButton("关闭", null).setPositiveButton("添加订阅", (view, which) -> promptSubscription());
        if (entries.isEmpty()) {
            dialog.setMessage("还没有保存的订阅。可添加 Clash / Mihomo 订阅链接。");
        } else {
            AppUi ui = new AppUi(this);
            AppUi.Summaries adapter = ui.new Summaries();
            List<AppUi.Summary> rows = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                SubscriptionCatalog.Entry entry = entries.get(i);
                rows.add(new AppUi.Summary(entry.name(), SubscriptionUsage.load(appPreferences(), entry.address()).summary(),
                    entry.address().equals(subscriptions.activeAddress()) ? "当前使用" : "", i));
            }
            adapter.set(rows); dialog.setAdapter(adapter, (view, index) -> {
                AppUi.Summary row = adapter.getItem(index); if (row != null) showSubscriptionActions(entries.get(row.sourceIndex()));
            });
        }
        AlertDialog shown = dialog.show(); trackManagement(shown); if (!entries.isEmpty()) new AppUi(this).styleList(shown);
    }

    private void trackManagement(AlertDialog dialog) {
        if (managementDialog != null && managementDialog != dialog) managementDialog.dismiss();
        managementDialog = dialog;
        dialog.setOnDismissListener(view -> {
            if (managementDialog == dialog) managementDialog = null;
            main.post(this::deliverBackgroundResults);
        });
    }

    private void showSubscriptionActions(SubscriptionCatalog.Entry entry) {
        boolean active = entry.address().equals(subscriptions.activeAddress());
        String use = active ? "更新订阅" : "切换到此订阅";
        List<String> actions = new ArrayList<>();
        if (canApplySubscription()) actions.add(use);
        actions.add("重命名");
        actions.add("流量与有效期");
        if (!active) actions.add("删除");
        trackManagement(new AlertDialog.Builder(this).setTitle(entry.name())
            .setItems(actions.toArray(new String[0]), (dialog, index) -> {
                switch (actions.get(index)) {
                    case "流量与有效期" -> showUsage(entry);
                    case "重命名" -> renameSubscription(entry);
                    case "删除" -> trackManagement(new AlertDialog.Builder(this).setTitle("删除订阅")
                        .setMessage("从列表移除「" + entry.name() + "」？")
                        .setNegativeButton("取消", null).setPositiveButton("删除", (view, which) -> {
                            List<SubscriptionCatalog.Entry> entries = new ArrayList<>(subscriptions.entries());
                            entries.remove(entry);
                            saveSubscriptions(entries, "已删除订阅。");
                        }).show());
                    default -> activateSubscription(entry);
                }
            }).setNegativeButton("返回", (dialog, which) -> showSubscriptions()).show());
    }

    private void showUsage(SubscriptionCatalog.Entry entry) {
        trackManagement(usageDialog(entry, SubscriptionUsage.load(appPreferences(), entry.address()), null));
    }

    private AlertDialog usageDialog(SubscriptionCatalog.Entry entry, SubscriptionUsage usage, Runnable dismiss) {
        AppUi ui = new AppUi(this);
        LinearLayout form = ui.column(); form.setPadding(dp(20), dp(8), dp(20), dp(8));
        ui.add(form, ui.label(entry.name(), 14, MINT, true), 0);
        ui.add(form, ui.label(usage.checkedAt() == 0 ? "尚未读取" : usage.used() < 0 ? "用量未提供" : SubscriptionUsage.bytes(usage.used()), 28, TEXT, true), 12);
        ui.add(form, ui.label("已用流量 / 总额 " + SubscriptionUsage.bytes(usage.total()), 12, MUTED, false), 4);
        if (usage.used() >= 0 && usage.total() > 0) {
            android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000); progress.setProgress((int) Math.min(1000, (double) usage.used() / usage.total() * 1000));
            progress.setProgressTintList(ColorStateList.valueOf(usage.used() > usage.total() ? AMBER : MINT));
            ui.add(form, progress, 12);
        }
        String expiry = usage.expire() < 0 ? "未提供" : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(usage.expire() * 1000));
        boolean expired = usage.expire() > 0 && usage.expire() <= System.currentTimeMillis() / 1000;
        ui.add(form, ui.label("剩余  " + SubscriptionUsage.bytes(usage.remaining()) + "\n上传  " + SubscriptionUsage.bytes(usage.upload())
            + "\n下载  " + SubscriptionUsage.bytes(usage.download()), 14, TEXT, false), 14);
        ui.add(form, ui.label("到期时间  " + expiry + (expired ? " · 已到期" : ""), 13, expired ? AMBER : MUTED, false), 14);
        ui.add(form, ui.label("最近读取  " + (usage.checkedAt() == 0 ? "尚未读取" : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(usage.checkedAt()))), 11, MUTED, false), 8);
        ui.add(form, ui.label(usage.checkedAt() == 0 ? "点击刷新读取服务商用量，不会切换订阅或应用配置。"
            : "服务商返回的最近一次快照，不是实时流量。缺失字段明确标注；总额 0 不推断是否不限量。", 11, MUTED, false), 12);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        return new AlertDialog.Builder(this).setTitle("流量与有效期").setView(scroll)
            .setNegativeButton("关闭", (view, which) -> { if (dismiss != null) dismiss.run(); })
            .setOnCancelListener(view -> { if (dismiss != null) dismiss.run(); })
            .setPositiveButton("刷新用量", (view, which) -> { if (dismiss != null) dismiss.run(); refreshUsage(entry); }).show();
    }

    private void refreshUsage(SubscriptionCatalog.Entry entry) {
                if (background.usage.running() || background.usage.outcome() != null) { showMessage("正在读取另一份订阅用量，请稍后重试。"); return; }
                Context context = getApplicationContext();
                background.usage.start(BackgroundWork.DOWNLOADS, () -> {
                    SubscriptionUsage usage = Subscription.usage(entry.address());
                    return worker.submit(() -> {
                        SharedPreferences prefs = context.getSharedPreferences("MainActivity", MODE_PRIVATE);
                        SubscriptionCatalog catalog = new SubscriptionCatalog(prefs, new RoutingStore(context));
                        if (catalog.load().entries().stream().noneMatch(saved -> saved.address().equals(entry.address()))) {
                            throw new IOException("此订阅已被移除，未保存用量。");
                        }
                        usage.save(prefs, entry.address());
                        return new BackgroundWork.UsageResult(entry, usage);
                    }).get();
                });
                showMessage("正在读取服务商用量，不会切换或应用配置…");
    }

    private EditText subscriptionInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(14);
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        input.setBackground(shape(BG, 12, true));
        input.setInputType(inputType);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        return input;
    }

    private void promptSubscription() {
        EditText nameInput = subscriptionInput("订阅名称（选填）", InputType.TYPE_CLASS_TEXT);
        EditText input = subscriptionInput("https://", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(4), dp(20), 0);
        add(content, nameInput, 0);
        add(content, input, 10);
        boolean canActivate = canApplySubscription();
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("添加订阅")
            .setMessage("粘贴机场提供的 Clash / Mihomo 订阅链接。\n" + Subscription.PROXY_HINT)
            .setView(content).setNegativeButton("取消", null).setPositiveButton(canActivate ? "保存并启用" : "保存", null);
        if (canActivate) builder.setNeutralButton("仅保存", null);
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> addSubscription(dialog, nameInput, input, canActivate));
        trackManagement(dialog);
        if (canActivate) dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            .setOnClickListener(view -> addSubscription(dialog, nameInput, input, false));
    }

    private void addSubscription(AlertDialog dialog, EditText nameInput, EditText input, boolean activate) {
        if (busy) return;
        String address = input.getText().toString().trim();
        String host;
        try { host = Subscription.host(address); }
        catch (IOException invalid) {
            input.setError(invalid.getMessage());
            return;
        }
        if (subscriptions.entries().stream().anyMatch(entry -> entry.address().equals(address))) {
            input.setError("此订阅已在列表中。");
            return;
        }
        String name = nameInput.getText().toString().trim();
        SubscriptionCatalog.Entry entry = new SubscriptionCatalog.Entry(name.isEmpty() ? host : name, address);
        List<SubscriptionCatalog.Entry> entries = new ArrayList<>(subscriptions.entries());
        entries.add(entry);
        dialog.dismiss();
        if (activate && canApplySubscription()) {
            previewSubscription(entry, true);
        } else {
            saveSubscriptions(entries, "订阅已保存。");
        }
    }

    private void renameSubscription(SubscriptionCatalog.Entry entry) {
        EditText input = subscriptionInput("订阅名称", InputType.TYPE_CLASS_TEXT);
        input.setText(entry.name());
        input.selectAll();
        LinearLayout content = column();
        content.setPadding(dp(20), dp(4), dp(20), 0);
        add(content, input, 0);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("重命名订阅")
            .setView(content).setNegativeButton("取消", null).setPositiveButton("保存", null).show();
        trackManagement(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("请输入订阅名称。"); return; }
            List<SubscriptionCatalog.Entry> entries = new ArrayList<>(subscriptions.entries());
            entries.set(entries.indexOf(entry), new SubscriptionCatalog.Entry(name, entry.address()));
            dialog.dismiss();
            saveSubscriptions(entries, "订阅已重命名。");
        });
    }

    private void saveSubscriptions(List<SubscriptionCatalog.Entry> entries, String success) {
        execute(success, () -> {
            subscriptionCatalog.save(entries);
            refreshSubscriptionCatalog();
            return null;
        });
    }

    private void activateSubscription(SubscriptionCatalog.Entry entry) {
        if (entry == null || !canApplySubscription()) return;
        previewSubscription(entry, false);
    }

    private void previewSubscription(SubscriptionCatalog.Entry entry, boolean adding) {
        Context context = getApplicationContext();
        if (background.preview.start(BackgroundWork.DOWNLOADS, () -> {
            Subscription.Download downloaded = Subscription.download(entry.address());
            byte[] source = downloaded.source();
            // Snapshot and configuration writes share the same queue; downloading does not.
            try {
                return worker.submit(() -> {
                    if (adding || new SubscriptionCatalog(context.getSharedPreferences("MainActivity", MODE_PRIVATE), new RoutingStore(context))
                        .load().entries().stream().anyMatch(saved -> saved.address().equals(entry.address()))) {
                        downloaded.usage().cache(context.getSharedPreferences("MainActivity", MODE_PRIVATE), entry.address());
                    }
                    Module.Status current = Module.status();
                    List<Controller.Group> selections = null;
                    if (hasProxyControl(current)) {
                        try { selections = Controller.groups().values(); }
                        catch (Exception unavailable) { selections = null; }
                    }
                    return SubscriptionPreview.compare(entry, source, RoutingStore.generation(), adding,
                        new RoutingStore(context).loadIfAvailable(current), selections);
                }).get();
            } catch (ExecutionException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                throw error;
            }
        })) {
            showMessage("正在下载订阅并比较配置…");
            render();
        }
    }

    private void showSubscriptionPreview(SubscriptionPreview preview) {
        showMessage("");
        AlertDialog.Builder dialog = textDialog("订阅变更预览 · " + preview.entry().name(), preview.summary())
            .setNegativeButton("关闭", (view, which) -> dismissBackgroundResult(background.preview))
            .setOnCancelListener(view -> dismissBackgroundResult(background.preview));
        if (!preview.conflicts()) dialog.setPositiveButton("应用配置", null);
        AlertDialog shown = dialog.show();
        backgroundDialog = shown;
        if (!preview.conflicts()) shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!canApplySubscription()) {
                shown.setMessage(preview.summary() + "\n\n当前 TUN 状态不允许应用订阅，请等待状态刷新后重试。");
                return;
            }
            dismissBackgroundResult(background.preview);
            shown.dismiss();
            execute("订阅配置已应用。", true, () -> applySubscription(preview));
        });
    }

    private Module.Status applySubscription(SubscriptionPreview preview) throws Exception {
        String address = preview.entry().address();
        try {
            if (preview.generation() != RoutingStore.generation()) {
                throw new IOException("当前配置已变化，请重新预览订阅。");
            }
            Module.Status current = Module.status();
            if (!canApplySubscription(current)) throw new IOException("当前 TUN 状态不允许应用订阅，请稍后重试。");
            List<SubscriptionCatalog.Entry> entries = new ArrayList<>(subscriptionCatalog.load().entries());
            boolean exists = entries.stream().anyMatch(entry -> entry.address().equals(address));
            if (exists == preview.adding()) throw new IOException("订阅列表已变化，请重新预览。");
            if (preview.adding()) {
                entries.add(preview.entry());
                subscriptionCatalog.save(entries);
            }
            new RoutingStore(this).importProfile(preview.source(), address);
        } finally {
            refreshSubscriptionCatalog();
        }
        return Module.status();
    }

    private void pickProfile() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("*/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(picker, PICK_PROFILE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_BACKUP) {
            byte[] bytes = background.pendingExport;
            background.pendingExport = null;
            if (result != RESULT_OK || data == null || data.getData() == null) { render(); return; }
            if (bytes == null) { showMessage("备份准备状态已丢失，请重新导出。"); return; }
            Uri uri = data.getData();
            Context context = getApplicationContext();
            background.backup.start(BackgroundWork.DOWNLOADS, () -> {
                try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("无法写入所选位置。");
                    output.write(bytes);
                }
                return new BackgroundWork.BackupDone(false, null, "");
            });
            render();
            return;
        }
        if (request == IMPORT_BACKUP) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            Context context = getApplicationContext();
            background.backup.start(BackgroundWork.DOWNLOADS, () -> {
                byte[] bytes;
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("无法读取所选备份。");
                    bytes = BackupArchive.read(input);
                }
                return BackupArchive.encrypted(bytes) ? new BackgroundWork.BackupLocked(bytes, false)
                    : decodeBackup(context, bytes, new char[0]);
            });
            render();
            return;
        }
        if (request == UPDATE_INSTALL_PERMISSION) {
            AppUpdate.Release release = pendingUpdate;
            pendingUpdate = null;
            if (release != null && getPackageManager().canRequestPackageInstalls()) startAppUpdate(release);
            else showMessage("未获得安装权限，可在「关于」重新检查更新。");
            return;
        }
        if (request == APP_LIST_SETTINGS) {
            refreshAppRouting(appPreferences().getBoolean(ROOT_INVENTORY, false));
            return;
        }
        if (request == EXPORT_MODULE && result == RESULT_OK && data != null && data.getData() != null) {
            Uri outputUri = data.getData();
            execute("模块安装包已导出，可在 Root 管理器中安装。", () -> {
                try (OutputStream output = getContentResolver().openOutputStream(outputUri, "wt")) {
                    if (output == null) throw new IOException("无法写入所选位置。");
                    BundledModule.copy(this, output);
                }
                return null;
            });
            return;
        }
        if (request != PICK_PROFILE || result != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        execute("配置已通过 Mihomo 校验并保存。", () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法打开所选文件。");
                new RoutingStore(this).importProfile(Profile.normalize(input), "");
            } finally {
                refreshSubscriptionCatalog();
            }
            return Module.status();
        });
    }

    private EditText backupPassword(String hint) {
        EditText input = subscriptionInput(hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(256)});
        return input;
    }

    private void showBackupExport() {
        if (busy || background.backup.running() || background.backup.outcome() != null) return;
        LinearLayout form = column();
        form.setPadding(dp(20), dp(6), dp(20), dp(6));
        add(form, label("包含订阅链接与节点凭据。建议设置密码；密码不会保存，遗忘后无法解密。", 13, MUTED, false), 0);
        Switch encrypted = new Switch(this);
        encrypted.setText("使用密码加密");
        encrypted.setTextColor(TEXT);
        encrypted.setChecked(true);
        add(form, encrypted, 10);
        EditText password = backupPassword("密码（至少 8 个字符）");
        EditText confirmation = backupPassword("再次输入密码");
        add(form, password, 8);
        add(form, confirmation, 8);
        TextView plainHint = label("未加密备份会直接包含订阅链接与节点凭据，请妥善保管。", 12, AMBER, false);
        plainHint.setVisibility(View.GONE);
        add(form, plainHint, 8);
        encrypted.setOnCheckedChangeListener((view, checked) -> {
            password.setVisibility(checked ? View.VISIBLE : View.GONE);
            confirmation.setVisibility(checked ? View.VISIBLE : View.GONE);
            plainHint.setVisibility(checked ? View.GONE : View.VISIBLE);
        });
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("导出备份").setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("选择保存位置", null).show();
        backupPasswordDialog = dialog;
        dialog.setOnDismissListener(view -> {
            password.getText().clear();
            confirmation.getText().clear();
            backupPasswordDialog = null;
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (encrypted.isChecked() && password.length() < 8) { password.setError("请设置至少 8 个字符的密码。"); return; }
            if (encrypted.isChecked() && !password.getText().toString().equals(confirmation.getText().toString())) {
                confirmation.setError("两次密码不一致。"); return;
            }
            char[] secret = new char[encrypted.isChecked() ? password.length() : 0];
            password.getText().getChars(0, secret.length, secret, 0);
            password.getText().clear();
            confirmation.getText().clear();
            dialog.dismiss();
            Context context = getApplicationContext();
            String version = appVersion();
            if (!background.backup.start(BackgroundWork.DOWNLOADS, () -> {
                try {
                    BackupArchive.Data snapshot = worker.submit(() -> new BackupStore(context).capture(version)).get();
                    byte[] bytes = BackupArchive.encode(snapshot, secret);
                    // Exercise the same validation path before offering the file for export.
                    BackupArchive.decode(bytes, secret);
                    return new BackgroundWork.BackupExport(bytes);
                } finally { java.util.Arrays.fill(secret, '\0'); }
            })) java.util.Arrays.fill(secret, '\0');
            showMessage("正在准备备份…");
            render();
        });
    }

    private static Object decodeBackup(Context context, byte[] bytes, char[] password) throws Exception {
        try {
            BackupArchive.Data data = BackupArchive.decode(bytes, password);
            String stamp = worker.submit(() -> new BackupStore(context).stamp()).get();
            return new BackgroundWork.BackupPreview(data, stamp);
        } catch (BackupArchive.PasswordFailure incorrect) {
            return new BackgroundWork.BackupLocked(bytes, true);
        } finally { java.util.Arrays.fill(password, '\0'); }
    }

    private void showBackupResult(Object result) {
        if (result instanceof BackgroundWork.BackupExport export) {
            background.backup.clearResult();
            background.pendingExport = export.bytes();
            Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,
                    "MinClash-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date()) + ".mcbak");
            try { startActivityForResult(picker, EXPORT_BACKUP); }
            catch (android.content.ActivityNotFoundException error) {
                background.pendingExport = null;
                showMessage("系统没有提供文件保存入口。");
            }
        } else if (result instanceof BackgroundWork.BackupLocked locked) {
            LinearLayout form = column();
            form.setPadding(dp(20), dp(6), dp(20), dp(6));
            add(form, label(locked.incorrect() ? "密码不正确，或文件已损坏。可重新输入密码。" : "此备份已加密，请输入导出时设置的密码。", 13, MUTED, false), 0);
            EditText password = backupPassword("备份密码");
            add(form, password, 10);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("解密备份").setView(form)
                .setNegativeButton("取消", (view, which) -> dismissBackgroundResult(background.backup))
                .setOnCancelListener(view -> dismissBackgroundResult(background.backup))
                .setPositiveButton("解密并预览", null).show();
            backgroundDialog = dialog;
            dialog.setOnDismissListener(view -> password.getText().clear());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (password.length() == 0) { password.setError("请输入备份密码。"); return; }
                char[] secret = new char[password.length()];
                password.getText().getChars(0, secret.length, secret, 0);
                password.getText().clear();
                background.backup.clearResult();
                backgroundDialog = null;
                dialog.dismiss();
                Context context = getApplicationContext();
                if (!background.backup.start(BackgroundWork.DOWNLOADS, () -> decodeBackup(context, locked.bytes(), secret))) {
                    java.util.Arrays.fill(secret, '\0');
                }
                render();
            });
        } else if (result instanceof BackgroundWork.BackupPreview preview) {
            BackupArchive.Data data = preview.data();
            int favorites = 0;
            int categories = 0;
            for (Object value : data.preferences().values()) if (value instanceof Set<?> set) favorites += set.size();
            try { categories = new org.json.JSONArray((String) data.preferences().getOrDefault("routing_categories", "[]")).length(); }
            catch (org.json.JSONException invalid) { /* Already validated by BackupArchive. */ }
            String summary = "自定义分流  " + data.state().rules().size() + " 条\n备份版本  " + data.appVersion() + "\n备份时间  "
                + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(data.createdAt()))
                + "\n订阅  " + data.subscriptions().size() + " 个\n应用分流  " + data.state().applications().size()
                + " 项\n自定义分类  " + categories + " 个\n节点收藏  " + favorites + " 项\n配置  " + data.state().source().length / 1024 + " KiB"
                + "\n\n将替换当前配置、全部订阅、应用分流、自定义分流、分类、收藏和订阅自动更新／节点列表偏好。"
                + "\n\n请先停止 TUN。恢复后不会自动启动 TUN；通知、图标、系统授权、开机与热点设置保留本机选择。";
            AlertDialog dialog = textDialog("恢复备份 · 内容预览", summary)
                .setNegativeButton("取消", (view, which) -> dismissBackgroundResult(background.backup))
                .setOnCancelListener(view -> dismissBackgroundResult(background.backup))
                .setNeutralButton("停止 TUN", null)
                .setPositiveButton("替换并恢复", null).show();
            backgroundDialog = dialog;
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                if (busy || status == null || !status.active()) return;
                execute("TUN 已停止，可确认恢复备份。", () -> {
                    Module.run(Module.Command.STOP, null);
                    return Module.status();
                });
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (busy) return;
                if (status == null || !status.installed() || !status.enabled || !status.state.equals("stopped")) {
                    showMessage("请先安装并启用模块、停止 TUN，再恢复备份。");
                    return;
                }
                background.backup.clearResult();
                backgroundDialog = null;
                dialog.dismiss();
                background.backupRestoring = true;
                busy = true;
                Context context = getApplicationContext();
                background.backup.start(worker, () -> {
                    new BackupStore(context).restore(data, preview.stamp());
                    SharedPreferences prefs = context.getSharedPreferences("MainActivity", MODE_PRIVATE);
                    String warning = "";
                    boolean scheduled;
                    try { scheduled = SubscriptionUpdateJob.schedule(context, prefs.getBoolean(SubscriptionUpdateJob.ENABLED, false)); }
                    catch (RuntimeException unavailable) { scheduled = false; }
                    if (!scheduled) {
                        prefs.edit().putBoolean(SubscriptionUpdateJob.ENABLED, false).apply();
                        warning = "自动订阅更新未能安排，请重新开启。";
                    }
                    Module.Status restored = null;
                    try { restored = Module.status(); }
                    catch (Exception unavailable) { warning += "请重新检测环境以刷新状态。"; }
                    return new BackgroundWork.BackupDone(true, restored, warning);
                });
                render();
            });
        } else if (result instanceof BackgroundWork.BackupDone done) {
            background.backup.clearResult();
            if (done.status() != null) { status = done.status(); proxySummary = null; }
            showMessage((done.restored() ? "备份已恢复，TUN 保持停止。" : "备份已导出。") + done.warning());
            render();
            schedulePoll();
        }
    }

    private void updateLauncherIcon() {
        LauncherIcon icon = LauncherIcon.current(this);
        launcherIconButton.setText("桌面图标 · " + icon.label);
        aboutLogo.setImageResource(getResources().getIdentifier(icon.drawable, "drawable", getPackageName()));
    }

    private void showLauncherIcons() {
        LauncherIcon[] icons = LauncherIcon.values();
        String[] labels = new String[icons.length];
        for (int i = 0; i < icons.length; i++) labels[i] = icons[i].label;
        new AlertDialog.Builder(this).setTitle("桌面图标")
            .setSingleChoiceItems(labels, LauncherIcon.current(this).ordinal(), (dialog, index) -> {
                dialog.dismiss();
                try {
                    icons[index].select(this);
                    showMessage("已选择" + icons[index].label + "图标，回到桌面查看。");
                } catch (IllegalArgumentException | SecurityException error) {
                    android.util.Log.w("MinClash", "Unable to change launcher icon", error);
                    showMessage("切换桌面图标失败，请重试。");
                }
                updateLauncherIcon();
            }).setNegativeButton("取消", null).show();
    }

    private void showModes(Controller.Mode current) {
        Controller.Mode[] modes = Controller.Mode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label;
        new AlertDialog.Builder(this).setTitle("代理模式")
            .setSingleChoiceItems(labels, current.ordinal(), (dialog, index) -> {
                dialog.dismiss();
                if (modes[index] != current) {
                    Controller.Mode mode = modes[index];
                    execute("已切换到" + mode.label + "模式。", true, () -> {
                        Module.run(mode.command, null);
                        return Module.status();
                    });
                }
            }).setNegativeButton("取消", null).show();
    }

    private void showGroups(List<Controller.Group> groups) {
        if (groups.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("代理组与节点")
                .setMessage("当前配置没有可手动选择的代理组。").setPositiveButton("关闭", null).show();
            return;
        }
        List<Controller.Group> ordered = new ArrayList<>(groups);
        ordered.sort(Comparator.comparing((Controller.Group group) -> ProxyGroupSection.of(group.name()))
            .thenComparing(group -> !ProxyGroupSection.isProxies(group.name())));
        List<PolicyItem> items = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Controller.Group group = ordered.get(i);
            items.add(new PolicyItem(group.name(), "当前出口 · " + groupRoute(group, groups),
                group.current().equals("DIRECT") ? "直连" : group.automatic() ? "自动" : "手动", false, true, i,
                ProxyGroupSection.of(group.name()).label));
        }
        showPolicyList("代理组与节点", groups.size() + " 个代理组 · 点击查看可选节点", "搜索代理组或当前出口", items,
            (dialog, index) -> { dialog.dismiss(); showNodes(ordered.get(index), groups, null); }, null, null,
            index -> ordered.get(index).name(), false);
    }

    private String groupRoute(Controller.Group group, List<Controller.Group> groups) {
        List<String> route = Controller.route(group, groups);
        if (route.isEmpty()) return "尚未选择";
        return TextUtils.join(" → ", route.stream().map(Controller::outletLabel).toArray());
    }

    private void showNodes(Controller.Group group, List<Controller.Group> groups, Runnable browseGroups) {
        List<PolicyItem> items = new ArrayList<>();
        for (int i = 0; i < group.options().size(); i++) {
            String option = group.options().get(i);
            String detail = option.equals("DIRECT") ? "直接连接，不经过代理"
                : option.equals("REJECT") || option.equals("REJECT-DROP") ? "拒绝连接" : "代理节点";
            for (Controller.Group candidate : groups) if (candidate.name().equals(option)) {
                detail = "代理组 · " + groupRoute(candidate, groups);
                break;
            }
            boolean selected = option.equals(group.current());
            items.add(new PolicyItem(Controller.outletLabel(option), detail,
                selected ? "当前" : "", selected, false, i, ""));
        }
        showPolicyList(group.name() + " · 选择出口", group.options().size() + " 个选项 · 点击切换", "搜索节点或代理组", items,
            (dialog, index) -> {
                dialog.dismiss();
                String option = group.options().get(index);
                if (!option.equals(group.current())) selectNode(group.name(), option);
            }, group.automatic() ? () -> selectNode(group.name(), null) : null, browseGroups, group.options()::get, true);
    }

    private record PolicyItem(String title, String detail, String badge, boolean selected, boolean navigates,
                              int index, String section) {}

    private void showPolicyList(String title, String subtitle, String hint, List<PolicyItem> items,
                                BiConsumer<AlertDialog, Integer> onPick, Runnable reset, Runnable browseGroups,
                                java.util.function.IntFunction<String> testName, boolean nodePicker) {
        if (policyDialog != null) policyDialog.dismiss();
        LinearLayout header = column();
        header.setPadding(dp(20), dp(20), dp(20), dp(8));
        add(header, label(title, 20, TEXT, true), 0);
        add(header, label(subtitle, 12, MUTED, false), 8);
        PolicyAdapter adapter = new PolicyAdapter(items, testName, nodePicker);
        if (nodePicker) adapter.addControls(header);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(14);
        search.setHint(hint);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        search.setBackground(shape(BG, 12, true));
        add(header, search, 14);
        adapter.filter("");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                String query = text.toString().trim().toLowerCase(Locale.ROOT);
                adapter.filter(query);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setCustomTitle(header)
            .setAdapter(adapter, null).setNegativeButton("关闭", null);
        if (reset != null) builder.setNeutralButton("恢复自动", (dialog, which) -> reset.run());
        if (browseGroups != null) builder.setPositiveButton("所有代理组", (dialog, which) -> browseGroups.run());
        AlertDialog dialog = builder.show();
        policyDialog = dialog;
        dialog.setOnDismissListener(dismissed -> {
            adapter.close();
            if (policyDialog == dialog) policyDialog = null;
        });
        dialog.getListView().setDivider(null);
        dialog.getListView().setPadding(dp(10), dp(4), dp(10), dp(6));
        dialog.getListView().setClipToPadding(false);
        dialog.getListView().setOnItemClickListener((parent, view, index, id) -> {
            if (adapter.configGeneration != RoutingStore.generation()) { adapter.refreshItems(); return; }
            PolicyItem item = adapter.getItem(index);
            if (item != null && item.index() >= 0 && !busy) onPick.accept(dialog, item.index());
        });
        enableSearchKeyboard(dialog);
    }

    private record PolicyRow(LinearLayout card, TextView title, TextView detail, TextView favorite, TextView badge, TextView arrow) {}

    private final class PolicyAdapter extends BaseAdapter {
        private final List<PolicyItem> entries = new ArrayList<>();
        private final List<PolicyItem> source;
        private final List<String> names = new ArrayList<>();
        private final java.util.function.IntFunction<String> testName;
        private final boolean nodePicker;
        private final String favoritesKey;
        private final Set<String> favorites;
        private final NodeLatencyBatch tests;
        private NodeLatencyBatch.Snapshot measurement;
        private String query = "";
        private boolean sortByDelay;
        private boolean hideFailed;
        private boolean closed;
        private final int configGeneration = RoutingStore.generation();
        private Button batchButton;
        private TextView progress;
        private final Runnable update = this::refreshItems;

        PolicyAdapter(List<PolicyItem> items, java.util.function.IntFunction<String> testName, boolean nodePicker) {
            this.source = items;
            this.testName = testName;
            this.nodePicker = nodePicker;
            for (PolicyItem item : items) names.add(testName.apply(item.index()));
            favoritesKey = NodeList.favoritesKey(subscriptions == null ? "" : subscriptions.activeAddress());
            favorites = new LinkedHashSet<>(appPreferences().getStringSet(favoritesKey, java.util.Collections.emptySet()));
            sortByDelay = appPreferences().getBoolean("node_sort_delay", false);
            hideFailed = appPreferences().getBoolean("node_hide_failed", false);
            tests = new NodeLatencyBatch(name -> Controller.testDelay(name, NetworkCheck.GOOGLE), () -> main.post(update));
            measurement = tests.snapshot();
        }

        void addControls(LinearLayout header) {
            LinearLayout controls = new LinearLayout(MainActivity.this);
            batchButton = pickerControl("测本组");
            batchButton.setOnClickListener(view -> {
                if (measurement.running()) tests.cancel();
                else if (!busy) tests.start(names);
            });
            Button order = pickerControl(sortByDelay ? "延迟排序 ✓" : "延迟排序");
            order.setOnClickListener(view -> {
                sortByDelay = !sortByDelay;
                appPreferences().edit().putBoolean("node_sort_delay", sortByDelay).apply();
                order.setText(sortByDelay ? "延迟排序 ✓" : "延迟排序");
                refreshItems();
            });
            Button hide = pickerControl(hideFailed ? "隐藏失败 ✓" : "隐藏失败");
            hide.setOnClickListener(view -> {
                hideFailed = !hideFailed;
                appPreferences().edit().putBoolean("node_hide_failed", hideFailed).apply();
                hide.setText(hideFailed ? "隐藏失败 ✓" : "隐藏失败");
                refreshItems();
            });
            for (Button control : new Button[] {batchButton, order, hide}) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
                if (controls.getChildCount() > 0) params.leftMargin = dp(5);
                controls.addView(control, params);
            }
            add(header, controls, 12);
            progress = label("Google 204 延迟 · 点 ☆ 收藏并置顶", 11, MUTED, false);
            add(header, progress, 6);
        }

        private Button pickerControl(String title) {
            Button control = button(title, false);
            control.setTextSize(11);
            control.setPadding(dp(2), 0, dp(2), 0);
            control.setMinimumWidth(0);
            control.setMinWidth(0);
            control.setSingleLine(true);
            return control;
        }

        private void measure(int index) {
            if (!closed && !busy) tests.startOne(testName.apply(index));
        }

        void close() {
            closed = true;
            tests.close();
            main.removeCallbacks(update);
        }

        void filter(String query) {
            this.query = query;
            refreshItems();
        }

        private void refreshItems() {
            if (closed || isDestroyed()) return;
            if (configGeneration != RoutingStore.generation()) {
                if (policyDialog != null) policyDialog.dismiss();
                showMessage("配置已变化，请重新打开代理组。");
                return;
            }
            measurement = tests.snapshot();
            if (batchButton != null) {
                batchButton.setText(measurement.running() ? "停止测速" : "测本组");
                batchButton.setEnabled(names.stream().anyMatch(NodeList::testable));
                String count = measurement.total() == 0 ? "" : (measurement.running() ? "测速中 " : "已测 ")
                    + measurement.completed() + "/" + measurement.total() + " · ";
                progress.setText(count + "Google 204 延迟 · 收藏置顶");
            }
            entries.clear();
            String section = "";
            List<PolicyItem> ordered = source;
            if (nodePicker) {
                ordered = new ArrayList<>();
                for (int index : NodeList.order(names, favorites, measurement.delays(), sortByDelay, hideFailed)) {
                    ordered.add(source.get(index));
                }
            }
            for (PolicyItem item : ordered) {
                if (!(item.title() + " " + item.detail()).toLowerCase(Locale.ROOT).contains(query)) continue;
                if (!item.section().isEmpty() && !section.equals(item.section())) {
                    section = item.section();
                    entries.add(new PolicyItem(section, "", "", false, false, -1, ""));
                }
                entries.add(item);
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return Math.max(1, entries.size()); }
        @Override public PolicyItem getItem(int index) { return index < entries.size() ? entries.get(index) : null; }
        @Override public long getItemId(int index) { return index; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int index) {
            PolicyItem item = getItem(index);
            return item != null && item.index() >= 0;
        }
        @Override public View getView(int index, View reusable, ViewGroup parent) {
            PolicyItem item = getItem(index);
            if (item == null) {
                TextView empty = label(nodePicker && hideFailed ? "没有可显示的项目\n可关闭“隐藏失败”或修改搜索" : "没有匹配的项目", 14, MUTED, false);
                empty.setPadding(dp(20), dp(32), dp(20), dp(32));
                empty.setGravity(Gravity.CENTER);
                return empty;
            }
            if (item.index() < 0) {
                TextView heading = label(item.title(), 12, MUTED, true);
                heading.setPadding(dp(10), dp(10), dp(10), dp(4));
                heading.setAccessibilityHeading(true);
                return heading;
            }
            LinearLayout row;
            PolicyRow holder;
            if (reusable instanceof LinearLayout layout && reusable.getTag() instanceof PolicyRow existing) {
                row = layout;
                holder = existing;
            } else {
                row = column();
                row.setPadding(dp(6), dp(4), dp(6), dp(4));
                LinearLayout card = new LinearLayout(MainActivity.this);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(14), dp(14), dp(14), dp(14));
                card.setDuplicateParentStateEnabled(true);
                LinearLayout details = column();
                card.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
                TextView name = label("", 15, TEXT, true);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                add(details, name, 0);
                TextView detail = label("", 11, MUTED, false);
                detail.setMaxLines(2);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                add(details, detail, 6);
                TextView favorite = label("", 22, MINT, false);
                favorite.setGravity(Gravity.CENTER);
                card.addView(favorite, new LinearLayout.LayoutParams(dp(40), dp(48)));
                TextView badge = label("", 11, MINT, true);
                badge.setSingleLine(true);
                badge.setGravity(Gravity.CENTER);
                badge.setMinHeight(dp(48));
                badge.setPadding(dp(9), dp(6), dp(9), dp(6));
                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
                badgeParams.leftMargin = dp(4);
                card.addView(badge, badgeParams);
                TextView arrow = label("", 20, MUTED, false);
                LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(-2, -2);
                arrowParams.leftMargin = dp(9);
                card.addView(arrow, arrowParams);
                add(row, card, 0);
                holder = new PolicyRow(card, name, detail, favorite, badge, arrow);
                row.setTag(holder);
            }
            holder.title().setText(item.title());
            holder.detail().setText(item.detail());
            String name = testName.apply(item.index());
            boolean testable = NodeList.testable(name);
            boolean measurable = testable || name.equals("DIRECT");
            boolean favorite = favorites.contains(name);
            holder.favorite().setVisibility(nodePicker && testable ? View.VISIBLE : View.GONE);
            holder.favorite().setText(favorite ? "★" : "☆");
            holder.favorite().setTextColor(favorite ? AMBER : MUTED);
            holder.favorite().setContentDescription((favorite ? "取消收藏 " : "收藏 ") + item.title());
            holder.favorite().setOnClickListener(view -> {
                if (!favorites.add(name)) favorites.remove(name);
                appPreferences().edit().putStringSet(favoritesKey, new LinkedHashSet<>(favorites)).apply();
                refreshItems();
            });
            Integer delay = measurement.delays().get(name);
            holder.badge().setText(!measurable ? "—" : measurement.pending().contains(name) ? "…"
                : delay == null ? "测速" : delay > 0 ? delay + " ms" : "失败");
            holder.badge().setVisibility(View.VISIBLE);
            holder.badge().setContentDescription(item.title() + " · Google 204 延迟测速 · " + holder.badge().getText());
            holder.badge().setEnabled(measurable && !measurement.running());
            holder.badge().setOnClickListener(view -> measure(item.index()));
            boolean accent = item.selected() || item.badge().equals("直连") || item.badge().equals("自动");
            holder.badge().setTextColor(accent ? MINT : MUTED);
            holder.badge().setBackground(shape(accent ? 0xff25473e : 0xff2a3946, 9, false));
            holder.card().setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9),
                shape(item.selected() ? 0xff213b37 : 0xff1f2d39, 14, item.selected()), null));
            holder.arrow().setText(item.navigates() ? "›" : item.selected() ? "✓" : "○");
            holder.arrow().setTextColor(item.selected() ? MINT : MUTED);
            return row;
        }
    }

    private void selectNode(String group, String node) {
        execute(node == null ? "已恢复自动选点。" : "已切换到 " + node + "。", true, () -> {
            Controller.select(group, node);
            return Module.status();
        });
    }

    private void openAppRouting() {
        routingFilter = AppCategories.APPS;
        routingQuery = "";
        boolean allowRoot = appPreferences().getBoolean(ROOT_INVENTORY, false);
        if (InstalledAppsAccess.needsRequest(this)) {
            requestPermissions(new String[] {InstalledAppsAccess.RUNTIME_PERMISSION}, REQUEST_APP_LIST);
            return;
        }
        loadAppRouting(allowRoot, false);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_NOTIFICATIONS) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) enableNotificationControl();
            else showMessage("开启通知栏控制需要在系统设置中允许 MinClash 通知。");
            return;
        }
        if (request == REQUEST_APP_LIST) refreshAppRouting(appPreferences().getBoolean(ROOT_INVENTORY, false));
    }

    private void refreshAppRouting(boolean allowRoot) {
        invalidateAppRouting();
        loadAppRouting(allowRoot, false);
    }

    private void enableRootRouting() {
        invalidateAppRouting();
        loadAppRouting(true, true);
    }

    private void invalidateAppRouting() {
        routingEpoch++;
        routingSnapshot = null;
        lastRoutingAttempt = -30000;
        routingLoader.invalidate();
        routingIcons.invalidate();
    }

    private AppRouting.Screen cachedAppRouting(boolean allowRoot) {
        RoutingSnapshot snapshot = routingSnapshot;
        if (snapshot == null || snapshot.screen().allowRoot() != allowRoot
            || snapshot.version() != RoutingStore.generation() || SystemClock.elapsedRealtime() - snapshot.time() >= 60000) return null;
        return snapshot.screen();
    }

    private void warmAppRouting() {
        if (!visible || selectedPage != 1 || busy || status == null || !status.enabled || !status.profile
            || SystemClock.elapsedRealtime() - lastRoutingAttempt < 30000) return;
        boolean allowRoot = appPreferences().getBoolean(ROOT_INVENTORY, false);
        if (cachedAppRouting(allowRoot) == null) readAppRouting(allowRoot);
    }

    private CompletableFuture<RoutingSnapshot> readAppRouting(boolean allowRoot) {
        if (routingRead != null && routingRead.root() == allowRoot && routingRead.epoch() == routingEpoch
            && !routingRead.result().isDone()) return routingRead.result();
        int epoch = routingEpoch;
        Module.Status current = status;
        CompletableFuture<RoutingSnapshot> result = new CompletableFuture<>();
        routingRead = new RoutingRead(allowRoot, epoch, result);
        lastRoutingAttempt = SystemClock.elapsedRealtime();
        result.thenAccept(snapshot -> main.post(() -> {
            if (!isDestroyed() && snapshot.epoch() == routingEpoch) routingSnapshot = snapshot;
        }));
        worker.execute(() -> {
            try {
                AppRouting.Screen screen = routingLoader.load(this, allowRoot, current);
                result.complete(new RoutingSnapshot(screen, epoch, RoutingStore.generation(), SystemClock.elapsedRealtime()));
            } catch (Exception error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private void loadAppRouting(boolean allowRoot, boolean rememberRoot) {
        AppRouting.Screen cached = cachedAppRouting(allowRoot);
        if (cached != null && (!cached.systemInventoryEmpty() || cached.rootInventory()) && !rememberRoot) {
            showAppRouting(cached);
            return;
        }
        AlertDialog loading = new AlertDialog.Builder(this).setTitle("应用分流")
            .setMessage("正在读取应用和出口…").setNegativeButton("关闭", null).show();
        readAppRouting(allowRoot).whenComplete((snapshot, error) -> main.post(() -> {
            if (isDestroyed() || !loading.isShowing()) return;
            if (error != null) {
                loading.setMessage(error.getMessage() == null ? "无法读取应用分流。" : error.getMessage());
                return;
            }
            loading.dismiss();
            if (!visible || snapshot.epoch() != routingEpoch) return;
            if (rememberRoot) appPreferences().edit().putBoolean(ROOT_INVENTORY, true).apply();
            showAppRouting(snapshot.screen());
        }));
    }

    private String iconSummary(AppRouting.Screen screen) {
        int loaded = 0, missing = 0;
        boolean denied = false;
        for (AppRouting.Entry app : screen.applications()) {
            if (!app.installed()) continue;
            AppIconLoader.Result icon = routingIcons.get(app.packageName());
            if (icon == null) continue;
            if (icon.bitmap() != null) loaded++;
            else { missing++; denied |= icon.issue().equals("permission"); }
        }
        return "已加载 " + loaded + " 个，按列表显示需要继续读取"
            + (missing == 0 ? "。" : "；" + missing + " 个读取失败" + (denied ? "，存在权限拒绝。" : "。"));
    }

    private void showAppPermissions(AppRouting.Screen screen, AlertDialog listDialog) {
        boolean requestable = InstalledAppsAccess.needsRequest(this);
        String query = Build.VERSION.SDK_INT < 30 ? "此 Android 版本无需单独申请"
            : checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == android.content.pm.PackageManager.PERMISSION_GRANTED
                ? "已授予（普通权限，无需弹窗）" : "未授予";
        String runtime = !InstalledAppsAccess.supportsRequest(this) ? "此系统未提供动态申请入口"
            : requestable ? "尚未授予，可点击下方申请" : "已授予";
        boolean rootOption = !requestable && !screen.allowRoot() && screen.applications().stream().anyMatch(app -> {
            AppIconLoader.Result icon = routingIcons.get(app.packageName());
            return icon != null && icon.bitmap() == null;
        });
        new AlertDialog.Builder(this).setTitle("应用列表与图标权限")
            .setMessage("查询已安装应用：" + query + "\n\n系统动态权限：" + runtime + "\n\n应用图标：" + iconSummary(screen)
                + "\n\n系统“应用权限”页主要展示可动态申请的权限，“未请求任何权限”不代表查询权限未声明。"
                + "\n\n图标读取失败也可能是应用被隐藏、已卸载或资源不可用；这里会按实际结果提示。")
            .setNegativeButton("关闭", null)
            .setNeutralButton("应用设置", (dialog, which) -> {
                listDialog.dismiss();
                try {
                    startActivityForResult(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())), APP_LIST_SETTINGS);
                } catch (android.content.ActivityNotFoundException error) { showMessage("无法打开系统应用设置。"); }
            })
            .setPositiveButton(requestable ? "申请权限" : rootOption ? "用 Root 补充读取" : "重新读取", (dialog, which) -> {
                listDialog.dismiss();
                if (requestable) requestPermissions(new String[] {InstalledAppsAccess.RUNTIME_PERMISSION}, REQUEST_APP_LIST);
                else if (rootOption) enableRootRouting();
                else refreshAppRouting(appPreferences().getBoolean(ROOT_INVENTORY, false));
            }).show();
    }

    private void showAppRouting(AppRouting.Screen screen) {
        if (screen.systemInventoryEmpty() && !screen.rootInventory()) {
            String reason = !InstalledAppsAccess.supportsRequest(this)
                ? "此系统没有可弹窗申请的应用列表权限；已声明的查询权限在安装时授予，但普通查询仍未返回其他应用。"
                : InstalledAppsAccess.needsRequest(this) ? "读取应用列表权限尚未授予，普通查询未返回其他应用。"
                : "读取应用列表权限已授予，但普通查询仍未返回其他应用。";
            new AlertDialog.Builder(this).setTitle("未能读取应用列表")
                .setMessage(reason + "\n\n可前往应用设置检查权限，或使用现有 Root 授权读取。选择 Root 后会记住此选择，每次仍先尝试普通查询。")
                .setNegativeButton("取消", null)
                .setNeutralButton("应用设置", (dialog, which) -> {
                    try {
                        startActivityForResult(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())), APP_LIST_SETTINGS);
                    } catch (android.content.ActivityNotFoundException error) { showMessage("无法打开系统应用设置。"); }
                })
                .setPositiveButton("用 Root 读取", (dialog, which) -> enableRootRouting()).show();
            return;
        }
        LinearLayout content = column();
        AlertDialog[] listDialog = new AlertDialog[1];
        content.setPadding(dp(20), dp(18), dp(20), dp(8));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("应用分流", 20, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button permission = button("权限", false);
        permission.setTextSize(12);
        permission.setMinHeight(dp(32));
        permission.setPadding(dp(10), dp(6), dp(10), dp(6));
        permission.setOnClickListener(view -> showAppPermissions(screen, listDialog[0]));
        LinearLayout.LayoutParams permissionParams = new LinearLayout.LayoutParams(-2, -2);
        permissionParams.rightMargin = dp(6);
        heading.addView(permission, permissionParams);
        Button info = button("说明", false);
        info.setTextSize(12);
        info.setMinHeight(dp(32));
        info.setPadding(dp(12), dp(6), dp(12), dp(6));
        info.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("应用分流说明")
            .setMessage("• 个人选择优先于订阅规则，仅在规则模式下生效。\n\n"
                + "• 选择“跟随订阅规则”可移除个人设置。运行中保存会短暂重连。\n\n"
                + "• “系统”包含系统应用和组件，“Google”按包名归类。自定义分类可包含任意应用，只用于筛选。\n\n"
                + "• 图标按列表显示需要在后台加载，应用列表会短暂缓存；从系统设置返回后重新读取。\n\n"
                + (screen.allowRoot() ? "读取方式：普通接口优先，必要时使用你选择的 Root 补充读取应用或图标。" : "读取方式：系统应用查询接口。"))
            .setPositiveButton("知道了", null).show());
        heading.addView(info, new LinearLayout.LayoutParams(-2, -2));
        add(content, heading, 0);
        TextView count = label("", 12, MUTED, false);
        add(content, count, 8);
        TextView iconHint = label("图标按需加载 · 点此查看权限与读取状态", 11, MUTED, false);
        iconHint.setOnClickListener(view -> showAppPermissions(screen, listDialog[0]));
        add(content, iconHint, 4);
        EditText search = new EditText(this);
        search.setHint("搜索应用名称或包名");
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(14);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        search.setBackground(shape(BG, 12, true));
        search.setText(routingQuery);
        add(content, search, 14);
        Button categoryButton = button("应用  ▾", false);
        categoryButton.setSingleLine(true);
        categoryButton.setEllipsize(TextUtils.TruncateAt.END);
        Button manageCategories = button("管理分类", false);
        buttonRow(content, categoryButton, manageCategories, 10);
        List<AppCategories.Category> custom = AppCategories.load(appPreferences());
        RoutingAdapter adapter = new RoutingAdapter(screen.allowRoot());
        Runnable filter = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            routingQuery = search.getText().toString();
            List<AppCategories.Category> categories = AppCategories.all(custom);
            AppCategories.Category category = categories.stream().filter(item -> item.id().equals(routingFilter))
                .findFirst().orElse(categories.get(0));
            routingFilter = category.id();
            categoryButton.setText(category.name() + "  ▾");
            adapter.entries.clear();
            for (AppRouting.Entry app : screen.applications()) {
                if (!category.includes(app)) continue;
                if (!(app.label() + " " + app.packageName()).toLowerCase(Locale.ROOT).contains(query)) continue;
                adapter.entries.add(app);
            }
            count.setText(adapter.entries.size() + " 个应用 · 已指定 " + screen.state().applications().size() + " 个");
            adapter.notifyDataSetChanged();
        };
        categoryButton.setOnClickListener(view -> {
            List<AppCategories.Category> categories = AppCategories.all(custom);
            String[] names = categories.stream().map(AppCategories.Category::name).toArray(String[]::new);
            int selected = 0;
            for (int i = 0; i < categories.size(); i++) if (categories.get(i).id().equals(routingFilter)) selected = i;
            new AlertDialog.Builder(this).setTitle("筛选应用").setSingleChoiceItems(names, selected, (dialog, index) -> {
                dialog.dismiss();
                routingFilter = categories.get(index).id();
                filter.run();
            }).setNegativeButton("取消", null).show();
        });
        manageCategories.setOnClickListener(view -> showCategoryManager(screen, custom, filter));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter.run(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setCustomTitle(content)
            .setAdapter(adapter, null).setNegativeButton("关闭", null).create();
        listDialog[0] = dialog;
        routingIcons.observe(() -> {
            iconHint.setText(iconSummary(screen));
            adapter.notifyDataSetChanged();
        });
        dialog.setOnDismissListener(ignored -> routingIcons.stopObserving());
        filter.run();
        dialog.show();
        dialog.getListView().setDivider(null);
        dialog.getListView().setPadding(dp(10), dp(4), dp(10), dp(6));
        dialog.getListView().setClipToPadding(false);
        dialog.getListView().setOnItemClickListener((parent, view, index, id) -> {
            AppRouting.Entry app = adapter.getItem(index);
            if (app != null) showAppTarget(screen, app, dialog);
        });
        enableSearchKeyboard(dialog);
    }

    private void showCategoryManager(AppRouting.Screen screen, List<AppCategories.Category> custom, Runnable refresh) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("自定义分类")
            .setNegativeButton("关闭", null)
            .setPositiveButton("新建分类", (dialog, which) -> editCategory(screen, custom, null, refresh));
        if (custom.isEmpty()) builder.setMessage("把常用应用加入分类，方便在分流列表中查找。");
        else builder.setItems(custom.stream().map(category -> category.name() + "  ·  " + category.members().size() + " 个")
            .toArray(String[]::new), (dialog, which) -> editCategory(screen, custom, custom.get(which), refresh));
        builder.show();
    }

    private void editCategory(AppRouting.Screen screen, List<AppCategories.Category> custom,
                              AppCategories.Category existing, Runnable refresh) {
        Set<String> members = new LinkedHashSet<>();
        if (existing != null) members.addAll(existing.members());
        List<AppRouting.Entry> available = new ArrayList<>(screen.applications());
        Set<String> listed = new LinkedHashSet<>();
        for (AppRouting.Entry app : available) listed.add(app.packageName());
        for (String name : members) {
            if (!listed.contains(name)) available.add(new AppRouting.Entry(name, name, "", false, false, false));
        }

        LinearLayout form = column();
        form.setPadding(dp(16), dp(8), dp(16), dp(4));
        EditText name = subscriptionInput("分类名称", InputType.TYPE_CLASS_TEXT);
        if (existing != null) name.setText(existing.name());
        add(form, name, 0);
        EditText search = subscriptionInput("搜索应用名称或包名", InputType.TYPE_CLASS_TEXT);
        add(form, search, 8);
        TextView count = label("", 12, MUTED, false);
        add(form, count, 8);
        ListView choices = new ListView(this);
        choices.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        ArrayAdapter<String> labels = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice);
        labels.setNotifyOnChange(false);
        choices.setAdapter(labels);
        form.addView(choices, new LinearLayout.LayoutParams(-1, dp(230)));
        List<AppRouting.Entry> shown = new ArrayList<>();
        Runnable filter = () -> {
            shown.clear();
            labels.setNotifyOnChange(false);
            labels.clear();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (AppRouting.Entry app : available) {
                if (!(app.label() + " " + app.packageName()).toLowerCase(Locale.ROOT).contains(query)) continue;
                shown.add(app);
                labels.add(app.label().equals(app.packageName()) ? app.packageName() : app.label() + "\n" + app.packageName());
            }
            labels.notifyDataSetChanged();
            choices.clearChoices();
            for (int i = 0; i < shown.size(); i++) choices.setItemChecked(i, members.contains(shown.get(i).packageName()));
            count.setText("已选 " + members.size() + " 个应用");
        };
        choices.setOnItemClickListener((parent, view, index, id) -> {
            String app = shown.get(index).packageName();
            if (choices.isItemChecked(index)) members.add(app);
            else members.remove(app);
            count.setText("已选 " + members.size() + " 个应用");
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter.run(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        filter.run();
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(existing == null ? "新建分类" : "编辑分类")
            .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null);
        if (existing != null) builder.setNeutralButton("删除分类", (dialog, which) ->
            new AlertDialog.Builder(this).setTitle("删除「" + existing.name() + "」？")
                .setMessage("分类将从列表移除，应用分流规则会保留。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (confirm, action) -> {
                    List<AppCategories.Category> next = new ArrayList<>(custom);
                    next.remove(existing);
                    storeCategories(custom, next, refresh);
                }).show());
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty() || value.length() > 24) {
                name.setError("请输入 1–24 字的分类名称");
                return;
            }
            for (AppCategories.Category category : AppCategories.all(custom)) {
                if (category != existing && value.equalsIgnoreCase(category.name())) {
                    name.setError("这个分类名称已存在");
                    return;
                }
            }
            String id = existing == null ? AppCategories.CUSTOM_PREFIX + UUID.randomUUID() : existing.id();
            AppCategories.Category category = new AppCategories.Category(id, value, members);
            List<AppCategories.Category> next = new ArrayList<>(custom);
            if (existing == null) next.add(category);
            else next.set(next.indexOf(existing), category);
            if (storeCategories(custom, next, () -> {
                routingFilter = id;
                refresh.run();
            })) dialog.dismiss();
        });
    }

    private boolean storeCategories(List<AppCategories.Category> current, List<AppCategories.Category> next, Runnable refresh) {
        try {
            AppCategories.save(appPreferences(), next);
            current.clear();
            current.addAll(next);
            refresh.run();
            return true;
        } catch (org.json.JSONException error) {
            showMessage("无法保存分类，请重试。");
            return false;
        }
    }

    private void enableSearchKeyboard(AlertDialog dialog) {
        // AlertDialog's text-editor detection does not inspect its custom title.
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
    }

    private record RoutingRow(ImageView icon, TextView missingIcon, TextView name, TextView packageName, TextView policy) {}

    private final class RoutingAdapter extends BaseAdapter {
        final List<AppRouting.Entry> entries = new ArrayList<>();
        private final boolean allowRoot;

        RoutingAdapter(boolean allowRoot) { this.allowRoot = allowRoot; }

        @Override public int getCount() { return Math.max(1, entries.size()); }
        @Override public AppRouting.Entry getItem(int index) { return index < entries.size() ? entries.get(index) : null; }
        @Override public long getItemId(int index) { return index; }
        @Override public boolean isEnabled(int index) { return !entries.isEmpty(); }

        @Override public View getView(int index, View reusable, ViewGroup parent) {
            AppRouting.Entry app = getItem(index);
            if (app == null) {
                LinearLayout empty = column();
                empty.setPadding(dp(20), dp(32), dp(20), dp(32));
                TextView title = label(routingFilter.equals(AppCategories.ASSIGNED) && routingQuery.trim().isEmpty()
                    ? "还没有指定应用" : "没有匹配的应用", 15, TEXT, true);
                title.setGravity(Gravity.CENTER);
                add(empty, title, 0);
                TextView hint = label(routingFilter.equals(AppCategories.ASSIGNED) ? "在“应用”中选择一个 App，为它指定出口。"
                    : "试试其他名称，或切换到“全部”查找系统组件。", 12, MUTED, false);
                hint.setGravity(Gravity.CENTER);
                add(empty, hint, 10);
                return empty;
            }
            LinearLayout row;
            RoutingRow holder;
            if (reusable instanceof LinearLayout layout && reusable.getTag() instanceof RoutingRow existing) {
                row = layout;
                holder = existing;
            } else {
                row = column();
                row.setPadding(dp(6), dp(4), dp(6), dp(4));
                LinearLayout card = new LinearLayout(MainActivity.this);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(13), dp(12), dp(13));
                card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9), shape(0xff1f2d39, 14, false), null));
                card.setDuplicateParentStateEnabled(true);
                FrameLayout iconBox = new FrameLayout(MainActivity.this);
                iconBox.setBackground(shape(0xff2b4149, 10, false));
                iconBox.setClipToOutline(true);
                ImageView icon = new ImageView(MainActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iconBox.addView(icon, new FrameLayout.LayoutParams(-1, -1));
                TextView missingIcon = label("!", 20, AMBER, true);
                missingIcon.setGravity(Gravity.CENTER);
                iconBox.addView(missingIcon, new FrameLayout.LayoutParams(-1, -1));
                card.addView(iconBox, new LinearLayout.LayoutParams(dp(36), dp(36)));
                LinearLayout details = column();
                LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1);
                detailsParams.leftMargin = dp(10);
                card.addView(details, detailsParams);
                TextView name = label("", 15, TEXT, true);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                add(details, name, 0);
                TextView packageName = label("", 10, MUTED, false);
                packageName.setSingleLine(true);
                packageName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                add(details, packageName, 5);
                TextView policy = label("", 11, MUTED, true);
                policy.setSingleLine(true);
                policy.setEllipsize(TextUtils.TruncateAt.END);
                policy.setMaxWidth(dp(96));
                policy.setPadding(dp(9), dp(6), dp(9), dp(6));
                LinearLayout.LayoutParams policyParams = new LinearLayout.LayoutParams(-2, -2);
                policyParams.leftMargin = dp(8);
                card.addView(policy, policyParams);
                TextView arrow = label("›", 22, MUTED, false);
                LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(-2, -2);
                arrowParams.leftMargin = dp(8);
                card.addView(arrow, arrowParams);
                add(row, card, 0);
                holder = new RoutingRow(icon, missingIcon, name, packageName, policy);
                row.setTag(holder);
            }
            String name = app.label().isEmpty() ? app.packageName() : app.label();
            AppIconLoader.Result icon = routingIcons.get(app.packageName());
            boolean loaded = icon != null && icon.bitmap() != null;
            if (icon == null && app.installed()) routingIcons.request(app.packageName(), allowRoot);
            holder.icon().setImageBitmap(loaded ? icon.bitmap() : null);
            holder.icon().setContentDescription(name + "图标");
            holder.icon().setVisibility(loaded ? View.VISIBLE : View.GONE);
            holder.missingIcon().setVisibility(loaded ? View.GONE : View.VISIBLE);
            holder.missingIcon().setText(icon == null && app.installed() ? "…" : "!");
            holder.missingIcon().setTextColor(icon == null && app.installed() ? MUTED : AMBER);
            boolean denied = icon != null && icon.issue().equals("permission");
            holder.missingIcon().setContentDescription(loaded ? "" : icon == null && app.installed()
                ? "图标正在加载" : denied ? "图标读取无权限" : "图标未能读取");
            holder.name().setText(name);
            holder.packageName().setText(!app.installed() ? "未在应用列表中找到 · " + app.packageName()
                : denied ? "图标读取无权限 · " + app.packageName() : app.packageName());
            holder.policy().setText(app.target().isEmpty() ? "跟随规则" : app.target().equals("DIRECT") ? "直连" : app.target());
            holder.policy().setTextColor(app.target().isEmpty() ? MUTED : MINT);
            holder.policy().setBackground(shape(app.target().isEmpty() ? 0xff2a3946 : 0xff25473e, 9, false));
            return row;
        }
    }

    private void showAppTarget(AppRouting.Screen screen, AppRouting.Entry app, AlertDialog listDialog) {
        List<String> targets = new ArrayList<>();
        targets.add("");
        targets.addAll(screen.targets());
        String[] labels = new String[targets.size()];
        for (int i = 0; i < labels.length; i++) {
            String target = targets.get(i);
            labels[i] = target.isEmpty() ? "跟随订阅规则" : target.equals("DIRECT") ? "直连" : "代理组：" + target;
        }
        int[] chosen = {targets.indexOf(app.target())};
        new AlertDialog.Builder(this).setTitle(app.label() + " · 出口")
            .setSingleChoiceItems(labels, chosen[0], (dialog, index) -> chosen[0] = index)
            .setNeutralButton("当前连接", (dialog, which) -> showAppConnections(app))
            .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                if (chosen[0] < 0) return;
                String target = targets.get(chosen[0]);
                if (target.equals(app.target())) return;
                listDialog.dismiss();
                execute("应用分流已保存。", true, () -> {
                    RoutingStore routing = new RoutingStore(this);
                    RoutingStore.State latest = routing.load();
                    Map<String, String> rules = new TreeMap<>(latest.applications());
                    if (target.isEmpty()) rules.remove(app.packageName());
                    else rules.put(app.packageName(), target);
                    routing.apply(latest.withApplications(rules));
                    return routingLoader.load(this, appPreferences().getBoolean(ROOT_INVENTORY, false), Module.status());
                });
            }).show();
    }

    private RulesScreen readPersonalRules() throws Exception {
        RoutingStore.State state = new RoutingStore(this).load();
        List<String> targets = Profile.routingTargets(state.source());
        targets.add("REJECT");
        return new RulesScreen(state, RoutingStore.generation(), targets);
    }

    private void showPersonalRules(RulesScreen screen) {
        List<PersonalRules.Rule> rules = screen.state().rules();
        AppUi ui = new AppUi(this);
        LinearLayout heading = column();
        heading.setPadding(dp(20), dp(16), dp(20), dp(12));
        add(heading, label("自定义分流", 20, TEXT, true), 0);
        add(heading, label("应用分流 → 本列表顺序 → 订阅规则", 12, MUTED, false), 6);
        EditText search = ui.search("搜索域名、网段或出口"); add(heading, search, 12);
        TextView count = label("共 " + rules.size() + " 条 · 仅规则模式生效", 11, MUTED, false); add(heading, count, 8);
        AppUi.Summaries adapter = ui.new Summaries();
        java.util.function.Consumer<String> filter = query -> {
            List<AppUi.Summary> rows = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                PersonalRules.Rule rule = rules.get(i);
                String type = new String[] {"精确域名", "域名及子域名", "IPv4 网段", "IPv6 网段"}[java.util.Arrays.asList(PersonalRules.TYPES).indexOf(rule.type())];
                if ((type + " " + rule.type() + " " + rule.value() + " " + rule.target()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT).trim())) {
                    rows.add(new AppUi.Summary(rule.value(), type + " · 出口 " + Controller.outletLabel(rule.target()), "#" + (i + 1), i));
                }
            }
            adapter.empty(rules.isEmpty() ? "还没有自定义规则\n点击“添加规则”开始设置" : "没有匹配的规则\n试试清空搜索或更换关键词");
            adapter.set(rows); count.setText("共 " + rules.size() + " 条 · 匹配 " + rows.size() + " 条 · 规则模式生效");
        };
        filter.accept("");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.accept(s.toString()); }
            public void afterTextChanged(Editable value) {}
        });
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setCustomTitle(heading)
            .setAdapter(adapter, null)
            .setNegativeButton("关闭", null).setPositiveButton("添加规则", (dialog, which) -> editPersonalRule(screen, -1));
        AlertDialog shown = builder.show(); trackManagement(shown); ui.styleList(shown); enableSearchKeyboard(shown);
        shown.getListView().setOnItemClickListener((parent, view, position, id) -> {
            AppUi.Summary row = adapter.getItem(position); if (row == null) return;
            int index = row.sourceIndex(); shown.dismiss();
            List<String> actions = new ArrayList<>(); actions.add("编辑");
            if (index > 0) actions.add("上移"); if (index + 1 < rules.size()) actions.add("下移"); actions.add("删除");
            trackManagement(new AlertDialog.Builder(this).setTitle(rules.get(index).value())
                .setItems(actions.toArray(new String[0]), (selection, which) -> {
                    String choice = actions.get(which);
                    if (choice.equals("编辑")) { editPersonalRule(screen, index); return; }
                    List<PersonalRules.Rule> changed = new ArrayList<>(rules);
                    if (choice.equals("删除")) {
                        trackManagement(new AlertDialog.Builder(this).setTitle("删除这条规则？")
                            .setMessage(rules.get(index).type() + " · " + rules.get(index).value())
                            .setNegativeButton("取消", (confirm, buttonIndex) -> showPersonalRules(screen))
                            .setPositiveButton("删除", (confirm, item) -> { changed.remove(index); savePersonalRules(screen, changed); }).show());
                    } else {
                        int next = choice.equals("上移") ? index - 1 : index + 1;
                        if (next >= 0 && next < changed.size()) {
                            java.util.Collections.swap(changed, index, next);
                            savePersonalRules(screen, changed);
                        } else showPersonalRules(screen);
                    }
                }).setNegativeButton("返回", (selection, which) -> showPersonalRules(screen)).show());
        });
    }

    private void editPersonalRule(RulesScreen screen, int index) {
        AppUi ui = new AppUi(this);
        PersonalRules.Rule original = index < 0 ? null : screen.state().rules().get(index);
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), dp(4));
        android.widget.Spinner type = new android.widget.Spinner(this);
        type.setAdapter(ui.spinner(
            new String[] {"精确域名", "域名及子域名", "IPv4 地址／网段", "IPv6 地址／网段"}));
        type.setSelection(original == null ? 1 : java.util.Arrays.asList(PersonalRules.TYPES).indexOf(original.type()));
        add(form, type, 0);
        EditText value = subscriptionInput("example.com 或 192.0.2.0/24", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        value.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(1024)});
        if (original != null) value.setText(original.value());
        add(form, value, 8);
        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                value.setHint(position < 2 ? "example.com" : position == 2 ? "192.0.2.0/24 或单个 IPv4" : "2001:db8::/32 或单个 IPv6");
            }
        });
        add(form, label("出口", 12, MUTED, true), 12);
        android.widget.Spinner target = new android.widget.Spinner(this);
        target.setAdapter(ui.spinner(
            screen.targets().stream().map(Controller::outletLabel).toArray(String[]::new)));
        target.setSelection(original == null ? 0 : Math.max(0, screen.targets().indexOf(original.target())));
        add(form, target, 4);
        add(form, label("域名不填写协议、路径或通配符。IP 规则不额外触发域名解析，单个 IP 可省略前缀长度。", 12, MUTED, false), 10);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(original == null ? "添加分流规则" : "编辑分流规则")
            .setView(scroll).setNegativeButton("取消", (view, which) -> showPersonalRules(screen)).setPositiveButton("保存", null).show();
        trackManagement(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                PersonalRules.Rule rule = PersonalRules.create(PersonalRules.TYPES[type.getSelectedItemPosition()],
                    value.getText().toString(), screen.targets().get(target.getSelectedItemPosition()));
                List<PersonalRules.Rule> changed = new ArrayList<>(screen.state().rules());
                if (index < 0) changed.add(rule); else changed.set(index, rule);
                if (changed.size() > 2000) throw new IOException("自定义规则最多 2000 条。");
                Set<String> unique = new LinkedHashSet<>();
                for (PersonalRules.Rule item : changed) if (!unique.add(item.type() + "," + item.value())) throw new IOException("有重复的自定义规则。");
                dialog.dismiss();
                savePersonalRules(screen, changed);
            } catch (IOException error) { value.setError(error.getMessage()); }
        });
    }

    private void savePersonalRules(RulesScreen screen, List<PersonalRules.Rule> rules) {
        execute("自定义规则已保存。", true, () -> {
            if (screen.generation() != RoutingStore.generation()) throw new IOException("配置已变化，请重新打开自定义分流。");
            RoutingStore store = new RoutingStore(this);
            RoutingStore.State latest = store.load();
            store.apply(latest.withRules(rules));
            return readPersonalRules();
        });
    }

    private void showAppConnections(AppRouting.Entry app) {
        TextView result = label("", 13, TEXT, false);
        result.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(result);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(app.label() + " · 当前连接")
            .setView(scroll).setNegativeButton("关闭", null).setPositiveButton("刷新", null)
            .setNeutralButton("临时识别应用", null).create();
        dialog.show();
        Button refresh = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button identify = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        Runnable read = () -> {
            refresh.setEnabled(false);
            identify.setVisibility(View.GONE);
            result.setText("正在读取当前连接…");
            networkWorker.execute(() -> {
                LiveConnections.View snapshot;
                try { snapshot = LiveConnections.read(app.packageName(), getPackageManager()); }
                catch (Exception error) {
                    snapshot = new LiveConnections.View("暂时无法读取连接，请确认 TUN 正在运行后重试。", false);
                }
                LiveConnections.View data = snapshot;
                main.post(() -> {
                    if (isDestroyed() || !dialog.isShowing()) return;
                    result.setText(data.details());
                    scroll.scrollTo(0, 0);
                    identify.setVisibility(data.offerIdentification() ? View.VISIBLE : View.GONE);
                    refresh.setEnabled(true);
                });
            });
        };
        identify.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("临时识别应用")
            .setMessage("在本次 TUN 运行期间，Mihomo 会尝试识别新连接来自哪个应用。开启后请让该应用重新产生流量，再刷新查看。")
            .setNegativeButton("取消", null).setPositiveButton("开启", (confirm, which) -> {
                identify.setEnabled(false);
                refresh.setEnabled(false);
                worker.execute(() -> {
                    boolean enabled;
                    try {
                        Controller.identifyProcessesForSession();
                        enabled = true;
                    } catch (Exception error) { enabled = false; }
                    boolean success = enabled;
                    main.post(() -> {
                        if (isDestroyed() || !dialog.isShowing()) return;
                        identify.setEnabled(true);
                        if (success) {
                            identify.setVisibility(View.GONE);
                            result.setText("已开启临时识别。请让应用重新产生流量，再点刷新。");
                        } else {
                            result.setText("暂时无法开启识别，请确认 TUN 状态后重试。");
                        }
                        refresh.setEnabled(true);
                    });
                });
            }).show());
        refresh.setOnClickListener(view -> read.run());
        read.run();
    }

    private void runDiagnostics(Subscription.NetworkFailure subscriptionFailure) {
        if (busy) return;
        showMessage("正在检查模块、网络与节点 DNS…");
        execute("", () -> DiagnosticReport.collect(appVersion(), appVersionCode(), Build.VERSION.SDK_INT, subscriptionFailure, networkWorker));
    }

    private interface Operation { Object run() throws Exception; }

    private void execute(String success, Operation operation) {
        execute(success, false, operation);
    }

    private void execute(String success, boolean checkConnection, Operation operation) {
        if (busy) return;
        busy = true;
        main.removeCallbacks(poll);
        if (checkConnection) resetNetworkChecks();
        render();
        worker.execute(() -> {
            try {
                Object result = operation.run();
                Module.Status updatedStatus = result instanceof Module.Status updated ? updated
                    : result instanceof AppRouting.Screen screen ? screen.status() : null;
                ProxySummary summary = readProxySummary(updatedStatus);
                main.post(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    if (!success.isEmpty()) showMessage(success);
                    if (result instanceof DiagnosticReport) showMessage("");
                    if (result instanceof Module.Status updated) {
                        checkNetworkAfterStatus(updated, summary, checkConnection);
                        status = updated;
                        proxySummary = summary;
                        appPreferences().edit().putBoolean("connected", true).apply();
                    } else if (visible) {
                        if (result instanceof String logs) showLogs(logs);
                        if (result instanceof DiagnosticReport report) showDiagnosticReport(report.text());
                        if (result instanceof Controller.Mode mode) showModes(mode);
                        if (result instanceof Controller.Groups groups) showGroups(groups.values());
                        if (result instanceof RulesScreen screen) showPersonalRules(screen);
                        if (result instanceof AppRouting.Screen screen) {
                            checkNetworkAfterStatus(screen.status(), summary, checkConnection);
                            status = screen.status();
                            proxySummary = summary;
                            showAppRouting(screen);
                        }
                    }
                    render();
                    schedulePoll();
                    deliverBackgroundResults();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    showMessage(error.getMessage() == null ? "操作失败，请重新检测环境。" : error.getMessage());
                    if (checkConnection) scheduleLatency(0);
                    render();
                    schedulePoll();
                    if (visible && error instanceof Subscription.NetworkFailure failure) showSubscriptionNetworkFailure(failure);
                    if (visible && error instanceof Module.ProfileFailure failure) {
                        new AlertDialog.Builder(this).setTitle("配置校验失败").setMessage(failure.getMessage())
                            .setNegativeButton("关闭", null).setPositiveButton("内核日志", (dialog, which) ->
                                execute("", () -> Module.run(Module.Command.LOGS, null))).show();
                    }
                    deliverBackgroundResults();
                });
            }
        });
    }

    private void checkNetworkAfterStatus(Module.Status updated, ProxySummary summary, boolean requested) {
        boolean running = hasProxyControl(updated);
        if (!requested && running == hasProxyControl(status)
            && (!running || summary == null || summary.equals(proxySummary))) return;
        ProxyNotificationService.connectionChanged(this);
        resetNetworkChecks();
        scheduleLatency(0);
    }

    private boolean isActive() { return status != null && status.active(); }

    private boolean canApplySubscription() {
        return !busy && canApplySubscription(status);
    }

    private static boolean canApplySubscription(Module.Status current) {
        return current != null && current.installed() && current.enabled
            && (current.state.equals("stopped") || current.state.equals("running"));
    }

    private void render() {
        boolean installed = status != null && status.installed();
        boolean pending = status != null && !status.pendingVersion.isEmpty();
        boolean enabled = installed && status.enabled;
        boolean moduleUpdate = installed && bundledModule.valid() && status.updateAvailable(bundledModule.revision());
        if (installed) {
            stateText.setText(status.phase().label);
            stateText.setTextColor(status.running() ? MINT : TEXT);
            detailText.setText(status.profile ? status.hotspotEnabled
                ? status.running() && status.hotspotState.startsWith("active") ? "配置已就绪 · 本机与热点共享代理"
                    : "配置已就绪 · 热点共享待就绪"
                : "配置已就绪 · 本机流量接管"
                : "导入订阅或本地配置，开始连接。" );
        } else {
            stateText.setText(pending ? "等待重启" : status == null ? "准备连接" : "完成初始化");
            stateText.setTextColor(TEXT);
            detailText.setText(pending ? "重启手机后，内置模块将生效。"
                : status == null ? "先检测环境，再导入你的配置。" : "前往设置，安装 App 内置的模块。" );
        }
        boolean running = installed && status.running();
        statusBadge.setText(running ? "● TUN 就绪" : busy ? "处理中" : pending ? "待重启" : "未连接");
        statusBadge.setTextColor(running ? MINT : MUTED);
        statusBadge.setBackground(shape(running ? 0xff203a35 : 0xff25313c, 10, false));
        String setup = pending ? "模块已安装，重启手机后生效  →"
            : status != null && !installed ? "首次使用：前往设置安装内置模块  →"
            : moduleUpdate ? "模块有功能更新：" + bundledModule.version() + "，前往设置安装  →" : "";
        setupHint.setText(setup);
        setupHint.setVisibility(setup.isEmpty() ? View.GONE : View.VISIBLE);
        SubscriptionCatalog.Entry active = subscriptions == null ? null : subscriptions.active();
        String current = active != null ? "当前订阅 · " + active.name()
            : installed && status.profile ? "本地配置已就绪" : "尚未启用订阅";
        profileSummary.setText(installed && status.profile ? current : "尚未导入配置");
        if (subscriptions != null) subscriptionSummary.setText(current + (active == null ? "" : "\n"
            + SubscriptionUsage.load(appPreferences(), active.address()).summary()));
        if (busy) refreshButton.setText("处理中…");
        else refreshButton.setText(installed ? "刷新状态" : "检测环境");
        toggle.setText(status == null ? "检测环境" : !installed ? "前往安装模块" : isActive() ? "停止 TUN" : "启动 TUN");
        toggle.setEnabled(!busy && (status == null || (!installed && !pending)
            || installed && (isActive() || enabled && status.profile)));
        boolean canImport = !busy && enabled && !isActive();
        importButton.setEnabled(canImport);
        subscriptionButton.setEnabled(!busy && subscriptions != null);
        boolean subscribed = active != null && installed && status.profile;
        updateButton.setVisibility(subscribed ? View.VISIBLE : View.GONE);
        updateButton.setEnabled(canApplySubscription() && subscribed && !background.preview.running()
            && background.preview.outcome() == null);
        boolean hasControl = hasProxyControl(status);
        boolean canControl = !busy && hasControl;
        modeButton.setEnabled(canControl);
        groupsButton.setEnabled(canControl);
        String unavailable = hasControl ? "暂不可用" : "—";
        setProxyButtonText(modeButton, "代理模式", proxySummary == null ? unavailable : proxySummary.mode().label + "模式");
        setProxyButtonText(groupsButton, "代理组与节点", proxySummary == null ? unavailable : proxySummary.node());
        routingButton.setEnabled(!busy && enabled && status.profile);
        personalRulesButton.setEnabled(!busy && enabled && status.profile);
        connectionsButton.setEnabled(!busy && hasProxyControl(status));
        proxyHint.setText(installed && !status.control ? "请升级 MinClash Root 模块以使用代理控制。"
            : installed && status.state.equals("running") ? "全局模式使用 GLOBAL 组。"
            : "TUN 启动后可切换模式和节点。");
        refreshButton.setEnabled(!busy);
        networkCheckArea.setEnabled(!busy && networkChecksPending == 0);
        logsButton.setEnabled(!busy && installed);
        diagnosticsButton.setEnabled(!busy);
        checkAppUpdateButton.setEnabled(!background.updateCheck.running() && background.updateCheck.outcome() == null
            && !background.updateDownload.running() && !UpdateInstallReceiver.installing);
        checkAppUpdateButton.setText(background.updateDownload.running() ? "正在下载更新…"
            : background.updateCheck.running() ? "正在检查更新…" : "检查更新");
        repairDnsButton.setEnabled(!busy && enabled && status.profile
            && (status.state.equals("stopped") || status.state.equals("running")));
        autoUpdateSummary.setText(SubscriptionUpdateJob.summary(this, appPreferences()));
        moduleSummary.setText("内置模块 " + (bundledModule.valid() ? bundledModule.version() : "信息不可用")
            + (pending ? "\n已提交安装，重启手机后生效。"
            : installed ? "\n当前模块 " + (status.version.isEmpty() ? "版本未知" : status.version)
                + (moduleUpdate ? "\n模块功能有更新，可按需安装。" : "\n现有模块可继续使用，无需重装。")
            : "\n无需另外下载，授权 Root 后即可安装。"));
        installButton.setText(pending ? "等待重启" : moduleUpdate ? "更新模块"
            : installed ? "重新安装模块" : "安装内置模块");
        installButton.setEnabled(!busy && !pending && bundledModule.valid());
        if (installButton.isActivated() != (!installed || moduleUpdate)) styleButton(installButton, !installed || moduleUpdate);
        exportButton.setEnabled(!busy && bundledModule.valid());
        boolean backupIdle = !busy && !background.backup.running() && background.backup.outcome() == null
            && background.pendingExport == null;
        backupExportButton.setEnabled(backupIdle);
        backupImportButton.setEnabled(backupIdle);
        autoUpdateSwitch.setEnabled(!busy);
        updatingSwitch = true;
        bootSwitch.setChecked(installed && status.boot);
        bootSwitch.setEnabled(!busy && enabled);
        hotspotSwitch.setChecked(installed && status.hotspotEnabled);
        hotspotSwitch.setEnabled(!busy && enabled && status.hotspot);
        hotspotSummary.setText(installed ? status.hotspotSummary() : "安装新版内置模块并重启后可用。");
        updatingSwitch = false;
        boolean notification = notificationEnabled();
        notificationButton.setText(notification ? "关闭通知栏控制" : "开启通知栏控制");
        backgroundPermissionButton.setVisibility(notification ? View.VISIBLE : View.GONE);
        overlayPermissionButton.setVisibility(notification ? View.VISIBLE : View.GONE);
        overlayPermissionButton.setText(notification && Settings.canDrawOverlays(this) ? "检查悬浮窗权限" : "允许悬浮窗");
        foregroundAppButton.setVisibility(notification ? View.VISIBLE : View.GONE);
        foregroundAppButton.setText(notification && ForegroundApp.allowed(this) ? "当前应用显示权限" : "授权显示当前应用");
        if (visible && !busy && status != null) ProxyNotificationService.sync(this, notification && enabled && isActive());
        NotificationRecovery.sync(this, status);
        messageText.setVisibility(messageText.length() == 0 ? View.GONE : View.VISIBLE);
        warmAppRouting();
    }

    private void setProxyButtonText(Button button, String title, String summary) {
        SpannableString text = new SpannableString(title + "\n" + summary);
        text.setSpan(new RelativeSizeSpan(0.85f), title.length() + 1, text.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (button.isEnabled()) {
            int muted = (button.getCurrentTextColor() & 0x00ffffff) | 0xaa000000;
            text.setSpan(new ForegroundColorSpan(muted), title.length() + 1, text.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setText(text);
    }

    private void showMessage(String text) {
        messageText.setText(text);
        messageText.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showLogs(String logs) {
        textDialog("内核日志", logs.isEmpty() ? "暂无内核日志。" : logs).setPositiveButton("关闭", null).show();
    }

    private void showSubscriptionNetworkFailure(Subscription.NetworkFailure failure) {
        new AlertDialog.Builder(this).setTitle("订阅下载失败").setMessage(failure.getMessage())
            .setNegativeButton("关闭", null).setPositiveButton("诊断与反馈", (dialog, which) -> {
                selectPage(3);
                runDiagnostics(failure);
            }).show();
    }

    private void showDiagnosticReport(String report) {
        textDialog("诊断报告 · 公开分享版", report).setNegativeButton("关闭", null)
            .setNeutralButton("复制", (dialog, which) -> {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("MinClash 诊断报告", report));
                showMessage("已复制诊断报告，可附上故障现象和复现步骤再发送。");
            }).setPositiveButton("分享…", (dialog, which) -> {
                Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(share, "分享诊断报告"));
            }).show();
    }

    private AlertDialog.Builder textDialog(String title, String text) {
        TextView content = label(text, 12, TEXT, false);
        content.setTypeface(Typeface.MONOSPACE);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return new AlertDialog.Builder(this).setTitle(title).setView(scroll);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout page(FrameLayout parent, int index) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = column();
        body.setPadding(dp(20), dp(26), dp(20), dp(28));
        scroll.addView(body);
        parent.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        pages[index] = scroll;
        return body;
    }

    private void header(LinearLayout parent, String title, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(title, 30, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = label("v" + appVersion(), 11, MINT, true);
        version.setPadding(dp(10), dp(6), dp(10), dp(6));
        version.setBackground(shape(0xff203a35, 10, false));
        row.addView(version);
        add(parent, row, 0);
        add(parent, label(description, 13, MUTED, false), 5);
    }

    private void section(LinearLayout parent, String title) {
        add(parent, label(title, 15, TEXT, true), 24);
    }

    private void selectPage(int index) {
        if (selectedPage == 0 && index != 0) resetNetworkChecks();
        selectedPage = Math.max(0, Math.min(pages.length - 1, index));
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == selectedPage ? View.VISIBLE : View.GONE);
            navigation[i].setSelected(i == selectedPage);
            navigation[i].setTextColor(i == selectedPage ? MINT : MUTED);
            navigation[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9),
                shape(i == selectedPage ? 0xff203a35 : CARD, 12, false), null));
        }
        scheduleLatency(0);
        warmAppRouting();
    }

    private void buttonRow(LinearLayout parent, Button first, Button second, int top) {
        LinearLayout row = new LinearLayout(this);
        row.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(0, -2, 1);
        next.leftMargin = dp(10);
        row.addView(second, next);
        add(parent, row, top);
    }

    private LinearLayout card(LinearLayout parent, int top) {
        LinearLayout layout = column();
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(shape(CARD, 20, true));
        add(parent, layout, top);
        return layout;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1.08f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(50));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setStateListAnimator(null);
        button.setGravity(Gravity.CENTER);
        styleButton(button, primary);
        return button;
    }

    private void styleButton(Button button, boolean primary) {
        button.setActivated(primary);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled}, shape(0xff202c36, 14, false));
        states.addState(new int[] {}, shape(primary ? MINT : 0xff202e3a, 14, !primary));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x22000000 : 0x2279e7c9), states, null));
        button.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
            new int[] {0xff708390, primary ? Color.rgb(8, 39, 34) : TEXT}));
    }

    private GradientDrawable shape(int color, int radius, boolean border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (border) shape.setStroke(dp(1), BORDER);
        return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
