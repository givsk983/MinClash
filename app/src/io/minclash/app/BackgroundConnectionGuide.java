package io.minclash.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** User-directed troubleshooting; opening this guide does not change permissions or TUN state. */
final class BackgroundConnectionGuide {
    private final Activity activity;
    private final AppUi ui;
    private final Consumer<String> message;

    BackgroundConnectionGuide(Activity activity, LinearLayout page, Consumer<String> message,
                              Runnable keepAlive, Runnable diagnostics) {
        this.activity = activity;
        this.ui = new AppUi(activity);
        this.message = message;

        LinearLayout intro = card(page, "返回后一直“连接中”？", 0);
        text(intro, "适用于 Telegram 等应用在后台停留一阵后，返回时连接等待较久、消息稍后才补齐的情况。应用被冻结、被清理或连接失效，都可能需要重新连接和同步。", 8);
        text(intro, "节点测速正常，也不能证明应用的后台连接和消息送达正常。先按下面的顺序排查。", 8);

        LinearLayout target = card(page, "01  先设置出现问题的应用", 14);
        ui.add(target, ui.label("Telegram 等消息应用需要单独设置", 13, AppUi.MINT, true), 8);
        text(target, "长按目标应用的桌面图标 → 应用信息。仅为 MinClash 放开后台权限，不能代替消息应用自身的设置。", 8);
        ui.add(target, ui.label("小米 / Redmi · MIUI / 澎湃 OS", 14, AppUi.TEXT, true), 12);
        text(target, "① 开启「自启动」或「后台自启动」。\n② 进入「省电策略」，选择「无限制」。\n③ 检查「应用联网」允许正在使用的网络；需要消息提醒时，允许该应用通知。", 6);
        text(target, "其他品牌：在目标应用的应用信息中，检查后台运行、电池限制、自启动和通知。菜单名称随系统版本变化，以手机实际页面为准。", 10);
        action(target, "Telegram 应用信息", this::openTelegram, 12);
        action(target, "其他应用设置", this::openApplications, 8);
        if (isXiaomi()) action(target, "小米自启动管理", this::openAutostart, 8);
        text(target, "按钮会打开系统页面，请找到目标应用后自行选择设置。若入口不可用，可长按应用图标进入应用信息。", 8);

        LinearLayout service = card(page, "02  检查 MinClash 是否正常运行", 14);
        text(service, "在「概览」确认 TUN 运行中。使用通知栏控制时，也为 MinClash 检查自启动和后台省电限制，帮助系统清理后恢复通知。消息应用仍需完成上一步。", 8);
        action(service, "MinClash 应用信息", () -> openDetails(activity.getPackageName()), 12);

        LinearLayout recovery = card(page, "03  仍反复重连时，再尝试保活", 14);
        text(recovery, "后台权限已配置好，返回后仍经常长时间“连接中”时，可在支持的模块上尝试「后台连接保活 → 均衡」。如提示待应用，再按页面说明重连 TUN，期间网络会短暂中断。连接正常时无需启用。", 8);
        text(recovery, "保活只帮助检测失效的 TCP 连接，不能解除应用冻结、阻止系统清理，也不保证锁屏或深度休眠时即时收消息。没有保活功能时，仍可先完成前两步。", 8);
        action(recovery, "打开后台连接保活", keepAlive, 12);

        LinearLayout verify = card(page, "04  比较实际恢复与消息送达", 14);
        text(verify, "保持网络、订阅和节点一致，让目标应用先正常连接。按 Home 返回桌面，在后台停留 2–5 分钟后再打开，观察“连接中”持续多久、消息何时补齐。重复几轮，再单独测试更长时间的锁屏。", 8);
        text(verify, "检查消息送达时，可请联系人在应用处于后台时发一条测试消息，分别记录通知到达和打开后的同步时间。短时恢复变快，不代表长时间锁屏后的消息一定即时到达。", 8);
        text(verify, "若仍有问题，检查额外的冻结、清理或省电工具是否限制了目标应用。应用更新或系统升级后，也可重新核对后台设置。", 8);

        LinearLayout next = card(page, "持续使用时也慢？", 14);
        text(next, "若应用一直在前台仍慢、TUN 状态异常，或所有应用同时断网，还需检查当前网络、应用分流和代理出口。可到「概览 → 连接管理」查看目标应用的规则与出口，或生成诊断报告继续排查。", 8);
        action(next, "生成诊断报告", diagnostics, 12);
        ui.add(page, ui.label("按需放宽后台限制：只为需要及时消息的应用设置，并观察待机耗电。允许后台运行或启用保活可能增加耗电；效果取决于设备、系统和应用。", 12, AppUi.MUTED, false), 14);
    }

