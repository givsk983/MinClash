package io.minclash.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import java.util.Set;

import static io.minclash.app.AppUi.*;

final class SubscriptionSettingsDialog {
    static AlertDialog show(Activity activity, SharedPreferences prefs, Runnable changed, Runnable permission) {
        AppUi ui = new AppUi(activity);
        LinearLayout form = ui.column(); form.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12));
        ui.add(form, ui.label("详情面板布局", 15, TEXT, true), 0);
        RadioGroup styles = new RadioGroup(activity);
        RadioButton quota = choice(activity, "额度优先 · 推荐", 1);
        RadioButton dual = choice(activity, "流量与有效期并列", 2);
        styles.addView(quota); styles.addView(dual);
        styles.check(SubscriptionPreferences.DUAL.equals(SubscriptionPreferences.style(prefs)) ? 2 : 1);
        ui.add(form, styles, 8);
        ui.add(form, ui.label("首页右上角圆环显示剩余百分比，下方显示到期倒计时。点击圆环打开详情，再按偏好选择额度优先或并列布局。", 12, MUTED, false), 6);
        Switch percentage = toggle(activity, "详情显示剩余流量百分比", prefs.getBoolean(SubscriptionPreferences.SHOW_PERCENT, true));
        ui.add(form, percentage, 14);

        ui.add(form, ui.label("订阅到期通知", 15, TEXT, true), 22);
        Switch reminders = toggle(activity, "提醒当前使用的订阅", SubscriptionPreferences.enabled(prefs));
        ui.add(form, reminders, 8);
        ui.add(form, ui.label("提前多少天提醒", 13, TEXT, false), 12);
        EditText days = new EditText(activity);
        days.setTextColor(TEXT); days.setHintTextColor(MUTED); days.setTextSize(15); days.setSingleLine(true);
        days.setInputType(InputType.TYPE_CLASS_TEXT); days.setHint("例如 7, 3, 1");
        days.setFilters(new InputFilter[] {new InputFilter.LengthFilter(120)});
        days.setText(SubscriptionPreferences.daysLabel(prefs).replace('、', ','));
        ui.add(form, days, 6);
        ui.add(form, ui.label("填写 1–30 天，以逗号分隔，可设置多个时间。每个阶段提醒一次，到期时再提醒一次；错过多个阶段时只发当前最紧急的一条。", 12, MUTED, false), 8);
        ui.add(form, ui.label("按已读取的有效期安排本机提醒，后台会定期刷新用量信息；不应用配置、不触发重连。续费并读到新有效期后重新安排。", 12, MUTED, false), 12);
        ui.add(form, ui.label("退出 App、停止 TUN 或关闭通知栏控制后仍可提醒。省电策略可能延后通知；系统强行停止后需重新打开 App 才能恢复提醒。", 12, MUTED, false), 10);
        android.widget.Button notifications = ui.button(SubscriptionReminders.canShow(activity) ? "系统通知设置" : "开启到期通知权限");
        notifications.setOnClickListener(view -> permission.run()); ui.add(form, notifications, 14);
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("订阅显示与提醒").setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            Set<String> selected;
            try { selected = SubscriptionPreferences.parseDays(days.getText().toString()); }
            catch (IllegalArgumentException invalid) { days.setError("请填写 1–30 的整数，以逗号分隔。"); return; }
            if (reminders.isChecked() && selected.isEmpty()) { days.setError("请至少选择一个提醒时间。"); return; }
            boolean saved = prefs.edit().putString(SubscriptionPreferences.STYLE, styles.getCheckedRadioButtonId() == 2 ? SubscriptionPreferences.DUAL : SubscriptionPreferences.QUOTA)
                .putBoolean(SubscriptionPreferences.SHOW_PERCENT, percentage.isChecked())
                .putBoolean(SubscriptionPreferences.REMINDERS, reminders.isChecked()).putStringSet(SubscriptionPreferences.DAYS, selected).commit();
            if (!saved) { days.setError("无法保存设置，请重试。"); return; }
            dialog.dismiss(); changed.run();
            if (reminders.isChecked() && !SubscriptionReminders.canShow(activity)) permission.run();
        }));
        dialog.show(); return dialog;
    }

    private static RadioButton choice(Activity activity, String label, int id) {
        RadioButton button = new RadioButton(activity); button.setId(id); button.setText(label); button.setTextColor(TEXT);
        button.setTextSize(14); button.setMinHeight(new AppUi(activity).dp(48)); return button;
    }
    private static Switch toggle(Activity activity, String label, boolean checked) {
        Switch toggle = new Switch(activity); toggle.setText(label); toggle.setTextColor(TEXT);
        toggle.setTextSize(14); toggle.setMinHeight(new AppUi(activity).dp(48)); toggle.setChecked(checked); return toggle;
    }
}
