package io.minclash.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;

public final class TrafficActivity extends ThemedActivity {
    private static final long RECENT_MS = 60_000;
    private final TrafficMonitor.Observer observer = this::bind;
    private final TextView[] values = new TextView[6];
    private AppUi ui;
    private TextView status;
    private Chart chart;
    private Spinner range;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new AppUi(this);
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

        LinearLayout body = ui.column();
        body.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(body);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        Button back = ui.button("‹ 返回");
        back.setOnClickListener(view -> finish());
        heading.addView(back);
        TextView title = ui.label("流量统计", 22, AppUi.TEXT, true);
        title.setAccessibilityHeading(true);
        title.setPadding(ui.dp(12), 0, 0, 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ui.add(body, heading, 0);

        LinearLayout trend = ui.card();
        LinearLayout trendHeader = new LinearLayout(this);
        trendHeader.setGravity(Gravity.CENTER_VERTICAL);
        status = ui.label("暂无实时数据", 13, AppUi.MUTED, false);
        trendHeader.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        range = new Spinner(this);
        range.setContentDescription("趋势时间范围");
        range.setMinimumHeight(ui.dp(48));
        range.setAdapter(ui.spinner(new String[] {"近 1 分钟", "近 10 分钟"}));
        trendHeader.addView(range, new LinearLayout.LayoutParams(-2, -2));
        ui.add(trend, trendHeader, 0);
        LinearLayout legend = new LinearLayout(this);
        TextView upload = ui.label("↑ 上传", 12, AppUi.AMBER, true);
        upload.setPadding(0, 0, ui.dp(16), 0);
        legend.addView(upload);
        legend.addView(ui.label("↓ 下载", 12, AppUi.MINT, true));
        ui.add(trend, legend, 2);
        chart = new Chart(this);
        LinearLayout.LayoutParams plot = new LinearLayout.LayoutParams(-1, ui.dp(164));
        plot.topMargin = ui.dp(8);
        trend.addView(chart, plot);
        range.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                chart.setContentDescription(position == 0
                    ? "最近一分钟上传和下载速度趋势，未采样时段留空"
                    : "最近十分钟上传和下载速度趋势，未采样时段留空");
                chart.invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        if (state != null) range.setSelection(state.getInt("traffic_range"));
        ui.add(body, trend, 14);

        LinearLayout statistics = ui.card();
        String[] names = {"上传速度", "下载速度", "累计上传", "累计下载", "活动连接", "内核内存"};
        for (int i = 0; i < names.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            addMetric(row, names[i], i);
            addMetric(row, names[i + 1], i + 1);
            ui.add(statistics, row, i == 0 ? 0 : 14);
        }
        ui.add(body, statistics, 12);
        ui.add(body, ui.label("仅统计内核流量，累计值随内核重启重置。", 12, AppUi.MUTED, false), 12);
        setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("traffic_range", range.getSelectedItemPosition());
        super.onSaveInstanceState(state);
    }

    private void addMetric(LinearLayout row, String name, int index) {
        LinearLayout cell = ui.column();
        cell.setPadding(0, 0, ui.dp(8), 0);
        cell.addView(ui.label(name, 12, AppUi.MUTED, false));
        int color = index == 0 ? AppUi.AMBER : index == 1 ? AppUi.MINT : AppUi.TEXT;
        values[index] = ui.label("—", index < 2 ? 20 : 17, color, true);
        ui.add(cell, values[index], 3);
        row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }

    @Override protected void onResume() {
        super.onResume();
        TrafficMonitor.INSTANCE.observe(observer, true);
    }

    @Override protected void onPause() {
        TrafficMonitor.INSTANCE.stopObserving(observer);
        super.onPause();
    }

    private void bind(TrafficMonitor.Reading reading, TrafficMonitor.Rate rate) {
        if (reading == null) {
            status.setText("暂无实时数据");
            for (TextView value : values) value.setText("—");
        } else {
            status.setText("未采样时段留空");
            values[0].setText(TrafficMonitor.formatRate(rate.upload()));
            values[1].setText(TrafficMonitor.formatRate(rate.download()));
            values[2].setText(SubscriptionUsage.bytes(reading.uploaded()));
            values[3].setText(SubscriptionUsage.bytes(reading.downloaded()));
            values[4].setText(reading.connections() < 0 ? "—" : String.valueOf(reading.connections()));
            values[5].setText(reading.memory() > 0 ? SubscriptionUsage.bytes(reading.memory()) : "—");
        }
        chart.invalidate();
    }