    private LinearLayout card(LinearLayout parent, String title, int top) {
        LinearLayout card = ui.card();
        ui.add(card, ui.label(title, 17, AppUi.TEXT, true), 0);
        ui.add(parent, card, top);
        return card;
    }

    private void text(LinearLayout parent, String value, int top) {
        ui.add(parent, ui.label(value, 13, AppUi.MUTED, false), top);
    }

    private void action(LinearLayout parent, String title, Runnable action, int top) {
        Button button = ui.button(title);
        button.setOnClickListener(view -> action.run());
        ui.add(parent, button, top);
    }

    private static boolean isXiaomi() {
        return "Xiaomi".equalsIgnoreCase(Build.MANUFACTURER) || "Redmi".equalsIgnoreCase(Build.MANUFACTURER);
    }

    private void openTelegram() {
        PackageManager packages = activity.getPackageManager();
        List<String> installed = new ArrayList<>();
        for (String name : List.of("org.telegram.messenger", "org.telegram.messenger.web")) {
            try {
                ApplicationInfo info = packages.getApplicationInfo(name, 0);
                if ((info.flags & ApplicationInfo.FLAG_INSTALLED) != 0) installed.add(name);
            } catch (PackageManager.NameNotFoundException | SecurityException unavailable) { }
        }
        if (installed.size() == 1) {
            openDetails(installed.get(0));
        } else if (installed.size() > 1) {
            String[] choices = installed.stream().map(name -> name.endsWith(".web")
                ? "Telegram（官网版）" : "Telegram（Google Play 版）").toArray(String[]::new);
            new AlertDialog.Builder(activity).setTitle("选择要排查的 Telegram")
                .setItems(choices, (dialog, which) -> openDetails(installed.get(which)))
                .setNegativeButton("取消", null).show();
        } else {
            new AlertDialog.Builder(activity).setTitle("选择目标应用")
                .setMessage("未找到可访问的官方 Telegram 安装。使用其他客户端或应用分身时，请长按对应图标打开应用信息，或在系统应用列表中选择它。")
                .setNegativeButton("关闭", null).setPositiveButton("应用列表", (dialog, which) -> openApplications()).show();
        }
    }

    private void openAutostart() {
        try {
            activity.startActivity(new Intent().setComponent(new android.content.ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity")));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            new AlertDialog.Builder(activity).setTitle("手动检查自启动")
                .setMessage("此系统无法直接打开自启动管理。请长按要排查的应用图标进入应用信息，检查「自启动」或「后台自启动」；也可在系统应用列表中选择目标应用。")
                .setNegativeButton("关闭", null).setPositiveButton("应用列表", (dialog, which) -> openApplications()).show();
        }
    }

    private void openDetails(String packageName) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            message.accept("无法打开应用信息。请长按目标应用的桌面图标，手动检查自启动与后台省电限制。");
        }
    }

    private void openApplications() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
            } catch (ActivityNotFoundException | SecurityException missing) {
                message.accept("此设备没有可用的应用列表入口。请长按要排查的应用图标，打开应用信息。");
            }
        }
    }
}
