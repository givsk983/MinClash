package io.minclash.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrafficActivity extends ThemedActivity {
    private static final long RECENT_MS = 60_000;
    private static final DateTimeFormatter HISTORY_DATE = DateTimeFormatter.ofPattern("MM-dd");
    private static final int APP_PREVIEW_LIMIT = 8;
    private final TrafficMonitor.Observer observer = this::bind;
    private final TextView[] values = new TextView[6];
    private final TextView[] dailyValues = new TextView[DailyTraffic.DAYS];
    private final TextView[] dailyDates = new TextView[DailyTraffic.DAYS];
    private List<DailyTraffic.Day> displayedDaily = Collections.emptyList();
    private List<AppDailyTraffic.Day> displayedAppDaily = Collections.emptyList();
    private List<LocalDate> appDates = Collections.emptyList();
    private final Map<Integer, Owner> appOwners = new HashMap<>();
    private AppUi ui;
    private TextView status;
    private Chart chart;
    private Spinner range;
    private Spinner appRange;
    private LinearLayout appRows;
    private TextView appHistoryMessage;
    private Button allApps;
    private int appSelection;
    private boolean appTrafficSupported;
    private AppIconLoader appIcons;
    private AlertDialog appHistoryDialog;

    private record Owner(String label, String packageName, long installed) {}
    private record AppRow(int uid, String label, String packageName) {}
    private record AppRowViews(ImageView icon, TextView name, TextView amount) {}

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new AppUi(this);
        appIcons = new AppIconLoader(this);
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
        LinearLayout history = ui.card();
        TextView historyTitle = ui.label("近 7 天流量", 16, AppUi.TEXT, true);
        historyTitle.setAccessibilityHeading(true);
        ui.add(history, historyTitle, 0);
        ui.add(history, ui.label("本机按日保存 Mihomo 采样。跨日缺少采样或内核重启时，部分流量可能未记入。",
            12, AppUi.MUTED, false), 6);
        for (int i = 0; i < DailyTraffic.DAYS; i++) {
            LinearLayout row = new LinearLayout(this);
            dailyDates[i] = ui.label("—", 12, AppUi.TEXT, true);
            dailyValues[i] = ui.label("—", 12, AppUi.MUTED, false);
            dailyValues[i].setGravity(Gravity.END);
            row.addView(dailyDates[i], new LinearLayout.LayoutParams(-2, -2));
            row.addView(dailyValues[i], new LinearLayout.LayoutParams(0, -2, 1));
            ui.add(history, row, 12);
        }
        addAppHistory(history, state);
        ui.add(body, history, 14);
        setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("traffic_range", range.getSelectedItemPosition());
        state.putInt("app_traffic_range", appSelection);
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
        appIcons.observe(this::refreshAppIcons);
        appIcons.invalidate();
        appOwners.clear();
        if (!displayedAppDaily.isEmpty()) renderAppHistory();
        if (appHistoryDialog != null) refreshAppIcons();
        TrafficMonitor.INSTANCE.observe(this, observer, true);
    }

    @Override protected void onPause() {
        TrafficMonitor.INSTANCE.stopObserving(observer);
        appIcons.stopObserving();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (appHistoryDialog != null) appHistoryDialog.dismiss();
        appIcons.close();
        super.onDestroy();
    }

    private void bind(TrafficMonitor.Reading reading, TrafficMonitor.Rate rate) {
        if (reading == null) {
            setText(status, "暂无实时数据");
            for (TextView value : values) setText(value, "—");
        } else {
            setText(status, "未采样时段留空");
            setText(values[0], TrafficMonitor.formatRate(rate.upload()));
            setText(values[1], TrafficMonitor.formatRate(rate.download()));
            setText(values[2], SubscriptionUsage.bytes(reading.uploaded()));
            setText(values[3], SubscriptionUsage.bytes(reading.downloaded()));
            setText(values[4], reading.connections() < 0 ? "—" : String.valueOf(reading.connections()));
            setText(values[5], reading.memory() > 0 ? SubscriptionUsage.bytes(reading.memory()) : "—");
        }
        chart.invalidate();
        List<DailyTraffic.Day> recent = TrafficMonitor.INSTANCE.daily();
        for (int i = 0; i < recent.size(); i++) {
            DailyTraffic.Day day = recent.get(i);
            if (i < displayedDaily.size() && day.equals(displayedDaily.get(i))) continue;
            setText(dailyDates[i], day.date().format(HISTORY_DATE) + (i == 0 ? " 今天" : ""));
            DailyTraffic.Totals total = day.totals();
            setText(dailyValues[i], total == null ? "暂无记录"
                : "↑ " + SubscriptionUsage.bytes(total.upload()) + "    ↓ " + SubscriptionUsage.bytes(total.download()));
        }
        displayedDaily = recent;
        List<AppDailyTraffic.Day> recentApps = TrafficMonitor.INSTANCE.appDaily();
        boolean appSupport = TrafficMonitor.INSTANCE.supportsAppTraffic();
        if (!displayedAppDaily.equals(recentApps) || appTrafficSupported != appSupport) {
            displayedAppDaily = recentApps;
            appTrafficSupported = appSupport;
            List<LocalDate> dates = new ArrayList<>(recentApps.size());
            for (AppDailyTraffic.Day day : recentApps) dates.add(day.date());
            if (!appDates.equals(dates)) {
                appDates = dates;
                String[] choices = new String[dates.size() + 1];
                choices[0] = "近 7 天合计";
                for (int i = 0; i < dates.size(); i++) choices[i + 1] = dates.get(i).format(HISTORY_DATE) + (i == 0 ? " 今天" : "");
                appRange.setAdapter(ui.spinner(choices));
                appRange.setSelection(Math.min(appSelection, dates.size()));
            }
            renderAppHistory();
        }
    }

    private void addAppHistory(LinearLayout history, Bundle state) {
        TextView heading = ui.label("按应用查看流量", 15, AppUi.TEXT, true);
        heading.setAccessibilityHeading(true);
        ui.add(history, heading, 20);
        appSelection = state == null ? 0 : state.getInt("app_traffic_range");
        appRange = new Spinner(this);
        appRange.setContentDescription("应用流量日期");
        appRange.setMinimumHeight(ui.dp(48));
        appRange.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                appSelection = position;
                renderAppHistory();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        ui.add(history, appRange, 6);
        appHistoryMessage = ui.label("正在读取应用流量", 12, AppUi.MUTED, false);
        ui.add(history, appHistoryMessage, 4);
        appRows = ui.column();
        ui.add(history, appRows, 0);
        allApps = ui.button("查看全部应用");
        allApps.setVisibility(View.GONE);
        ui.add(history, allApps, 8);
    }

    private void renderAppHistory() {
        Map<AppRow, DailyTraffic.Totals> totals = new LinkedHashMap<>();
        LocalDate selected = appSelection > 0 && appSelection <= appDates.size() ? appDates.get(appSelection - 1) : null;
        ZoneId zone = ZoneId.systemDefault();
        for (AppDailyTraffic.Day day : displayedAppDaily) {
            if (selected != null && !selected.equals(day.date())) continue;
            long dayStart = day.date().atStartOfDay(zone).toInstant().toEpochMilli();
            day.totals().forEach((uid, usage) -> {
                Owner owner = appOwners.computeIfAbsent(uid, this::trafficOwner);
                // A newly installed package may have inherited a UID used earlier that day.
                AppRow key = owner.installed() > dayStart ? new AppRow(uid, "来源未识别 · UID " + uid, "")
                    : new AppRow(uid, owner.label(), owner.packageName());
                totals.merge(key, usage, (left, right) -> new DailyTraffic.Totals(
                    addBytes(left.upload(), right.upload()), addBytes(left.download(), right.download())));
            });
        }
        var sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(Comparator.<Map.Entry<AppRow, DailyTraffic.Totals>>comparingLong(
            entry -> addBytes(entry.getValue().upload(), entry.getValue().download())).reversed()
            .thenComparing(entry -> entry.getKey().label()).thenComparingInt(entry -> entry.getKey().uid()));
        Map<String, Integer> nameCounts = new HashMap<>();
        for (var entry : sorted) nameCounts.merge(entry.getKey().label(), 1, Integer::sum);
        appRows.removeAllViews();
        setText(appHistoryMessage, sorted.isEmpty()
            ? appTrafficSupported ? "暂无应用流量记录，使用 TUN 后会逐步显示。" : "更新内置模块并重启后，可开始记录应用流量。"
            : "应用流量从启用新版模块后记录，未能确认归属的流量单独显示。");
        for (int i = 0; i < Math.min(sorted.size(), APP_PREVIEW_LIMIT); i++) {
            LinearLayout row = createAppRow();
            bindAppRow(row, sorted.get(i), nameCounts);
            ui.add(appRows, row, 11);
        }
        boolean more = sorted.size() > APP_PREVIEW_LIMIT;
        allApps.setVisibility(more ? View.VISIBLE : View.GONE);
        allApps.setOnClickListener(more ? view -> showAllApps(sorted, nameCounts) : null);
    }

    private LinearLayout createAppRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(40));
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32));
        iconSize.setMarginEnd(ui.dp(9));
        row.addView(icon, iconSize);
        TextView name = ui.label("", 13, AppUi.TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount = ui.label("", 12, AppUi.MUTED, false);
        amount.setPaddingRelative(ui.dp(8), 0, 0, 0);
        amount.setGravity(Gravity.END);
        row.addView(amount, new LinearLayout.LayoutParams(-2, -2));
        row.setTag(new AppRowViews(icon, name, amount));
        return row;
    }

    private void bindAppRow(View row, Map.Entry<AppRow, DailyTraffic.Totals> entry, Map<String, Integer> nameCounts) {
        AppRowViews views = (AppRowViews) row.getTag();
        views.name().setText(appRowLabel(entry.getKey(), nameCounts));
        views.amount().setText(appUsage(entry.getValue()));
        views.icon().setTag(entry.getKey().packageName());
        refreshAppIcon(row);
    }

    private void refreshAppIcon(View row) {
        if (!(row.getTag() instanceof AppRowViews views)) return;
        String packageName = (String) views.icon().getTag();
        AppIconLoader.Result result = packageName.isEmpty() ? null : appIcons.get(packageName);
        if (result == null && !packageName.isEmpty()) appIcons.request(packageName, false);
        if (result != null && result.bitmap() != null) views.icon().setImageBitmap(result.bitmap());
        else views.icon().setImageResource(android.R.drawable.sym_def_app_icon);
    }

    private void refreshAppIcons() {
        for (int i = 0; i < appRows.getChildCount(); i++) refreshAppIcon(appRows.getChildAt(i));
        if (appHistoryDialog == null) return;
        ListView list = appHistoryDialog.getListView();
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) refreshAppIcon(list.getChildAt(i));
    }

    private void showAllApps(List<Map.Entry<AppRow, DailyTraffic.Totals>> entries, Map<String, Integer> nameCounts) {
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Map.Entry<AppRow, DailyTraffic.Totals> getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View row = recycled;
                if (row == null) {
                    row = createAppRow();
                    row.setPaddingRelative(ui.dp(22), ui.dp(6), ui.dp(22), ui.dp(6));
                }
                bindAppRow(row, getItem(position), nameCounts);
                return row;
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("应用流量")
            .setAdapter(adapter, null).setPositiveButton("关闭", null).create();
        dialog.setOnDismissListener(closed -> {
            if (appHistoryDialog == dialog) appHistoryDialog = null;
        });
        appHistoryDialog = dialog;
        dialog.show();
    }

    private Owner trafficOwner(int uid) {
        if (uid == AppDailyTraffic.UNKNOWN_UID) return new Owner("来源未识别", "", 0);
        PackageManager packages = getPackageManager();
        try {
            String[] names = packages.getPackagesForUid(uid);
            if (names != null && names.length > 1) return new Owner("共享 UID " + uid, "", 0);
            if (names != null && names.length == 1) {
                PackageInfo info = packages.getPackageInfo(names[0], 0);
                if (info.applicationInfo != null && info.applicationInfo.uid == uid) {
                    return new Owner(info.applicationInfo.loadLabel(packages).toString(), names[0], info.firstInstallTime);
                }
            }
        } catch (PackageManager.NameNotFoundException | SecurityException ignored) { }
        return new Owner("来源未识别 · UID " + uid, "", 0);
    }

    private static String appRowLabel(AppRow row, Map<String, Integer> nameCounts) {
        if (nameCounts.getOrDefault(row.label(), 0) < 2) return row.label();
        return row.label() + "\n" + (row.packageName().isEmpty() ? "UID " + row.uid() : row.packageName());
    }

    private static long addBytes(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static String appUsage(DailyTraffic.Totals usage) {
        return "↑ " + SubscriptionUsage.bytes(usage.upload()) + "   ↓ " + SubscriptionUsage.bytes(usage.download());
    }

    private static void setText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
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
