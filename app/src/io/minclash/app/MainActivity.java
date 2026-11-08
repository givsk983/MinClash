package io.minclash.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_PROFILE = 1;
    private static final int EXPORT_MODULE = 2;
    private static final int REQUEST_APP_LIST = 3;
    private static final int APP_LIST_SETTINGS = 4;
    private static final String ROOT_INVENTORY = "root_app_inventory";
    private static final String SUBSCRIPTION = "subscription";
    private static final int BG = 0xff0d131a;
    private static final int CARD = 0xff17212b;
    private static final int BORDER = 0xff293641;
    private static final int TEXT = 0xffedf4f7;
    private static final int MUTED = 0xff9baebc;
    private static final int MINT = 0xff79e7c9;
    private static final int AMBER = 0xfff5ca88;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollStatus;
    private Module.Status status;
    private BundledModule.Info bundledModule = new BundledModule.Info("", 0, 0);
    private boolean busy;
    private boolean polling;
    private boolean visible;
    private boolean updatingSwitch;
    private TextView stateText;
    private TextView detailText;
    private TextView messageText;
    private Button toggle;
    private Button importButton;
    private Button subscriptionButton;
    private Button updateButton;
    private Button modeButton;
    private Button groupsButton;
    private Button routingButton;
    private int routingFilter;
    private String routingQuery = "";
    private final AppRouting.Loader routingLoader = new AppRouting.Loader();
    private AppIconLoader routingIcons;
    private TextView proxyHint;
    private Button refreshButton;
    private Button logsButton;
    private Switch bootSwitch;
    private Button installButton;
    private Button exportButton;
    private TextView moduleSummary;
    private TextView profileSummary;
    private TextView statusBadge;
    private TextView setupHint;
    private final View[] pages = new View[4];
    private final Button[] navigation = new Button[4];
    private int selectedPage;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        routingIcons = new AppIconLoader(this);
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
            execute("", () -> {
                Module.run(isActive() ? Module.Command.STOP : Module.Command.START, null);
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
        section(overview, "代理控制");
        modeButton = button("代理模式", false);
        modeButton.setOnClickListener(view -> execute("", Controller::mode));
        groupsButton = button("代理组与节点", false);
        groupsButton.setOnClickListener(view -> execute("", Controller::groups));
        buttonRow(overview, modeButton, groupsButton, 10);
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
        add(settings, label("导入配置", 17, TEXT, true), 0);
        add(settings, label("使用 Clash / Mihomo 格式的订阅或 YAML 文件。", 13, MUTED, false), 8);
        subscriptionButton = button("导入订阅链接", true);
        subscriptionButton.setOnClickListener(view -> promptSubscription());
        add(settings, subscriptionButton, 20);
        updateButton = button("更新当前订阅", false);
        updateButton.setOnClickListener(view -> importSubscription(savedSubscription()));
        add(settings, updateButton, 10);
        importButton = button("导入 Mihomo YAML", false);
        importButton.setOnClickListener(view -> pickProfile());
        add(settings, importButton, 10);
        add(settings, label("更换配置前，请先停止连接。", 12, MUTED, false), 14);
        LinearLayout routing = card(profiles, 14);
        add(routing, label("应用分流", 17, TEXT, true), 0);
        add(routing, label("给应用指定直连或代理组。你的设置优先于机场规则，更新订阅后继续保留。", 13, MUTED, false), 8);
        routingButton = button("管理应用分流", false);
        routingButton.setOnClickListener(view -> openAppRouting());
        add(routing, routingButton, 16);
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
        add(service, label("手机启动后自动连接，需先导入有效配置。", 12, MUTED, false), 6);

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
        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("ic_launcher", "drawable", getPackageName()));
        logo.setBackground(shape(0xff132c32, 16, false));
        logo.setClipToOutline(true);
        logo.setContentDescription("MinClash 图标");
        identity.addView(logo, new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView applicationName = label("MinClash", 24, TEXT, true);
        applicationName.setGravity(Gravity.CENTER);
        add(identity, applicationName, 16);
        TextView applicationVersion = label("版本 " + appVersion(), 12, MINT, true);
        applicationVersion.setGravity(Gravity.CENTER);
        add(identity, applicationVersion, 6);
        TextView description = label("轻量的 Android Root TUN 工具\n管理订阅、代理模式与节点连接", 13, MUTED, false);
        description.setGravity(Gravity.CENTER);
        add(identity, description, 14);

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
        selectPage(savedInstanceState == null ? 0 : savedInstanceState.getInt("page", 0));
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", selectedPage);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        // Permissions and installed packages may have changed while outside the app.
        routingLoader.invalidate();
        routingIcons.invalidate();
        visible = true;
        if (getPreferences(MODE_PRIVATE).getBoolean("connected", false)) main.post(poll);
    }

    @Override protected void onStop() {
        visible = false;
        main.removeCallbacks(poll);
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(poll);
        routingIcons.close();
        worker.shutdown();
        super.onDestroy();
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
                main.post(() -> {
                    if (isDestroyed()) return;
                    polling = false;
                    status = updated;
                    render();
                    schedulePoll();
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
        if (visible && !busy && isActive()) main.postDelayed(poll, 4000);
    }

    private String savedSubscription() {
        return getPreferences(MODE_PRIVATE).getString(SUBSCRIPTION, "");
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
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

    private void promptSubscription() {
        EditText input = new EditText(this);
        input.setHint("https://");
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(14);
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        input.setBackground(shape(BG, 12, true));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(4), dp(20), 0);
        add(content, input, 0);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("导入订阅链接")
            .setMessage("粘贴机场提供的 Clash / Mihomo 订阅链接。")
            .setView(content).setNegativeButton("取消", null).setPositiveButton("下载并导入", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String address = input.getText().toString().trim();
            if (address.isEmpty()) {
                input.setError("请输入订阅链接。");
                return;
            }
            dialog.dismiss();
            importSubscription(address);
        });
    }

    private void importSubscription(String address) {
        execute("订阅已通过 Mihomo 校验并保存。", () -> {
            new RoutingStore(this).importProfile(Subscription.fetch(address));
            getPreferences(MODE_PRIVATE).edit().putString(SUBSCRIPTION, address).apply();
            return Module.status();
        });
    }

    private void pickProfile() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("*/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(picker, PICK_PROFILE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == APP_LIST_SETTINGS) {
            refreshAppRouting(getPreferences(MODE_PRIVATE).getBoolean(ROOT_INVENTORY, false));
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
                new RoutingStore(this).importProfile(Profile.normalize(input));
            }
            getPreferences(MODE_PRIVATE).edit().remove(SUBSCRIPTION).apply();
            return Module.status();
        });
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
                    execute("已切换到" + mode.label + "模式。", () -> {
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
        List<PolicyItem> items = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            Controller.Group group = groups.get(i);
            items.add(new PolicyItem(group.name(), "当前出口 · " + groupRoute(group, groups),
                group.current().equals("DIRECT") ? "直连" : group.automatic() ? "自动" : "手动", false, true, i));
        }
        showPolicyList("代理组与节点", groups.size() + " 个代理组 · 点击查看可选节点", "搜索代理组或当前出口", items,
            (dialog, index) -> { dialog.dismiss(); showNodes(groups.get(index), groups); }, null);
    }

    private String groupRoute(Controller.Group group, List<Controller.Group> groups) {
        String current = group.current();
        StringBuilder route = new StringBuilder();
        java.util.Set<String> visited = new java.util.HashSet<>();
        for (int depth = 0; depth < 6 && !current.isEmpty() && visited.add(current); depth++) {
            if (route.length() > 0) route.append(" → ");
            route.append(current.equals("DIRECT") ? "直连" : current.equals("REJECT") ? "拒绝连接" : current);
            String next = "";
            for (Controller.Group candidate : groups) if (candidate.name().equals(current)) { next = candidate.current(); break; }
            current = next;
        }
        return route.length() == 0 ? "尚未选择" : route.toString();
    }

    private void showNodes(Controller.Group group, List<Controller.Group> groups) {
        List<PolicyItem> items = new ArrayList<>();
        for (int i = 0; i < group.options().size(); i++) {
            String option = group.options().get(i);
            String detail = option.equals("DIRECT") ? "直接连接，不经过代理" : "代理节点";
            for (Controller.Group candidate : groups) if (candidate.name().equals(option)) {
                detail = "代理组 · " + groupRoute(candidate, groups);
                break;
            }
            boolean selected = option.equals(group.current());
            items.add(new PolicyItem(option.equals("DIRECT") ? "直连" : option, detail,
                selected ? "当前" : "", selected, false, i));
        }
        showPolicyList(group.name() + " · 选择出口", group.options().size() + " 个选项 · 点击切换", "搜索节点或代理组", items,
            (dialog, index) -> {
                dialog.dismiss();
                String option = group.options().get(index);
                if (!option.equals(group.current())) selectNode(group.name(), option);
            }, group.automatic() ? () -> selectNode(group.name(), null) : null);
    }

    private record PolicyItem(String title, String detail, String badge, boolean selected, boolean navigates, int index) {}

    private void showPolicyList(String title, String subtitle, String hint, List<PolicyItem> items,
                                BiConsumer<AlertDialog, Integer> onPick, Runnable reset) {
        LinearLayout header = column();
        header.setPadding(dp(20), dp(20), dp(20), dp(8));
        add(header, label(title, 20, TEXT, true), 0);
        add(header, label(subtitle, 12, MUTED, false), 8);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(14);
        search.setHint(hint);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        search.setBackground(shape(BG, 12, true));
        add(header, search, 14);
        PolicyAdapter adapter = new PolicyAdapter();
        adapter.entries.addAll(items);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                String query = text.toString().trim().toLowerCase(Locale.ROOT);
                adapter.entries.clear();
                for (PolicyItem item : items) {
                    if ((item.title() + " " + item.detail()).toLowerCase(Locale.ROOT).contains(query)) adapter.entries.add(item);
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setCustomTitle(header)
            .setAdapter(adapter, null).setNegativeButton("关闭", null);
        if (reset != null) builder.setNeutralButton("恢复自动", (dialog, which) -> reset.run());
        AlertDialog dialog = builder.show();
        dialog.getListView().setDivider(null);
        dialog.getListView().setPadding(dp(10), dp(4), dp(10), dp(6));
        dialog.getListView().setClipToPadding(false);
        dialog.getListView().setOnItemClickListener((parent, view, index, id) -> {
            PolicyItem item = adapter.getItem(index);
            if (item != null) onPick.accept(dialog, item.index());
        });
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private record PolicyRow(LinearLayout card, TextView title, TextView detail, TextView badge, TextView arrow) {}

    private final class PolicyAdapter extends BaseAdapter {
        final List<PolicyItem> entries = new ArrayList<>();
        @Override public int getCount() { return Math.max(1, entries.size()); }
        @Override public PolicyItem getItem(int index) { return index < entries.size() ? entries.get(index) : null; }
        @Override public long getItemId(int index) { return index; }
        @Override public boolean isEnabled(int index) { return !entries.isEmpty(); }
        @Override public View getView(int index, View reusable, ViewGroup parent) {
            PolicyItem item = getItem(index);
            if (item == null) {
                TextView empty = label("没有匹配的项目", 14, MUTED, false);
                empty.setPadding(dp(20), dp(32), dp(20), dp(32));
                empty.setGravity(Gravity.CENTER);
                return empty;
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
                TextView badge = label("", 11, MINT, true);
                badge.setSingleLine(true);
                badge.setPadding(dp(9), dp(6), dp(9), dp(6));
                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
                badgeParams.leftMargin = dp(10);
                card.addView(badge, badgeParams);
                TextView arrow = label("", 20, MUTED, false);
                LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(-2, -2);
                arrowParams.leftMargin = dp(9);
                card.addView(arrow, arrowParams);
                add(row, card, 0);
                holder = new PolicyRow(card, name, detail, badge, arrow);
                row.setTag(holder);
            }
            holder.title().setText(item.title());
            holder.detail().setText(item.detail());
            holder.badge().setText(item.badge());
            holder.badge().setVisibility(item.badge().isEmpty() ? View.GONE : View.VISIBLE);
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
        execute(node == null ? "已恢复自动选点。" : "已切换到 " + node + "。", () -> {
            Controller.select(group, node);
            return Module.status();
        });
    }

    private void openAppRouting() {
        routingFilter = 0;
        routingQuery = "";
        boolean allowRoot = getPreferences(MODE_PRIVATE).getBoolean(ROOT_INVENTORY, false);
        if (InstalledAppsAccess.needsRequest(this)) {
            requestPermissions(new String[] {InstalledAppsAccess.RUNTIME_PERMISSION}, REQUEST_APP_LIST);
            return;
        }
        loadAppRouting(allowRoot);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_APP_LIST) refreshAppRouting(getPreferences(MODE_PRIVATE).getBoolean(ROOT_INVENTORY, false));
    }

    private void loadAppRouting(boolean allowRoot) {
        Module.Status current = status;
        execute("", () -> routingLoader.load(this, allowRoot, current));
    }

    private void refreshAppRouting(boolean allowRoot) {
        routingLoader.invalidate();
        routingIcons.invalidate();
        loadAppRouting(allowRoot);
    }

    private void enableRootRouting() {
        routingLoader.invalidate();
        routingIcons.invalidate();
        Module.Status current = status;
        execute("", () -> {
            AppRouting.Screen loaded = routingLoader.load(this, true, current);
            getPreferences(MODE_PRIVATE).edit().putBoolean(ROOT_INVENTORY, true).apply();
            return loaded;
        });
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
                else refreshAppRouting(getPreferences(MODE_PRIVATE).getBoolean(ROOT_INVENTORY, false));
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
                + "• “应用”显示用户安装的应用和可打开的系统应用；系统组件可在“全部”中查找。\n\n"
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
        LinearLayout tabs = new LinearLayout(this);
        Button[] filters = new Button[3];
        String[] filterNames = {"应用", "已指定", "全部"};
        for (int i = 0; i < filters.length; i++) {
            filters[i] = button(filterNames[i], false);
            filters[i].setTextSize(12);
            filters[i].setMinHeight(dp(34));
            filters[i].setPadding(dp(4), dp(8), dp(4), dp(8));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) params.leftMargin = dp(6);
            tabs.addView(filters[i], params);
        }
        add(content, tabs, 12);
        RoutingAdapter adapter = new RoutingAdapter(screen.allowRoot());
        Runnable filter = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            routingQuery = search.getText().toString();
            adapter.entries.clear();
            for (AppRouting.Entry app : screen.applications()) {
                boolean show = switch (routingFilter) {
                    case 1 -> !app.target().isEmpty();
                    case 2 -> true;
                    default -> app.userFacing();
                };
                if (!show) continue;
                if (!(app.label() + " " + app.packageName()).toLowerCase(Locale.ROOT).contains(query)) continue;
                adapter.entries.add(app);
            }
            count.setText(adapter.entries.size() + " 个应用 · 已指定 " + screen.state().applications().size() + " 个");
            adapter.notifyDataSetChanged();
            for (int i = 0; i < filters.length; i++) {
                boolean selected = i == routingFilter;
                filters[i].setTextColor(selected ? BG : MUTED);
                filters[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9),
                    shape(selected ? MINT : 0xff202e3a, 10, false), null));
            }
        };
        for (int i = 0; i < filters.length; i++) {
            final int index = i;
            filters[i].setOnClickListener(view -> { routingFilter = index; filter.run(); });
        }
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
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
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
                TextView title = label(routingFilter == 1 && routingQuery.trim().isEmpty()
                    ? "还没有指定应用" : "没有匹配的应用", 15, TEXT, true);
                title.setGravity(Gravity.CENTER);
                add(empty, title, 0);
                TextView hint = label(routingFilter == 1 ? "在“应用”中选择一个 App，为它指定出口。"
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
            .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                if (chosen[0] < 0) return;
                String target = targets.get(chosen[0]);
                if (target.equals(app.target())) return;
                Map<String, String> rules = new TreeMap<>(screen.state().applications());
                if (target.isEmpty()) rules.remove(app.packageName());
                else rules.put(app.packageName(), target);
                listDialog.dismiss();
                execute("应用分流已保存。", () -> {
                    new RoutingStore(this).apply(new RoutingStore.State(screen.state().source(), rules));
                    return routingLoader.load(this, getPreferences(MODE_PRIVATE).getBoolean(ROOT_INVENTORY, false), Module.status());
                });
            }).show();
    }

    private interface Operation { Object run() throws Exception; }

    private void execute(String success, Operation operation) {
        if (busy) return;
        busy = true;
        main.removeCallbacks(poll);
        render();
        worker.execute(() -> {
            try {
                Object result = operation.run();
                main.post(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    if (!success.isEmpty()) showMessage(success);
                    if (result instanceof Module.Status updated) {
                        status = updated;
                        getPreferences(MODE_PRIVATE).edit().putBoolean("connected", true).apply();
                    } else if (visible) {
                        if (result instanceof String logs) showLogs(logs);
                        if (result instanceof Controller.Mode mode) showModes(mode);
                        if (result instanceof Controller.Groups groups) showGroups(groups.values());
                        if (result instanceof AppRouting.Screen screen) {
                            status = screen.status();
                            showAppRouting(screen);
                        }
                    }
                    render();
                    schedulePoll();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    showMessage(error.getMessage() == null ? "操作失败，请重新检测环境。" : error.getMessage());
                    render();
                    schedulePoll();
                });
            }
        });
    }

    private boolean isActive() { return status != null && status.installed() && !status.state.equals("stopped"); }

    private void render() {
        boolean installed = status != null && status.installed();
        boolean pending = status != null && !status.pendingVersion.isEmpty();
        boolean enabled = installed && status.enabled;
        boolean moduleUpdate = installed && bundledModule.valid() && status.updateAvailable(bundledModule.revision());
        if (installed) {
            stateText.setText(switch (status.state) {
                case "running" -> "TUN 运行中";
                case "starting" -> "正在建立 TUN";
                case "retrying" -> "等待重试";
                case "orphaned" -> "需要停止并清理";
                default -> enabled ? "已停止" : "模块已禁用";
            });
            stateText.setTextColor(status.state.equals("running") ? MINT : TEXT);
            detailText.setText(status.profile ? "配置已就绪 · 本机流量接管" : "导入订阅或本地配置，开始连接。" );
        } else {
            stateText.setText(pending ? "等待重启" : status == null ? "准备连接" : "完成初始化");
            stateText.setTextColor(TEXT);
            detailText.setText(pending ? "重启手机后，内置模块将生效。"
                : status == null ? "先检测环境，再导入你的配置。" : "前往设置，安装 App 内置的模块。" );
        }
        boolean running = installed && status.state.equals("running");
        statusBadge.setText(running ? "● 在线" : busy ? "处理中" : pending ? "待重启" : "未连接");
        statusBadge.setTextColor(running ? MINT : MUTED);
        statusBadge.setBackground(shape(running ? 0xff203a35 : 0xff25313c, 10, false));
        String setup = pending ? "模块已安装，重启手机后生效  →"
            : status != null && !installed ? "首次使用：前往设置安装内置模块  →"
            : moduleUpdate ? "模块有功能更新：" + bundledModule.version() + "，前往设置安装  →" : "";
        setupHint.setText(setup);
        setupHint.setVisibility(setup.isEmpty() ? View.GONE : View.VISIBLE);
        profileSummary.setText(installed && status.profile
            ? (savedSubscription().isEmpty() ? "本地配置已就绪" : "订阅配置已就绪") : "尚未导入配置");
        if (busy) refreshButton.setText("处理中…");
        else refreshButton.setText(installed ? "刷新状态" : "检测环境");
        toggle.setText(status == null ? "检测环境" : !installed ? "前往安装模块" : isActive() ? "停止 TUN" : "启动 TUN");
        toggle.setEnabled(!busy && (status == null || (!installed && !pending)
            || installed && (isActive() || enabled && status.profile)));
        boolean canImport = !busy && enabled && !isActive();
        importButton.setEnabled(canImport);
        subscriptionButton.setEnabled(canImport);
        boolean subscribed = !savedSubscription().isEmpty() && installed && status.profile;
        updateButton.setVisibility(subscribed ? View.VISIBLE : View.GONE);
        updateButton.setEnabled(canImport && subscribed);
        boolean canControl = !busy && enabled && status.control && status.state.equals("running");
        modeButton.setEnabled(canControl);
        groupsButton.setEnabled(canControl);
        routingButton.setEnabled(!busy && enabled && status.profile);
        proxyHint.setText(installed && !status.control ? "请升级 MinClash Root 模块以使用代理控制。"
            : installed && status.state.equals("running") ? "全局模式使用 GLOBAL 组。"
            : "TUN 启动后可切换模式和节点。");
        refreshButton.setEnabled(!busy);
        logsButton.setEnabled(!busy && installed);
        moduleSummary.setText("内置模块 " + (bundledModule.valid() ? bundledModule.version() : "信息不可用")
            + (pending ? "\n已提交安装，重启手机后生效。"
            : installed ? "\n当前模块 " + (status.version.isEmpty() ? "版本未知" : status.version)
                + (moduleUpdate ? "\n模块功能有更新，可按需安装。" : "\n现有模块可继续使用，无需重装。")
            : "\n无需另外下载，授权 Root 后即可安装。"));
        installButton.setText(pending ? "等待重启" : moduleUpdate ? "更新模块"
            : installed ? "重新安装模块" : "安装内置模块");
        installButton.setEnabled(!busy && !pending && bundledModule.valid());
        exportButton.setEnabled(!busy && bundledModule.valid());
        updatingSwitch = true;
        bootSwitch.setChecked(installed && status.boot);
        bootSwitch.setEnabled(!busy && enabled);
        updatingSwitch = false;
        messageText.setVisibility(messageText.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void showMessage(String text) {
        messageText.setText(text);
        messageText.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showLogs(String logs) {
        TextView content = label(logs.isEmpty() ? "暂无内核日志。" : logs, 12, TEXT, false);
        content.setTypeface(Typeface.MONOSPACE);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("内核日志").setView(scroll)
            .setPositiveButton("关闭", null).show();
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
        selectedPage = Math.max(0, Math.min(pages.length - 1, index));
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == selectedPage ? View.VISIBLE : View.GONE);
            navigation[i].setSelected(i == selectedPage);
            navigation[i].setTextColor(i == selectedPage ? MINT : MUTED);
            navigation[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9),
                shape(i == selectedPage ? 0xff203a35 : CARD, 12, false), null));
        }
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
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled}, shape(0xff202c36, 14, false));
        states.addState(new int[] {}, shape(primary ? MINT : 0xff202e3a, 14, !primary));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x22000000 : 0x2279e7c9), states, null));
        button.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
            new int[] {0xff708390, primary ? Color.rgb(8, 39, 34) : TEXT}));
        return button;
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
