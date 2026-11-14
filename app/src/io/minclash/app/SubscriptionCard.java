package io.minclash.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

import static io.minclash.app.AppUi.*;

/** Both layouts bind the same snapshot; neither depends on TUN state. */
final class SubscriptionCard extends LinearLayout {
    private static final int RED = 0xffff9a9e;
    private final AppUi ui;
    private final TextView name, largeRemaining, largeExpiry, largeDate, compactRemaining, compactExpiry, compactDate;
    private final TextView used, total, percent, warning, freshness, reminder, badge;
    private final LinearLayout quota, dual;
    private final Meter meter;
    private final Button refresh;

    SubscriptionCard(Context context, Runnable details, Runnable refreshUsage, Runnable settings) {
        super(context);
        ui = new AppUi(context);
        setOrientation(VERTICAL); setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18));
        setBackground(ui.shape(CARD, 18));
        LinearLayout heading = row();
        name = ui.label("当前订阅", 14, TEXT, true);
        heading.addView(name, new LayoutParams(0, -2, 1));
        badge = ui.label("当前使用", 11, MINT, true);
        badge.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); badge.setBackground(ui.shape(0xff203a35, 8));
        heading.addView(badge); ui.add(this, heading, 0);

        quota = ui.column();
        ui.add(quota, ui.label("剩余流量", 13, MUTED, false), 0);
        largeRemaining = ui.label("未提供", 40, TEXT, true); ui.add(quota, largeRemaining, 5);
        ui.add(this, quota, 20);

        dual = row(); dual.setGravity(Gravity.TOP);
        LinearLayout left = ui.column(), right = ui.column();
        ui.add(left, ui.label("剩余流量", 12, MUTED, false), 0);
        compactRemaining = ui.label("未提供", 28, TEXT, true); ui.add(left, compactRemaining, 6);
        ui.add(right, ui.label("订阅有效期", 12, MUTED, false), 0);
        compactExpiry = ui.label("未提供", 22, TEXT, true); ui.add(right, compactExpiry, 8);
        compactDate = ui.label("", 11, MUTED, false); ui.add(right, compactDate, 6);
        dual.addView(left, new LayoutParams(0, -2, 1));
        LayoutParams rightParams = new LayoutParams(0, -2, 1); rightParams.leftMargin = ui.dp(14);
        dual.addView(right, rightParams); ui.add(this, dual, 20);

        LinearLayout quotaMeta = row();
        total = ui.label("", 12, MUTED, false); quotaMeta.addView(total, new LayoutParams(0, -2, 1));
        percent = ui.label("", 12, MINT, true); quotaMeta.addView(percent);
        ui.add(this, quotaMeta, 12);
        meter = new Meter(context); LayoutParams meterParams = new LayoutParams(-1, ui.dp(8)); meterParams.topMargin = ui.dp(9);
        addView(meter, meterParams);
        used = ui.label("", 12, MUTED, false); ui.add(this, used, 8);

        LinearLayout expiry = ui.column();
        View divider = new View(context); divider.setBackgroundColor(BORDER);
        LayoutParams dividerParams = new LayoutParams(-1, ui.dp(1)); dividerParams.bottomMargin = ui.dp(14);
        expiry.addView(divider, dividerParams);
        largeExpiry = ui.label("", 22, TEXT, true); ui.add(expiry, largeExpiry, 0);
        largeDate = ui.label("", 12, MUTED, false); ui.add(expiry, largeDate, 4);
        ui.add(this, expiry, 18); quota.setTag(expiry);

        warning = ui.label("", 12, AMBER, false); ui.add(this, warning, 12);
        freshness = ui.label("", 11, MUTED, false); ui.add(this, freshness, 14);
        reminder = ui.label("", 12, MUTED, false);
        if (settings != null) {
            reminder.setPadding(0, ui.dp(10), 0, ui.dp(10)); reminder.setMinHeight(ui.dp(44));
            reminder.setOnClickListener(view -> settings.run()); reminder.setFocusable(true);
            ui.add(this, reminder, 2);
        }
        if (details != null && refreshUsage != null) {
            LinearLayout controls = row();
            Button detail = ui.button("用量详情"); detail.setOnClickListener(view -> details.run());
            refresh = ui.button("刷新用量"); refresh.setOnClickListener(view -> refreshUsage.run());
            controls.addView(detail, new LayoutParams(0, -2, 1));
            LayoutParams refreshParams = new LayoutParams(0, -2, 1); refreshParams.leftMargin = ui.dp(10);
            controls.addView(refresh, refreshParams); ui.add(this, controls, 10);
        } else refresh = null;
    }

    void bind(SubscriptionCatalog.Entry entry, SubscriptionUsage usage, SharedPreferences prefs, long now, boolean refreshing) {
        bind(entry, usage, prefs, now, refreshing, true);
    }
    void bind(SubscriptionCatalog.Entry entry, SubscriptionUsage usage, SharedPreferences prefs, long now, boolean refreshing, boolean current) {
        name.setText(entry.name());
        badge.setVisibility(current ? VISIBLE : GONE);
        boolean compact = SubscriptionPreferences.DUAL.equals(SubscriptionPreferences.style(prefs));
        quota.setVisibility(compact ? GONE : VISIBLE); dual.setVisibility(compact ? VISIBLE : GONE);
        ((View) quota.getTag()).setVisibility(compact ? GONE : VISIBLE);
        value(largeRemaining, usage.remaining()); value(compactRemaining, usage.remaining());
        largeExpiry.setText(usage.expiryLabel(now)); compactExpiry.setText(usage.expiryLabel(now));
        largeDate.setText(usage.expiryDate()); compactDate.setText(usage.expiryDate());
        long left = usage.expire() < 0 ? Long.MAX_VALUE : usage.expire() * 1000 - now;
        int expiryColor = left <= SubscriptionReminderPolicy.DAY ? RED : left <= 7 * SubscriptionReminderPolicy.DAY ? AMBER : TEXT;
        largeExpiry.setTextColor(expiryColor); compactExpiry.setTextColor(expiryColor);
        double ratio = usage.remainingRatio();
        int quotaColor = ratio >= 0 && ratio <= .05 ? RED : ratio >= 0 && ratio <= .2 ? AMBER : MINT;
        meter.ratio = ratio; meter.color = quotaColor; meter.invalidate(); meter.setVisibility(ratio < 0 ? GONE : VISIBLE);
        meter.setContentDescription(ratio < 0 ? "剩余流量未提供" : String.format(Locale.getDefault(), "剩余流量 %.1f%%", ratio * 100));
        percent.setVisibility(prefs.getBoolean(SubscriptionPreferences.SHOW_PERCENT, true) && ratio >= 0 ? VISIBLE : GONE);
        percent.setText(ratio < 0 ? "" : String.format(Locale.getDefault(), "剩余 %.1f%%", ratio * 100)); percent.setTextColor(quotaColor);
        total.setText("总额 " + SubscriptionUsage.bytes(usage.total()));
        used.setText("已用 " + SubscriptionUsage.bytes(usage.used()));
        String notice = left <= 0 ? "剩余流量不代表仍可使用，请续费后刷新。"
            : left <= 7 * SubscriptionReminderPolicy.DAY ? "订阅即将到期，建议提前续费。" : "";
        if (ratio >= 0 && ratio <= .2) notice += (notice.isEmpty() ? "" : "\n")
            + (usage.remaining() == 0 ? "流量已用尽，请检查服务商套餐。" : "剩余流量不足，请留意套餐余量。");
        warning.setText(notice); warning.setVisibility(notice.isEmpty() ? GONE : VISIBLE);
        freshness.setText(usage.freshness(now));
        reminder.setText(SubscriptionReminders.summary(getContext(), prefs, usage) + "  ›");
        if (refresh != null) { refresh.setEnabled(!refreshing); refresh.setText(refreshing ? "读取中…" : "刷新用量"); }
    }

    private void value(TextView view, long bytes) {
        String text = SubscriptionUsage.bytes(bytes); SpannableString styled = new SpannableString(text);
        int space = text.indexOf(' ');
        if (space >= 0) styled.setSpan(new RelativeSizeSpan(.48f), space, text.length(), 0);
        view.setText(styled);
    }
    private LinearLayout row() { LinearLayout result = new LinearLayout(getContext()); result.setGravity(Gravity.CENTER_VERTICAL); return result; }

    private static final class Meter extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        double ratio = -1; int color = MINT;
        Meter(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = getHeight() / 2f;
            paint.setColor(BORDER); canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            if (ratio > 0) {
                canvas.save(); canvas.clipRect(0, 0, (float) (getWidth() * ratio), getHeight());
                paint.setColor(color); canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint); canvas.restore();
            }
        }
    }
}