    private final class Chart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final PathEffect uploadDash = new DashPathEffect(new float[] {ui.dp(5), ui.dp(3)}, 0);

        Chart(Context context) {
            super(context);
            setContentDescription("上传和下载速度趋势");
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean wideRange = range.getSelectedItemPosition() == 1;
            long window = wideRange ? TrafficMonitor.HISTORY_MS : RECENT_MS;
            long cutoff = SystemClock.elapsedRealtime() - window;
            List<TrafficMonitor.Rate> rates = TrafficMonitor.INSTANCE.history();
            long peak = -1;
            for (TrafficMonitor.Rate rate : rates) {
                if (rate.time() >= cutoff) peak = Math.max(peak, Math.max(rate.upload(), rate.download()));
            }
            paint.setTextSize(ui.dp(11));
            paint.setColor(AppUi.MUTED);
            if (peak < 0) {
                String empty = "此时间段暂无采样";
                canvas.drawText(empty, (getWidth() - paint.measureText(empty)) / 2,
                    (getHeight() - paint.ascent() - paint.descent()) / 2, paint);
                return;
            }

            double scale = 2_048;
            while (scale < peak) scale *= 2;
            String max = TrafficMonitor.formatRate((long) scale);
            String middle = TrafficMonitor.formatRate((long) (scale / 2));
            float left = Math.max(paint.measureText(max), paint.measureText(middle)) + ui.dp(8);
            float right = getWidth() - ui.dp(3);
            if (right <= left) return;
            float top = ui.dp(10);
            float bottom = getHeight() - ui.dp(24);
            paint.setStrokeWidth(ui.dp(1));
            paint.setColor(AppUi.BORDER);
            for (int i = 0; i < 3; i++) {
                float x = left + (right - left) * i / 2;
                canvas.drawLine(x, top, x, bottom, paint);
            }
            for (int i = 0; i < 3; i++) {
                float y = top + (bottom - top) * i / 2;
                canvas.drawLine(left, y, right, y, paint);
            }
            paint.setColor(AppUi.MUTED);
            float baselineOffset = -(paint.ascent() + paint.descent()) / 2;
            canvas.drawText(max, 0, top + baselineOffset, paint);
            canvas.drawText(middle, 0, (top + bottom) / 2 + baselineOffset, paint);
            canvas.drawText("0 B/s", 0, bottom + baselineOffset, paint);
            float timeBaseline = getHeight() - ui.dp(4);
            String start = wideRange ? "10 分钟前" : "1 分钟前";
            String midpoint = wideRange ? "5 分钟前" : "30 秒前";
            float midX = (left + right - paint.measureText(midpoint)) / 2;
            float endX = right - paint.measureText("现在");
            canvas.drawText(start, left, timeBaseline, paint);
            if (midX > left + paint.measureText(start) + ui.dp(4)
                && midX + paint.measureText(midpoint) + ui.dp(4) < endX) {
                canvas.drawText(midpoint, midX, timeBaseline, paint);
            }
            canvas.drawText("现在", endX, timeBaseline, paint);

            paint.setStrokeWidth(ui.dp(2));
            for (int series = 0; series < 2; series++) {
                boolean upload = series == 1;
                paint.setColor(upload ? AppUi.AMBER : AppUi.MINT);
                path.reset();
                long lastTime = -1;
                for (TrafficMonitor.Rate rate : rates) {
                    long value = upload ? rate.upload() : rate.download();
                    if (rate.time() < cutoff || value < 0) {
                        lastTime = -1;
                        continue;
                    }
                    float x = left + (right - left) * (rate.time() - cutoff) / window;
                    float y = bottom - (bottom - top) * (float) (value / scale);
                    if (lastTime >= 0 && rate.time() - lastTime < TrafficMonitor.MAX_SAMPLE_GAP_MS) {
                        path.lineTo(x, y);
                    } else {
                        path.moveTo(x, y);
                        canvas.drawCircle(x, y, ui.dp(1), paint);
                    }
                    lastTime = rate.time();
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setPathEffect(upload ? uploadDash : null);
                canvas.drawPath(path, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
