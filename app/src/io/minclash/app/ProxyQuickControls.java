package io.minclash.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class ProxyQuickControls extends Dialog {
    private static final int TEXT = 0xffedf4f7;
    private static final int MUTED = 0xff9baebc;
    private static final int MINT = 0xff79e7c9;
    private final Button[] modes = new Button[Controller.Mode.values().length];
    private final List<String> options = new ArrayList<>();
    private Controller.Snapshot snapshot;
    private String selectedGroup;
    private TextView status;
    private Button groupButton;
    private Button automatic;
    private EditText search;
    private ListView list;
    private BaseAdapter adapter;
    private boolean busy;
    private final boolean modePanel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog groupPicker;

    ProxyQuickControls(Context context, boolean modePanel) {
        super(context, R.style.ProxyQuickControls);
        this.modePanel = modePanel;
        getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        setCanceledOnTouchOutside(true);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(10), dp(18), dp(14));
        panel.setFocusableInTouchMode(true);

        LinearLayout heading = new LinearLayout(getContext());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(modePanel ? "切换代理模式" : "切换代理节点", 18, TEXT);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = control("关闭");
        close.setOnClickListener(view -> dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(64), dp(40)));
        add(panel, heading, 0, -2);

        status = label("正在读取代理状态…", 12, MUTED);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        add(panel, status, 4, -2);

        if (modePanel) addModeControls(panel);
        else addNodeControls(panel);

        setContentView(panel);
        if (modePanel) {
            int width = Math.min(320, getContext().getResources().getConfiguration().screenWidthDp - 32);
            getWindow().setLayout(dp(width), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        panel.requestFocus();
        load(null);
    }

    private void addModeControls(LinearLayout panel) {
        LinearLayout modeRow = new LinearLayout(getContext());
        Controller.Mode[] order = {Controller.Mode.DIRECT, Controller.Mode.RULE, Controller.Mode.GLOBAL};
        for (int i = 0; i < order.length; i++) {
            Controller.Mode mode = order[i];
            Button button = control(mode.label);
            modes[mode.ordinal()] = button;
            button.setOnClickListener(view -> load(() -> Module.run(mode.command, null)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
            if (i > 0) params.setMarginStart(dp(6));
            modeRow.addView(button, params);
        }
        add(panel, modeRow, 12, -2);
    }

    private void addNodeControls(LinearLayout panel) {
        LinearLayout groupRow = new LinearLayout(getContext());
        groupButton = control("选择代理组");
        groupButton.setOnClickListener(view -> chooseGroup());
        groupRow.addView(groupButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        automatic = control("恢复自动");
        automatic.setOnClickListener(view -> {
            Controller.Group group = currentGroup();
            if (group != null && group.automatic()) load(() -> Controller.select(group.name(), null));
        });
        LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(dp(92), dp(42));
        autoParams.setMarginStart(dp(6));
        groupRow.addView(automatic, autoParams);
        add(panel, groupRow, 8, -2);

        search = new EditText(getContext());
        search.setHint("搜索节点");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        add(panel, search, 4, -2);

        list = new ListView(getContext());
        list.setDivider(null);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return options.size(); }
            @Override public String getItem(int position) { return options.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean isEnabled(int position) { return !busy; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = convertView instanceof TextView text ? text : label("", 14, TEXT);
                String option = getItem(position);
                Controller.Group group = currentGroup();
                boolean selected = group != null && option.equals(group.current());
                row.setText((selected ? "✓  " : "") + Controller.outletLabel(option));
                row.setTextColor(selected ? MINT : TEXT);
                row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(44));
                row.setPadding(dp(8), 0, dp(8), 0);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, row, position, id) -> {
            Controller.Group group = currentGroup();
            if (busy || group == null) return;
            String option = options.get(position);
            if (group.automatic() || !option.equals(group.current())) load(() -> Controller.select(group.name(), option));
        });
        add(panel, list, 4, dp(Math.min(260, getContext().getResources().getConfiguration().screenHeightDp / 3)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filterNodes(); }
            @Override public void afterTextChanged(Editable text) { }
        });

    }

    @Override public void dismiss() {
        if (groupPicker != null) groupPicker.dismiss();
        super.dismiss();
    }

    private interface Change { void run() throws Exception; }

    private void load(Change change) {
        if (busy) return;
        busy = true;
        status.setOnClickListener(null);
        status.setText(change == null ? "正在读取代理状态…" : "正在应用…");
        renderControls();
        RoutingStore.WORKER.execute(() -> {
            String error = "";
            if (change != null) {
                try {
                    change.run();
                    ProxyNotificationService.connectionChanged(getContext());
                    if (modePanel) {
                        main.post(this::dismiss);
                        return;
                    }
                } catch (Exception failure) { error = "操作未完成，请重试"; }
            }
            Controller.Snapshot fresh = null;
            try {
                fresh = modePanel ? new Controller.Snapshot(Controller.mode(), "", Collections.emptyList())
                    : Controller.snapshot();
            }
            catch (Exception failure) { error = "无法读取代理状态，请确认 TUN 正在运行 · 点此重试"; }
            Controller.Snapshot result = fresh;
            String message = error;
            main.post(() -> {
                if (!isShowing()) return;
                snapshot = result;
                if (!modePanel && currentGroup() == null) {
                    selectedGroup = result == null || result.groups().isEmpty() ? null : result.groups().get(0).name();
                }
                busy = false;
                status.setText(!message.isEmpty() ? message : modePanel ? "当前：" + result.mode().label + "模式"
                    : result.mode() == Controller.Mode.DIRECT ? "当前为直连模式" : "当前出口 · " + result.node());
                if (result == null) status.setOnClickListener(view -> load(null));
                renderControls();
            });
        });
    }

    private void chooseGroup() {
        if (busy || snapshot == null) return;
        List<Controller.Group> groups = snapshot.groups();
        String[] names = groups.stream().map(Controller.Group::name).toArray(String[]::new);
        groupPicker = new AlertDialog.Builder(getContext()).setTitle("选择代理组")
            .setSingleChoiceItems(names, groups.indexOf(currentGroup()), (dialog, index) -> {
                selectedGroup = groups.get(index).name();
                search.setText("");
                list.setSelection(0);
                renderControls();
                dialog.dismiss();
            }).setNegativeButton("取消", null).create();
        groupPicker.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        groupPicker.show();
    }

    private Controller.Group currentGroup() {
        if (snapshot == null || selectedGroup == null) return null;
        for (Controller.Group group : snapshot.groups()) if (group.name().equals(selectedGroup)) return group;
        return null;
    }

    private void renderControls() {
        if (modePanel) {
            for (Controller.Mode mode : Controller.Mode.values()) {
                boolean active = snapshot != null && snapshot.mode() == mode;
                Button button = modes[mode.ordinal()];
                button.setText(active ? "✓ " + mode.label : mode.label);
                button.setTextColor(active ? MINT : TEXT);
                button.setEnabled(!busy && snapshot != null && !active);
            }
            return;
        }
        Controller.Group group = currentGroup();
        groupButton.setText(group == null ? "暂无可选代理组" : group.name() + "  ▾");
        groupButton.setEnabled(!busy && group != null);
        automatic.setVisibility(group != null && group.automatic() ? View.VISIBLE : View.GONE);
        automatic.setEnabled(!busy);
        search.setVisibility(group == null ? View.GONE : View.VISIBLE);
        list.setVisibility(group == null ? View.GONE : View.VISIBLE);
        filterNodes();
    }

    private void filterNodes() {
        options.clear();
        Controller.Group group = currentGroup();
        if (group != null) {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (String option : group.options()) {
                if (option.toLowerCase(Locale.ROOT).contains(query) || Controller.outletLabel(option).contains(query)) {
                    options.add(option);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button control(String text) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(TEXT);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundResource(R.drawable.proxy_quick_button);
        return button;
    }

    private void add(LinearLayout panel, View view, int margin, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height);
        params.topMargin = dp(margin);
        panel.addView(view, params);
    }

    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
