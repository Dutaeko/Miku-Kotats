package miku.moe.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;

import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.MaterialColors;

public final class ThemeManager {
    public static final String THEME_DEFAULT = "default";
    public static final String THEME_MIKU = "miku";
    public static final String THEME_BLUE = "blue";
    public static final String THEME_GREEN = "green";
    public static final String THEME_PURPLE = "purple";
    public static final String THEME_RED = "red";
    private static final String PREFS_NAME = "miku_moe_ui_prefs";
    private static final String KEY_APP_THEME = "app_theme";

    private ThemeManager() {}

    public static void applyNightMode(Context context) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }

    public static void applyTheme(Activity activity) {
        applyNightMode(activity);
        activity.setTheme(getThemeStyle(activity));
        int surfaceColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, 0);
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(surfaceColor));
    }

    public static String[] getThemeValues() {
        return new String[]{THEME_DEFAULT, THEME_MIKU, THEME_BLUE, THEME_GREEN, THEME_PURPLE, THEME_RED};
    }

    public static String[] getThemeLabels() {
        return new String[]{"Default", "Miku Pink", "Ocean Blue", "Green Apple", "Lavender", "Strawberry"};
    }

    public static String getTheme(Context context) {
        String theme = prefs(context).getString(KEY_APP_THEME, THEME_DEFAULT);
        for (String value : getThemeValues()) {
            if (value.equals(theme)) return value;
        }
        return THEME_DEFAULT;
    }

    public static void setTheme(Context context, String theme) {
        String selected = THEME_DEFAULT;
        for (String value : getThemeValues()) {
            if (value.equals(theme)) {
                selected = value;
                break;
            }
        }
        prefs(context).edit().putString(KEY_APP_THEME, selected).apply();
    }

    public static int getThemeStyle(Context context) {
        String theme = getTheme(context);
        if (THEME_MIKU.equals(theme)) return R.style.AppTheme_Miku;
        if (THEME_BLUE.equals(theme)) return R.style.AppTheme_Blue;
        if (THEME_GREEN.equals(theme)) return R.style.AppTheme_Green;
        if (THEME_PURPLE.equals(theme)) return R.style.AppTheme_Purple;
        if (THEME_RED.equals(theme)) return R.style.AppTheme_Red;
        return R.style.AppTheme;
    }

    public static String getThemeLabel(Context context) {
        String theme = getTheme(context);
        String[] values = getThemeValues();
        String[] labels = getThemeLabels();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(theme)) return labels[i];
        }
        return labels[0];
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
