package io.minclash.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

final class TrafficCard extends LinearLayout {
    private final TextView upload;
    private final TextView download;
    private final TrafficMonitor.Observer observer = this::bind;

    TrafficCard(Context context) {
        super(context);
        AppUi ui = new AppUi(context);
        setOrientation(VERTICAL);
        setFocusable(true);
        setScreenReaderFocusable(true);
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12));
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9), ui.overviewCardSurface(), null));

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.label("实时流量", 15, AppUi.TEXT, true);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LayoutParams(0, -2, 1));
        heading.addView(ui.label("流量详情 ›", 12, AppUi.MUTED, false));
        ui.add(this, heading, 0);

        LinearLayout rates = new LinearLayout(context);
        rates.setBaselineAligned(false);
        download = addRate(ui, rates, "↓ 下载");
        upload = addRate(ui, rates, "↑ 上传");
        ui.add(this, rates, 8);
    }

    private TextView addRate(AppUi ui, LinearLayout rates, String title) {
        LinearLayout column = ui.column();
        column.setPaddingRelative(0, 0, ui.dp(8), 0);
        ui.add(column, ui.label(title, 12, AppUi.MUTED, false), 0);
        TextView value = ui.label("—", 22, AppUi.TEXT, true);
        value.setFontFeatureSettings("tnum");
        ui.add(column, value, 1);
        rates.addView(column, new LayoutParams(0, -2, 1));
        return value;
    }

    void setActive(boolean active) {
        if (active) TrafficMonitor.INSTANCE.observe(getContext(), observer, false);
        else TrafficMonitor.INSTANCE.stopObserving(observer);
    }

    private void bind(TrafficMonitor.Reading reading, TrafficMonitor.Rate rate) {
        setRate(download, rate == null ? -1 : rate.download());
        setRate(upload, rate == null ? -1 : rate.upload());
    }

    private void setRate(TextView target, long rate) {
        String formatted = TrafficMonitor.formatRate(rate);
        if (formatted.contentEquals(target.getText())) return;
        SpannableString text = new SpannableString(formatted);
        int unit = formatted.indexOf(' ') + 1;
        if (unit > 0) {
            text.setSpan(new RelativeSizeSpan(.6f), unit, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(AppUi.MUTED), unit, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        target.setText(text);
    }
}
