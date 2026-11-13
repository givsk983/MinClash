package io.minclash.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live-only view. Snapshots and application labels never leave this Activity. */
public final class ConnectionsActivity extends Activity {
    private record AppName(String label, String name) {}
    private record Row(ConnectionSnapshot.Entry connection, AppName app) {
        String key() { return connection.id().isEmpty() ? connection.host() + connection.process() + connection.started() : connection.id(); }
    }
    private record Card(TextView app, TextView host, TextView route, TextView flow, TextView protocol) {}
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectionRefresh refresh = new ConnectionRefresh();
    private final Runnable poll = () -> refresh();
    private final Map<String, AppName> appNames = new HashMap<>();
    private final List<Row> all = new ArrayList<>(), shown = new ArrayList<>();
    private boolean visible, automatic = true, stale, notReady;
    private long updatedAt;
    private int filterVersion;
    private AppUi ui;
    private ConnectionSnapshot snapshot;
    private TextView status, count, uploaded, downloaded, matches, empty;
    private Button refreshButton, identifyButton, automaticButton;
    private EditText search;
    private Spinner field;
    private ListView list;
    private BaseAdapter adapter;
    private AlertDialog details;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new AppUi(this);
        LinearLayout root = ui.column(); root.setBackgroundColor(AppUi.BG);
        root.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                int bottom = Math.max(safe.bottom, insets.getInsets(android.view.WindowInsets.Type.ime()).bottom);
                view.setPadding(safe.left + ui.dp(16), safe.top + ui.dp(10), safe.right + ui.dp(16), bottom + ui.dp(8));
            }
            return insets;
        });
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        Button back = ui.button("‹ 返回"); back.setOnClickListener(view -> finish());
        heading.addView(back, new LinearLayout.LayoutParams(ui.dp(76), -2));
        LinearLayout title = ui.column(); title.setPadding(ui.dp(12), 0, 0, 0);
        title.addView(ui.label("连接管理", 22, AppUi.TEXT, true));
        ui.add(title, ui.label("当前会话 · 不保存历史", 11, AppUi.MUTED, false), 3);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); ui.add(root, heading, 0);
        LinearLayout statistics = ui.card(), stats = new LinearLayout(this);
        count = metric(stats, "活动连接", 24, AppUi.MINT);
        uploaded = metric(stats, "会话上传", 16, AppUi.TEXT);
        downloaded = metric(stats, "会话下载", 16, AppUi.TEXT);
        statistics.addView(stats); ui.add(root, statistics, 12);
        LinearLayout controls = new LinearLayout(this);
        refreshButton = ui.button("刷新"); refreshButton.setOnClickListener(view -> refresh());
        automaticButton = ui.button("自动刷新 ✓"); automaticButton.setOnClickListener(view -> {
            automatic = !automatic; main.removeCallbacks(poll); renderControls();
            if (automatic) refresh(); else updateStatus();
        });
        identifyButton = ui.button("识别应用"); identifyButton.setOnClickListener(view -> identify());
        for (Button button : new Button[] {refreshButton, automaticButton, identifyButton}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (controls.getChildCount() > 0) params.leftMargin = ui.dp(6); controls.addView(button, params);
        }
        ui.add(root, controls, 10);
        LinearLayout filters = new LinearLayout(this); filters.setGravity(Gravity.CENTER_VERTICAL);
        field = new Spinner(this);
        field.setAdapter(ui.spinner(new String[] {"全部", "应用", "目标", "出口"}));
        filters.addView(field, new LinearLayout.LayoutParams(ui.dp(88), ui.dp(48)));
        search = ui.search("搜索应用、域名或出口"); filters.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = ui.button("清空"); clear.setContentDescription("清空连接筛选"); clear.setOnClickListener(view -> search.getText().clear());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(ui.dp(58), -2); clearParams.leftMargin = ui.dp(4);
        filters.addView(clear, clearParams); ui.add(root, filters, 10);
        status = ui.label("正在读取连接…", 11, AppUi.MUTED, false); status.setPadding(0, ui.dp(7), 0, ui.dp(5));
        status.setOnClickListener(view -> refresh()); root.addView(status);
        matches = ui.label("", 11, AppUi.MUTED, false); ui.add(root, matches, 0);
        android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1); bodyParams.topMargin = ui.dp(8); root.addView(body, bodyParams);
        list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        list.setDividerHeight(ui.dp(8)); list.setClipToPadding(false); list.setPadding(0, 0, 0, ui.dp(8));
        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Row getItem(int index) { return shown.get(index); }
            public long getItemId(int index) { return index; }
            public View getView(int index, View reusable, ViewGroup parent) {
                LinearLayout row; Card card;
                if (reusable instanceof LinearLayout old && old.getTag() instanceof Card existing) { row = old; card = existing; }
                else {
                    row = ui.card(); row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2279e7c9), ui.shape(AppUi.CARD, 16), null));
                    LinearLayout name = new LinearLayout(ConnectionsActivity.this); name.setGravity(Gravity.CENTER_VERTICAL);
                    TextView app = ui.line(12, AppUi.MINT, true); name.addView(app, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView protocol = ui.line(10, AppUi.MUTED, false); protocol.setPadding(ui.dp(8), 0, 0, 0); name.addView(protocol); row.addView(name);
                    TextView host = ui.line(15, AppUi.TEXT, true); ui.add(row, host, 8);
                    TextView route = ui.line(12, AppUi.MUTED, false); ui.add(row, route, 5);
                    TextView flow = ui.line(11, AppUi.MUTED, false); ui.add(row, flow, 8);
                    card = new Card(app, host, route, flow, protocol); row.setTag(card);
                }
                Row item = getItem(index); card.app().setText(item.app().label()); card.host().setText(item.connection().host());
                card.route().setText("出口 · " + item.connection().route()); card.protocol().setText(item.connection().network());
                card.flow().setText("↑ " + SubscriptionUsage.bytes(item.connection().upload()) + "    ↓ " + SubscriptionUsage.bytes(item.connection().download()));
                row.setOnClickListener(view -> showDetails(item)); return row;
            }
        };
        list.setAdapter(adapter); body.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
        empty = ui.label("正在读取连接…", 14, AppUi.MUTED, false); empty.setGravity(Gravity.CENTER);
        empty.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)); body.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            boolean compact = b - t - view.getPaddingTop() - view.getPaddingBottom() < ui.dp(390);
            if ((statistics.getVisibility() == View.GONE) != compact) statistics.setVisibility(compact ? View.GONE : View.VISIBLE);
        });
        setContentView(root);
        if (saved != null) { automatic = saved.getBoolean("automatic", true); field.setSelection(Math.max(0, Math.min(3, saved.getInt("field")))); search.setText(saved.getString("query", "")); }
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(false); }
            public void afterTextChanged(Editable value) {}
        });
        field.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int index, long id) {
                search.setHint(new String[] {"搜索应用、域名或出口", "应用名称、包名或 UID", "目标域名或 IP", "代理组或节点名称"}[index]); filter(false);
            }
        });
        renderControls();
    }
    private TextView metric(LinearLayout parent, String title, int size, int color) {
        LinearLayout part = ui.column(); part.addView(ui.label(title, 10, AppUi.MUTED, false));
        TextView value = ui.line(size, color, true); value.setText("—"); ui.add(part, value, 5); parent.addView(part, new LinearLayout.LayoutParams(0, -2, 1)); return value;
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putInt("field", field.getSelectedItemPosition()); saved.putString("query", search.getText().toString()); saved.putBoolean("automatic", automatic); super.onSaveInstanceState(saved);
    }
    @Override protected void onResume() { super.onResume(); visible = true; refresh(); }
    @Override protected void onPause() { visible = false; main.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); reader.shutdownNow(); if (details != null) details.dismiss(); super.onDestroy(); }

    private void refresh() {
        main.removeCallbacks(poll);
        if (!visible || !refresh.beginRead()) return;
        int revision = refresh.revision(); renderControls();
        reader.execute(() -> {
            try {
                Module.Status state = Module.status();
                if (!state.running() || !state.control) { main.post(() -> complete(revision, null, null, true)); return; }
                ConnectionSnapshot fresh = ConnectionSnapshot.parse(Controller.connections());
                List<ConnectionSnapshot.Entry> ordered = new ArrayList<>(fresh.entries());
                ordered.sort(Comparator.comparing(ConnectionSnapshot.Entry::started).reversed().thenComparing(ConnectionSnapshot.Entry::id).thenComparing(ConnectionSnapshot.Entry::host));
                List<Row> rows = new ArrayList<>(); for (ConnectionSnapshot.Entry entry : ordered) rows.add(new Row(entry, application(entry)));
                main.post(() -> complete(revision, fresh, rows, false));
            } catch (Exception unavailable) { main.post(() -> complete(revision, null, null, false)); }
        });
    }
    private void complete(int revision, ConnectionSnapshot fresh, List<Row> rows, boolean stopped) {
        if (isDestroyed()) return;
        ConnectionRefresh.Finished result = refresh.finishRead(revision);
        if (result.publish()) {
            notReady = stopped;
            if (fresh != null) { snapshot = fresh; updatedAt = System.currentTimeMillis(); stale = false; all.clear(); all.addAll(rows); }
            else { stale = true; if (stopped) { snapshot = null; all.clear(); updatedAt = 0; } }
            filter(true); updateStatus();
        }
        renderControls(); if (result.followUp() && visible) refresh(); else schedulePoll();
    }
    private void schedulePoll() {
        main.removeCallbacks(poll);
        if (visible && automatic && !refresh.reading() && !refresh.changing() && (details == null || !details.isShowing())) main.postDelayed(poll, 4000);
    }
    private void renderControls() {
        refreshButton.setEnabled(!refresh.changing()); refreshButton.setText(refresh.reading() ? "刷新中…" : "刷新");
        identifyButton.setEnabled(!refresh.changing()); automaticButton.setText(automatic ? "自动刷新 ✓" : "已暂停刷新");
    }
    private void updateStatus() {
        if (snapshot == null && !stale && !notReady) { status.setText("正在读取连接…"); return; }
        String time = updatedAt == 0 ? "" : java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(updatedAt));
        status.setTextColor(stale ? AppUi.AMBER : AppUi.MUTED);
        status.setText(notReady ? "TUN 尚未就绪，连接后点此重试。" : stale
            ? updatedAt == 0 ? "读取失败，请检查 Root 授权和内核状态，点此重试。" : "刷新失败 · 显示 " + time + " 的上次快照，点此重试。"
            : time + " 更新 · " + (automatic ? "每 4 秒刷新" : "已暂停自动刷新"));
    }
    private void filter(boolean preservePosition) {
        int first = list.getFirstVisiblePosition();
        String anchor = preservePosition && first >= 0 && first < shown.size() ? shown.get(first).key() : null;
        int top = list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop();
        String query = search.getText().toString(); int selected = field.getSelectedItemPosition(); shown.clear(); int matched = 0;
        for (Row row : all) if (row.connection().matches(query, selected, row.app().label() + " " + row.app().name())) { if (++matched <= 300) shown.add(row); }
        adapter.notifyDataSetChanged(); count.setText(snapshot == null ? "—" : String.valueOf(snapshot.total()));
        uploaded.setText(snapshot == null ? "—" : SubscriptionUsage.bytes(snapshot.upload())); downloaded.setText(snapshot == null ? "—" : SubscriptionUsage.bytes(snapshot.download()));
        matches.setText(snapshot == null ? "" : "匹配 " + matched + " 条 · 显示 " + shown.size() + " 条" + (snapshot.total() > 5000 ? " · 仅处理前 5000 条" : ""));
        empty.setText(snapshot == null ? notReady ? "尚未连接\n启动 TUN 后可查看活动连接" : stale ? "暂时无法读取\n点“刷新”重新尝试" : "正在读取连接…"
            : all.isEmpty() ? "暂无活动连接\n让应用产生流量后刷新" : "没有匹配的连接\n试试更换筛选条件或清空搜索");
        int version = ++filterVersion;
        if (anchor != null) for (int i = 0; i < shown.size(); i++) if (shown.get(i).key().equals(anchor)) {
            int position = i; list.post(() -> { if (filterVersion == version) list.setSelectionFromTop(position, top); }); return;
        }
        if (!preservePosition) list.setSelection(0);
    }
    private AppName application(ConnectionSnapshot.Entry entry) {
        String key = entry.uid() + ":" + entry.process(); if (appNames.containsKey(key)) return appNames.get(key);
        PackageManager packages = getPackageManager(); String name = entry.process().split(":", 2)[0]; AppName result;
        try {
            ApplicationInfo info;
            try { info = packages.getApplicationInfo(name, 0); }
            catch (PackageManager.NameNotFoundException missing) {
                String[] owners = entry.uid() > 0 ? packages.getPackagesForUid(entry.uid()) : null;
                if (owners == null || owners.length != 1) throw missing;
                name = owners[0]; info = packages.getApplicationInfo(name, 0);
            }
            if (entry.uid() >= 0 && info.uid != entry.uid()) result = new AppName("来源未识别 · UID " + entry.uid(), entry.process());
            else result = new AppName(packages.getApplicationLabel(info).toString(), name);
        } catch (Exception unavailable) { result = new AppName("来源未识别" + (entry.uid() >= 0 ? " · UID " + entry.uid() : ""), entry.process()); }
        if (appNames.size() >= 6000) appNames.clear(); appNames.put(key, result); return result;
    }
    private void track(AlertDialog dialog) {
        main.removeCallbacks(poll); details = dialog;
        dialog.setOnDismissListener(view -> { if (details == dialog) { details = null; schedulePoll(); } });
    }
    private void showDetails(Row row) {
        if (details != null) details.dismiss();
        TextView text = ui.label(row.connection().detail(row.app().label() + " · " + row.app().name())
            + (stale ? "\n\n这是上次快照，刷新成功后可断开连接。" : ""), 13, AppUi.TEXT, false);
        text.setTextIsSelectable(true); text.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(text);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("连接详情").setView(scroll).setPositiveButton("关闭", null);
        if (!stale && !row.connection().id().isEmpty()) builder.setNegativeButton("断开连接", (dialog, which) ->
            track(new AlertDialog.Builder(this).setTitle("断开此连接？").setMessage(row.connection().host() + "\n" + row.app().label() + "\n应用可能自动重新连接。")
                .setNegativeButton("取消", null).setPositiveButton("断开", (confirm, selected) -> {
                    if (stale) { refresh(); return; }
                    change(() -> Controller.closeConnection(row.connection().id()), "连接已断开或已结束。");
                }).show()));
        track(builder.show());
    }
    private void identify() {
        if (details != null) details.dismiss();
        track(new AlertDialog.Builder(this).setTitle("临时识别应用来源").setMessage("开启后让应用重新产生流量。仅持续到本次内核运行结束，部分连接仍可能无法识别。")
            .setNegativeButton("取消", null).setPositiveButton("开启", (dialog, which) -> change(Controller::identifyProcessesForSession, "已开启临时识别，请让应用产生流量。")).show());
    }
    private interface Action { void run() throws Exception; }
    private void change(Action action, String success) {
        if (!refresh.beginChange()) return;
        main.removeCallbacks(poll); stale = true; renderControls(); status.setText("正在应用，请稍候…");
        RoutingStore.WORKER.execute(() -> {
            String message;
            try { if (!Module.status().running()) throw new java.io.IOException(); action.run(); message = success; }
            catch (Exception unavailable) { message = "操作未完成，请确认 TUN 就绪后重试。"; }
            String result = message;
            main.post(() -> {
                if (isDestroyed()) return;
                boolean readNow = refresh.finishChange(); renderControls();
                android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_SHORT).show(); if (readNow) refresh();
            });
        });
    }
}
