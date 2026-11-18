package io.minclash.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
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
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9),
            ui.shape(AppUi.CARD, 16), null));

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(ui.label("流量统计", 15, AppUi.TEXT, true), new LayoutParams(0, -2, 1));
        heading.addView(ui.label("详情 ›", 12, AppUi.MUTED, false));
        ui.add(this, heading, 0);

        LinearLayout rates = new LinearLayout(context);
        upload = ui.label("↑ 上传 —", 13, AppUi.AMBER, false);
        download = ui.label("↓ 下载 —", 13, AppUi.MINT, false);
        upload.setPadding(0, 0, ui.dp(6), 0);
        rates.addView(upload, new LayoutParams(0, -2, 1));
        rates.addView(download, new LayoutParams(0, -2, 1));
        ui.add(this, rates, 4);
    }

    void setActive(boolean active) {
        if (active) TrafficMonitor.INSTANCE.observe(getContext(), observer, false);
        else TrafficMonitor.INSTANCE.stopObserving(observer);
    }

    private void bind(TrafficMonitor.Reading reading, TrafficMonitor.Rate rate) {
        upload.setText("↑ 上传 " + (rate == null ? "—" : TrafficMonitor.formatRate(rate.upload())));
        download.setText("↓ 下载 " + (rate == null ? "—" : TrafficMonitor.formatRate(rate.download())));
    }
}
