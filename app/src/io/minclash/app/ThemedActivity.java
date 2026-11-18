package io.minclash.app;

import android.app.Activity;
import android.os.Bundle;

/** Applies the saved palette before Android creates widgets or dialogs. */
public abstract class ThemedActivity extends Activity {
    private boolean lightTheme;
    @Override protected void onCreate(Bundle state) {
        lightTheme = AppUi.isLight(this);
        AppUi.configure(this);
        setTheme(lightTheme ? R.style.AppThemeLight : R.style.AppTheme);
        super.onCreate(state);
    }
    @Override protected void onResume() {
        super.onResume();
        if (lightTheme != AppUi.isLight(this)) recreate();
    }
}
