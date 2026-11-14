package io.minclash.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.Button;

import static io.minclash.app.AppUi.*;

/** A compact, stationary entry point; the arc always represents remaining quota. */
final class SubscriptionQuotaIndicator extends View {
    private static final int RED = 0xffff9a9e;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final float density, scaledDensity;
    private double ratio = -1;
    private String percent = "—", expiry = "日期未知";
    private int quotaColor = MINT, expiryColor = MUTED;
    private boolean expiryWarning;

    SubscriptionQuotaIndicator(Context context, Runnable open) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        setFocusable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setOnClickListener(view -> open.run());
    }

    void bind(SubscriptionCatalog.Entry entry, SubscriptionUsage usage, long now) {
        ratio = usage.remainingRatio();
        percent = ratio < 0 ? "—" : ratio > 0 && ratio < .01 ? "<1%" : (int) Math.floor(ratio * 100) + "%";
        quotaColor = ratio >= 0 && ratio <= .05 ? RED : ratio >= 0 && ratio <= .2 ? AMBER : MINT;
        long left = usage.expire() < 0 ? Long.MAX_VALUE : usage.expire() * 1000 - now;
        expiry = usage.expire() < 0 ? "日期未知" : left <= 0 ? "已到期" : left < 3_600_000 ? "<1小时"
            : left < SubscriptionReminderPolicy.DAY ? ((left + 3_599_999) / 3_600_000) + "小时"
            : ((left + SubscriptionReminderPolicy.DAY - 1) / SubscriptionReminderPolicy.DAY) + "天";
        expiryWarning = left <= 7 * SubscriptionReminderPolicy.DAY;
        expiryColor = left <= SubscriptionReminderPolicy.DAY ? RED : expiryWarning ? AMBER : MUTED;
        String description = "订阅额度，" + entry.name() + "，剩余 " + SubscriptionUsage.bytes(usage.remaining())
            + (ratio < 0 ? "" : "，" + percent) + "，" + usage.expiryLabel(now) + "，点按查看流量、有效期和提醒设置";
        setContentDescription(description); setTooltipText(description);
        invalidate();
    }

    @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = getWidth() / 2f, y = 24 * density, radius = 19 * density;
        paint.setStyle(Paint.Style.FILL); paint.setColor(BG);
        canvas.drawCircle(x, y, 24 * density, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3 * density); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(BORDER); canvas.drawCircle(x, y, radius, paint);
        if (ratio > 0) {
            arc.set(x - radius, y - radius, x + radius, y + radius);
            paint.setColor(quotaColor); canvas.drawArc(arc, -90, (float) (360 * ratio), false, paint);
        }
        paint.setStyle(Paint.Style.FILL); paint.setColor(TEXT);
        text(canvas, percent, x, y, 11 * scaledDensity, 30 * density);
        paint.setColor(BG); canvas.drawRoundRect(0, 47 * density, getWidth(), getHeight(), 4 * density, 4 * density, paint);
        paint.setColor(expiryColor);
        text(canvas, expiry, x, 56 * density, 11 * scaledDensity, getWidth() - 2 * density);
        if (expiryWarning) {
            float dotX = x + 14 * density, dotY = y - 14 * density;
            paint.setColor(BG); canvas.drawCircle(dotX, dotY, 6 * density, paint);
            paint.setColor(expiryColor); canvas.drawCircle(dotX, dotY, 4 * density, paint);
        }
        if (isPressed() || isFocused()) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density); paint.setColor(MINT);
            canvas.drawCircle(x, y, 23 * density, paint);
        }
    }

    private void text(Canvas canvas, String value, float x, float centerY, float size, float width) {
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(size);
        float measured = paint.measureText(value);
        if (measured > width) paint.setTextSize(size * width / measured);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(value, x, centerY - (metrics.ascent + metrics.descent) / 2, paint);
    }
}
