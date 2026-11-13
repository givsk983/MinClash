package io.minclash.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AppUi {
    record Summary(String title, String detail, String badge, int sourceIndex) {}
    private record SummaryRow(TextView title, TextView detail, TextView badge) {}
    static final int BG = 0xff0d131a, CARD = 0xff17212b, BORDER = 0xff293641;
    static final int TEXT = 0xffedf4f7, MUTED = 0xff9baebc, MINT = 0xff79e7c9, AMBER = 0xfff5ca88;
    private final Context context;
    AppUi(Context context) { this.context = context; }
    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), BORDER); return drawable;
    }
    LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(shape(CARD, 16)); return view;
    }
    TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        view.setLineSpacing(dp(2), 1); return view;
    }
    TextView line(int size, int color, boolean bold) {
        TextView view = label("", size, color, bold); view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END); return view;
    }
    Button button(String title) {
        Button view = new Button(context); view.setText(title); view.setTextSize(13); view.setAllCaps(false);
        view.setMinWidth(0); view.setMinimumWidth(0); view.setMinHeight(dp(48));
        view.setPadding(dp(10), dp(6), dp(10), dp(6)); view.setGravity(Gravity.CENTER);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9), shape(0xff202e3a, 12), null));
        view.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {MUTED, TEXT}));
        view.setStateListAnimator(null); view.setElevation(0); return view;
    }
    EditText search(String hint) {
        EditText view = new EditText(context); view.setTextSize(14); view.setSingleLine(true);
        view.setHint(hint); view.setHintTextColor(MUTED); view.setTextColor(TEXT);
        view.setPadding(dp(12), dp(10), dp(12), dp(10)); view.setMinHeight(dp(48));
        view.setBackground(shape(BG, 12)); view.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        return view;
    }
    void add(LinearLayout parent, android.view.View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(child, params);
    }
    android.widget.ArrayAdapter<String> spinner(String[] values) {
        return new android.widget.ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, values) {
            private TextView row(int position, android.view.View old, boolean dropdown) {
                TextView view = old instanceof TextView text ? text : line(14, TEXT, false);
                view.setText(getItem(position)); view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(dropdown ? 12 : 4), dp(8), dp(dropdown ? 12 : 4), dp(8)); view.setMinHeight(dp(48));
                view.setBackgroundColor(dropdown ? CARD : android.graphics.Color.TRANSPARENT); return view;
            }
            @Override public android.view.View getView(int position, android.view.View old, android.view.ViewGroup parent) { return row(position, old, false); }
            @Override public android.view.View getDropDownView(int position, android.view.View old, android.view.ViewGroup parent) { return row(position, old, true); }
        };
    }
    void styleList(android.app.AlertDialog dialog) {
        android.widget.ListView list = dialog.getListView();
        list.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        list.setDividerHeight(dp(6)); list.setPadding(dp(10), dp(6), dp(10), dp(8)); list.setClipToPadding(false);
    }
    final class Summaries extends android.widget.BaseAdapter {
        private final java.util.List<Summary> entries = new java.util.ArrayList<>();
        private String empty = "没有匹配的项目";
        void empty(String message) { empty = message; }
        void set(java.util.List<Summary> values) { entries.clear(); entries.addAll(values); notifyDataSetChanged(); }
        public int getCount() { return Math.max(1, entries.size()); }
        public Summary getItem(int position) { return position >= 0 && position < entries.size() ? entries.get(position) : null; }
        public long getItemId(int position) { Summary value = getItem(position); return value == null ? -1 : value.sourceIndex(); }
        public boolean areAllItemsEnabled() { return false; }
        public boolean isEnabled(int position) { return getItem(position) != null; }
        public android.view.View getView(int position, android.view.View old, android.view.ViewGroup parent) {
            Summary value = getItem(position);
            if (value == null) { TextView view = label(empty, 14, MUTED, false); view.setGravity(Gravity.CENTER); view.setPadding(dp(16), dp(28), dp(16), dp(28)); return view; }
            LinearLayout row; SummaryRow holder;
            if (old instanceof LinearLayout existing && existing.getTag() instanceof SummaryRow tag) { row = existing; holder = tag; }
            else {
                row = card(); LinearLayout top = new LinearLayout(context); top.setGravity(Gravity.CENTER_VERTICAL);
                TextView title = line(15, TEXT, true); top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                TextView badge = line(11, MINT, true); badge.setPadding(dp(8), 0, 0, 0); top.addView(badge); row.addView(top);
                TextView detail = label("", 12, MUTED, false); detail.setMaxLines(3); detail.setEllipsize(TextUtils.TruncateAt.END); add(row, detail, 7);
                holder = new SummaryRow(title, detail, badge); row.setTag(holder);
            }
            holder.title().setText(value.title()); holder.detail().setText(value.detail()); holder.badge().setText(value.badge());
            holder.badge().setVisibility(value.badge().isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE); return row;
        }
    }
}
