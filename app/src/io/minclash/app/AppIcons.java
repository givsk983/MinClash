package io.minclash.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import java.io.ByteArrayOutputStream;

final class AppIcons {
    static Bitmap render(Drawable drawable, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Rect original = new Rect(drawable.getBounds());
        try {
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
            return bitmap;
        } finally { drawable.setBounds(original); }
    }

    static String encode(Drawable drawable) {
        Bitmap bitmap = render(drawable, 64);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        if (output.size() > 12288) {
            output.reset();
            bitmap = render(drawable, 48);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private AppIcons() {}
}
