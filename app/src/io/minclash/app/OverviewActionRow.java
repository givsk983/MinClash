package io.minclash.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A single overview action with room for the current value to wrap. */
final class OverviewActionRow extends LinearLayout {
    private final TextView detail;

    OverviewActionRow(Context context, String title, boolean withSummary, Runnable action) {
        super(context);
        AppUi ui = new AppUi(context);
        setGravity(Gravity.CENTER_VERTICAL);
        setPaddingRelative(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9));
        setMinimumHeight(ui.dp(48));
        setFocusable(true);
        setScreenReaderFocusable(true);
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x2279e7c9), null, ui.shape(Color.WHITE, 0)));
        setOnClickListener(view -> action.run());

        TextView name = ui.label(title, 14, AppUi.TEXT, true);
        name.setDuplicateParentStateEnabled(true);
        name.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
            new int[] {AppUi.MUTED, AppUi.TEXT}));
        detail = withSummary ? ui.label("—", 13, AppUi.MUTED, false) : null;
        var configuration = getResources().getConfiguration();
        boolean stack = withSummary && (configuration.screenWidthDp < 360 || configuration.fontScale >= 1.3f);
        if (stack) {
            LinearLayout text = ui.column();
            text.setDuplicateParentStateEnabled(true);
            ui.add(text, name, 0);
            ui.add(text, detail, 3);
            addView(text, new LayoutParams(0, -2, 1));
        } else {
            addView(name, new LayoutParams(0, -2, withSummary ? 2 : 1));
            if (withSummary) {
                detail.setGravity(Gravity.END);
                detail.setPaddingRelative(ui.dp(8), 0, 0, 0);
                addView(detail, new LayoutParams(0, -2, 3));
            }
        }
        TextView arrow = ui.label("›", 20, AppUi.MUTED, false);
        arrow.setPaddingRelative(ui.dp(10), 0, 0, 0);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(arrow);
    }

    void summary(String value) {
        if (detail != null && !TextUtils.equals(detail.getText(), value)) detail.setText(value);
    }

    @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
}
