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
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_PROFILE = 1;
    private static final int EXPORT_MODULE = 2;
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
        selectPage(savedInstanceState == null ? 0 : savedInstanceState.getInt("page", 0));
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", selectedPage);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
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
        new AlertDialog.Builder(this).setTitle("安装内置模块 " + appVersion())
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
        picker.putExtra(Intent.EXTRA_TITLE, "MinClash-Root-v" + appVersion() + ".zip");
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
            Module.run(Module.Command.IMPORT, Subscription.fetch(address));
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
                Module.run(Module.Command.IMPORT, Profile.normalize(input));
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
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("代理组与节点");
        if (groups.isEmpty()) {
            dialog.setMessage("当前配置没有可手动选择的代理组。").setPositiveButton("关闭", null).show();
            return;
        }
        String[] labels = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            Controller.Group group = groups.get(i);
            labels[i] = group.current().isEmpty() ? group.name() : group.name() + "\n" + group.current();
        }
        dialog.setItems(labels, (ignored, index) -> showNodes(groups.get(index)))
            .setNegativeButton("关闭", null).show();
    }

    private void showNodes(Controller.Group group) {
        String[] labels = group.options().toArray(new String[0]);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(group.name())
            .setSingleChoiceItems(labels, group.options().indexOf(group.current()), (choices, index) -> {
                choices.dismiss();
                selectNode(group.name(), labels[index]);
            }).setNegativeButton("取消", null);
        if (group.automatic()) dialog.setNeutralButton("恢复自动", (ignored, index) -> selectNode(group.name(), null));
        dialog.show();
    }

    private void selectNode(String group, String node) {
        execute(node == null ? "已恢复自动选点。" : "已切换到 " + node + "。", () -> {
            Controller.select(group, node);
            return Module.status();
        });
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
            : installed && !status.version.equals(appVersion()) ? "内置模块 " + appVersion() + " 已就绪，前往设置安装  →" : "";
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
        proxyHint.setText(installed && !status.control ? "请升级 MinClash Root 模块以使用代理控制。"
            : installed && status.state.equals("running") ? "全局模式使用 GLOBAL 组。"
            : "TUN 启动后可切换模式和节点。");
        refreshButton.setEnabled(!busy);
        logsButton.setEnabled(!busy && installed);
        moduleSummary.setText("内置版本 " + appVersion() + (pending ? "\n已提交安装，重启手机后生效。"
            : installed ? "\n当前模块 " + (status.version.isEmpty() ? "版本未知" : status.version)
            : "\n无需另外下载，授权 Root 后即可安装。"));
        installButton.setText(pending ? "等待重启" : installed && status.version.equals(appVersion())
            ? "重新安装模块" : "安装内置模块");
        installButton.setEnabled(!busy && !pending);
        exportButton.setEnabled(!busy);
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
