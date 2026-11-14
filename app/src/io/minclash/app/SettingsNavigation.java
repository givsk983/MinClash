package io.minclash.app;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compact settings index with one detail page visible at a time. */
final class SettingsNavigation {
    private record Page(String title, LinearLayout body, TextView summary) {}
    private final AppUi ui;
    private final ScrollView scroll;
    private final LinearLayout root;
    private final LinearLayout index;
    private final TextView title;
    private final TextView subtitle;
    private final Button back;
    private final Runnable changed;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, Integer> positions = new LinkedHashMap<>();
    private String selected = "";

    SettingsNavigation(Context context, LinearLayout parent, ScrollView scroll, Runnable changed) {
        ui = new AppUi(context);
        this.scroll = scroll;
        this.changed = changed;
        root = parent;
        back = ui.button("‹  返回设置");
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setVisibility(View.GONE);
        back.setOnClickListener(view -> back());
        ui.add(root, back, 0);
        title = label("设置", 30, AppUi.TEXT, true);
        ui.add(root, title, 0);
        subtitle = label("常用功能与连接偏好", 13, AppUi.MUTED, false);
        ui.add(root, subtitle, 5);
        index = ui.column();
        ui.add(root, index, 2);
    }

    LinearLayout group(String name) {
        ui.add(index, label(name, 12, AppUi.MUTED, true), 12);
        LinearLayout group = ui.column();
        group.setBackground(ui.shape(AppUi.CARD, 16));
        ui.add(index, group, 6);
        return group;
    }

    LinearLayout page(LinearLayout group, String id, String name, String summary) {
        return entry(group, id, name, summary, null);
    }

    void action(LinearLayout group, String id, String name, String summary, Runnable action) {
        entry(group, id, name, summary, action);
    }

    private LinearLayout entry(LinearLayout group, String id, String name, String summary, Runnable action) {
        if (group.getChildCount() > 0) {
            View divider = new View(root.getContext());
            divider.setBackgroundColor(AppUi.BORDER);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, ui.dp(1));
            line.setMargins(ui.dp(16), 0, ui.dp(16), 0);
            group.addView(divider, line);
        }
        LinearLayout row = new LinearLayout(root.getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        row.setMinimumHeight(ui.dp(60));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x2279e7c9), null, ui.shape(0xffffffff, 16)));
        row.setFocusable(true);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName(Button.class.getName());
            }
        });
        LinearLayout text = ui.column();
        ui.add(text, label(name, 16, AppUi.TEXT, true), 0);
        TextView detail = label(summary, 12, AppUi.MUTED, false);
        ui.add(text, detail, 4);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = label("›", 24, AppUi.MUTED, false);
        arrow.setPadding(ui.dp(12), 0, 0, 0);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        row.setOnClickListener(view -> { if (action == null) show(id); else action.run(); });
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = null;
        if (action == null) {
            body = ui.column();
            body.setVisibility(View.GONE);
            ui.add(root, body, 18);
        }
        pages.put(id, new Page(name, body, detail));
        return body;
    }

    void summary(String id, String value) {
        TextView text = pages.get(id).summary();
        if (!value.contentEquals(text.getText())) text.setText(value);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = ui.label(text, size, color, bold);
        view.setIncludeFontPadding(false);
        return view;
    }

    void show(String id) {
        if (!id.isEmpty() && (!pages.containsKey(id) || pages.get(id).body() == null)) id = "";
        positions.put(selected, scroll.getScrollY());
        selected = id;
        boolean home = selected.isEmpty();
        index.setVisibility(home ? View.VISIBLE : View.GONE);
        back.setVisibility(home ? View.GONE : View.VISIBLE);
        title.setText(home ? "设置" : pages.get(selected).title());
        LinearLayout.LayoutParams heading = (LinearLayout.LayoutParams) title.getLayoutParams();
        heading.topMargin = ui.dp(home ? 0 : 12);
        title.setLayoutParams(heading);
        subtitle.setVisibility(home ? View.VISIBLE : View.GONE);
        for (Map.Entry<String, Page> entry : pages.entrySet()) {
            LinearLayout body = entry.getValue().body();
            if (body != null) body.setVisibility(entry.getKey().equals(selected) ? View.VISIBLE : View.GONE);
        }
        int offset = positions.getOrDefault(selected, 0);
        scroll.post(() -> scroll.scrollTo(0, offset));
        changed.run();
    }

    boolean inDetail() { return !selected.isEmpty(); }
    boolean back() {
        if (!inDetail()) return false;
        show("");
        return true;
    }

    void save(Bundle state) {
        positions.put(selected, scroll.getScrollY());
        state.putString("settings_section", selected);
        for (Map.Entry<String, Integer> entry : positions.entrySet()) state.putInt("settings_scroll_" + entry.getKey(), entry.getValue());
    }

    void restore(Bundle state) {
        String target = state.getString("settings_section", "");
        show(target);
        positions.put("", state.getInt("settings_scroll_", 0));
        for (String id : pages.keySet()) positions.put(id, state.getInt("settings_scroll_" + id, 0));
        int offset = positions.getOrDefault(selected, 0);
        scroll.post(() -> scroll.scrollTo(0, offset));
    }
}
