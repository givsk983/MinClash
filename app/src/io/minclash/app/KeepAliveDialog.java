package io.minclash.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;

/** A scrollable choice sheet: purpose first, selectable cards, then compact timings. */
final class KeepAliveDialog {
    private record Choice(TcpKeepAlive.Mode mode, String description) {
        String timing() { return "空闲阈值 " + mode.idle + " 秒 · 重试间隔 " + mode.interval + " 秒"; }
    }
    private static final Choice[] CHOICES = {
        new Choice(TcpKeepAlive.Mode.CONSERVATIVE, "降低探测频率，优先控制保活开销。"),
        new Choice(TcpKeepAlive.Mode.BALANCED, "兼顾连接可用性与探测开销。"),
        new Choice(TcpKeepAlive.Mode.AGGRESSIVE, "缩短失效检测周期，适合恢复延迟频繁的场景。")
    };

    static AlertDialog show(Context context, TcpKeepAlive.Mode current, boolean enabled,
                            Consumer<TcpKeepAlive.Mode> onSave) {
        AppUi ui = new AppUi(context);
        TcpKeepAlive.Mode[] selected = {current == TcpKeepAlive.Mode.OFF ? TcpKeepAlive.Mode.BALANCED : current};
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8));
        ui.add(body, ui.label("建议先使用均衡档，再根据连接恢复情况调整。", 13, AppUi.MUTED, false), 0);
        LinearLayout[] cards = new LinearLayout[CHOICES.length];
        RadioButton[] indicators = new RadioButton[CHOICES.length];
        Runnable update = () -> {
            for (int i = 0; i < CHOICES.length; i++) {
                boolean checked = CHOICES[i].mode() == selected[0];
                cards[i].setSelected(checked);
                indicators[i].setChecked(checked);
                GradientDrawable shape = ui.shape(checked ? 0xff1b3430 : AppUi.BG, 14);
                shape.setStroke(ui.dp(checked ? 2 : 1), checked ? AppUi.MINT : AppUi.BORDER);
                cards[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9), shape, null));
            }
        };
        for (int i = 0; i < CHOICES.length; i++) {
            Choice choice = CHOICES[i];
            LinearLayout card = new LinearLayout(context);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(ui.dp(12), ui.dp(14), ui.dp(14), ui.dp(14));
            card.setMinimumHeight(ui.dp(96));
            card.setFocusable(true);
            card.setContentDescription(choice.mode().label + (choice.mode() == TcpKeepAlive.Mode.BALANCED ? "，推荐。" : "。")
                + choice.description() + choice.timing());
            card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(host.isSelected());
                }
            });
            RadioButton indicator = new RadioButton(context);
            indicator.setButtonTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {AppUi.MINT, AppUi.MUTED}));
            indicator.setBackground(null);
            indicator.setPadding(0, 0, 0, 0);
            indicator.setClickable(false);
            indicator.setFocusable(false);
            indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(indicator, new LinearLayout.LayoutParams(ui.dp(30), ui.dp(32)));
            LinearLayout content = ui.column();
            content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(0, -2, 1);
            contentParams.leftMargin = ui.dp(8);
            card.addView(content, contentParams);
            LinearLayout heading = new LinearLayout(context);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.addView(ui.label(choice.mode().label, 16, AppUi.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
            if (choice.mode() == TcpKeepAlive.Mode.BALANCED) {
                TextView badge = ui.label("推荐", 11, AppUi.MINT, true);
                badge.setPadding(ui.dp(7), ui.dp(2), ui.dp(7), ui.dp(2));
                badge.setBackground(ui.shape(0xff203a35, 6));
                heading.addView(badge);
            }
            ui.add(content, heading, 0);
            ui.add(content, ui.label(choice.description(), 13, AppUi.TEXT, false), 5);
            ui.add(content, ui.label(choice.timing(), 12, AppUi.MUTED, false), 7);
            cards[i] = card;
            indicators[i] = indicator;
            card.setOnClickListener(view -> { selected[0] = choice.mode(); update.run(); });
            ui.add(body, card, i == 0 ? 14 : 10);
        }
        update.run();
        ui.add(body, ui.label("三档均在连续 3 次探测无响应后判定连接失效。上述参数不代表消息刷新间隔。", 12, AppUi.MUTED, false), 14);
        ui.add(body, ui.label(enabled ? "保存后请按设置页提示应用更改。" : "当前未启用，保存仅记录所选档位，不会自动开启保活。",
            12, AppUi.MUTED, false), 6);
        ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("保活强度").setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("保存设置", (view, which) -> onSave.accept(selected[0])).create();
        dialog.show();
        if (dialog.getWindow() != null) {
            int availableWidth = context.getResources().getDisplayMetrics().widthPixels - ui.dp(32);
            dialog.getWindow().setLayout(Math.min(availableWidth, ui.dp(480)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private KeepAliveDialog() {}
}
