package io.minclash.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;

/** The selected segment follows the core's actual mode. A drag previews one choice until release. */
final class ProxyModeRow extends LinearLayout {
    private final Segments segments;

    ProxyModeRow(Context context, Consumer<Controller.Mode> changeMode) {
        super(context);
        AppUi ui = new AppUi(context);
        var configuration = getResources().getConfiguration();
        boolean stacked = configuration.screenWidthDp < 360 || configuration.fontScale >= 1.3f;
        setOrientation(stacked ? VERTICAL : HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int rowPadding = stacked ? ui.dp(6) : 0;
        setPaddingRelative(ui.dp(14), rowPadding, ui.dp(14), rowPadding);

        TextView title = ui.label("代理模式", 14, AppUi.TEXT, true);
        if (stacked) ui.add(this, title, 0);
        else addView(title, new LayoutParams(0, -2, 1));

        segments = new Segments(context, ui, stacked, changeMode);
        if (stacked) ui.add(this, segments, 6);
        else {
            LayoutParams params = new LayoutParams(-2, -2);
            params.setMarginStart(ui.dp(8));
            addView(segments, params);
        }
    }

    void render(Controller.Mode mode, boolean enabled) {
        segments.render(mode, enabled);
    }

    private static final class Segments extends LinearLayout {
        private final Controller.Mode[] order = {Controller.Mode.DIRECT, Controller.Mode.RULE, Controller.Mode.GLOBAL};
        private final Consumer<Controller.Mode> changeMode;
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int radius;
        private final int touchSlop;
        private final int inset;
        private final int verticalInset;
        private Controller.Mode current;
        private int preview = -1;
        private int pointer = -1;
        private float downX;
        private float downY;
        private boolean dragging;
        private boolean rendered;

        Segments(Context context, AppUi ui, boolean stacked, Consumer<Controller.Mode> changeMode) {
            super(context);
            this.changeMode = changeMode;
            inset = ui.dp(2);
            verticalInset = ui.dp(stacked ? 3 : 6);
            radius = ui.dp(8);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            GradientDrawable track = new GradientDrawable();
            track.setCornerRadius(ui.dp(11));
            track.setColor(AppUi.tone(0xff0f1922, 0xffeaf0f2));
            setBackground(new InsetDrawable(track, 0, verticalInset, 0, verticalInset));
            setPadding(inset, 0, inset, 0);

            for (Controller.Mode mode : order) {
                Button option = new Button(context);
                option.setText(mode.label);
                option.setContentDescription(mode.label + "模式");
                option.setTextSize(13);
                option.setAllCaps(false);
                option.setMinWidth(0);
                option.setMinimumWidth(0);
                option.setMinHeight(ui.dp(48));
                option.setMinimumHeight(0);
                option.setStateListAnimator(null);
                option.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22888888), null,
                    new InsetDrawable(ui.shape(Color.WHITE, 8), 0, verticalInset + inset, 0, verticalInset + inset)));
                option.setPadding(0, 0, 0, 0);
                option.setOnClickListener(view -> select(mode));
                addView(option, stacked ? new LayoutParams(0, -2, 1) : new LayoutParams(ui.dp(54), -2));
            }
        }

        void render(Controller.Mode mode, boolean enabled) {
            if (rendered && current == mode && isEnabled() == enabled) return;
            rendered = true;
            if (mode != current || !enabled) resetDrag();
            current = mode;
            setEnabled(enabled);
            for (int i = 0; i < order.length; i++) {
                View option = getChildAt(i);
                option.setEnabled(enabled);
                option.setSelected(order[i] == current);
            }
            updateHighlight();
        }

        private void select(Controller.Mode mode) {
            if (isEnabled() && mode != current) changeMode.accept(mode);
        }

        private int highlightedIndex() {
            if (preview >= 0) return preview;
            for (int i = 0; i < order.length; i++) if (order[i] == current) return i;
            return -1;
        }

        private void updateHighlight() {
            int active = highlightedIndex();
            for (int i = 0; i < order.length; i++) {
                Button option = (Button) getChildAt(i);
                int color = i == active && isEnabled() ? AppUi.tone(0xff102b23, Color.WHITE)
                    : i == active ? AppUi.TEXT : AppUi.MUTED;
                option.setTextColor(color);
            }
            highlight.setColor(isEnabled() ? AppUi.MINT : AppUi.BORDER);
            invalidate();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            int active = highlightedIndex();
            if (active >= 0) {
                View option = getChildAt(active);
                int top = verticalInset + inset;
                canvas.drawRoundRect(option.getLeft(), top, option.getRight(), getHeight() - top,
                    radius, radius, highlight);
            }
            super.dispatchDraw(canvas);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    resetDrag();
                    pointer = event.getPointerId(0);
                    downX = event.getX();
                    downY = event.getY();
                }
                case MotionEvent.ACTION_MOVE -> {
                    int index = event.findPointerIndex(pointer);
                    if (index < 0) return false;
                    float x = event.getX(index);
                    float horizontal = Math.abs(x - downX);
                    float vertical = Math.abs(event.getY(index) - downY);
                    if (vertical > touchSlop && vertical >= horizontal) resetDrag();
                    else if (horizontal > touchSlop && horizontal > vertical * 1.25f) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        preview(x);
                        return true;
                    }
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> resetDrag();
                default -> { }
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!dragging) return super.onTouchEvent(event);
            int index = event.findPointerIndex(pointer);
            if (index < 0) {
                resetDrag();
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE -> preview(event.getX(index));
                case MotionEvent.ACTION_UP -> {
                    Controller.Mode next = order[indexAt(event.getX(index))];
                    resetDrag();
                    select(next);
                }
                case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> resetDrag();
                default -> { }
            }
            return true;
        }

        private int indexAt(float x) {
            // Use actual child positions so this works in RTL, too.
            int closest = 0;
            float distance = Float.MAX_VALUE;
            for (int i = 0; i < order.length; i++) {
                View option = getChildAt(i);
                float center = (option.getLeft() + option.getRight()) / 2f;
                float offset = Math.abs(x - center);
                if (offset < distance) {
                    closest = i;
                    distance = offset;
                }
            }
            return closest;
        }

        private void preview(float x) {
            int next = indexAt(x);
            if (next == preview) return;
            preview = next;
            updateHighlight();
        }

        private void resetDrag() {
            pointer = -1;
            if (dragging) getParent().requestDisallowInterceptTouchEvent(false);
            dragging = false;
            if (preview < 0) return;
            preview = -1;
            updateHighlight();
        }
    }
}
