package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

public class AnimeSettingsManager {
    private static final String PREF = "anime_source_settings";
    private static final String KEY_SOURCE = "anime_source";
    private static final String KEY_SOURCE_ENABLED_PREFIX = "anime_source_enabled_";
    public static final String SOURCE_DEFAULT = "default";
    public static final String SOURCE_ANIMEKU = "animeku";
    public static final String SOURCE_ANIMELOVERZ = "animeloverz";

    public static String getAnimeSource(Context context) {
        if (context == null) return SOURCE_DEFAULT;
        String source = prefs(context).getString(KEY_SOURCE, SOURCE_DEFAULT);
        if (!isValidSource(source) || !isAnimeSourceEnabled(context, source)) source = getFirstEnabledAnimeSource(context);
        return source;
    }

    public static void setAnimeSource(Context context, String source) {
        if (context == null) return;
        String value = isValidSource(source) ? source : SOURCE_DEFAULT;
        if (!isAnimeSourceEnabled(context, value)) value = getFirstEnabledAnimeSource(context);
        prefs(context).edit().putString(KEY_SOURCE, value).apply();
    }

    public static boolean isAnimekuSource(Context context) {
        return SOURCE_ANIMEKU.equals(getAnimeSource(context));
    }

    public static String getAnimeSourceLabel(Context context) {
        return labelForSourceId(getAnimeSource(context));
    }

    public static String labelForSourceId(String source) {
        if (SOURCE_ANIMEKU.equals(source)) return "Animeku";
        if (SOURCE_ANIMELOVERZ.equals(source)) return "Animeloverz";
        return "Anime X Nonton";
    }

    public static boolean isValidSource(String source) {
        return SOURCE_DEFAULT.equals(source) || SOURCE_ANIMEKU.equals(source) || SOURCE_ANIMELOVERZ.equals(source);
    }

    public static boolean isAnimeSourceEnabled(Context context, String source) {
        if (context == null || !isValidSource(source)) return false;
        return prefs(context).getBoolean(KEY_SOURCE_ENABLED_PREFIX + source, true);
    }

    public static void setAnimeSourceEnabled(Context context, String source, boolean enabled) {
        if (context == null || !isValidSource(source)) return;
        SharedPreferences.Editor editor = prefs(context).edit().putBoolean(KEY_SOURCE_ENABLED_PREFIX + source, enabled);
        editor.apply();
        if (!hasEnabledAnimeSource(context)) {
            prefs(context).edit().putBoolean(KEY_SOURCE_ENABLED_PREFIX + SOURCE_DEFAULT, true).putString(KEY_SOURCE, SOURCE_DEFAULT).apply();
        } else if (!isAnimeSourceEnabled(context, prefs(context).getString(KEY_SOURCE, SOURCE_DEFAULT))) {
            setAnimeSource(context, getFirstEnabledAnimeSource(context));
        }
    }

    public static ArrayList<String> getEnabledAnimeSources(Context context) {
        ArrayList<String> result = new ArrayList<>();
        if (isAnimeSourceEnabled(context, SOURCE_DEFAULT)) result.add(SOURCE_DEFAULT);
        if (isAnimeSourceEnabled(context, SOURCE_ANIMEKU)) result.add(SOURCE_ANIMEKU);
        if (isAnimeSourceEnabled(context, SOURCE_ANIMELOVERZ)) result.add(SOURCE_ANIMELOVERZ);
        if (result.isEmpty()) result.add(SOURCE_DEFAULT);
        return result;
    }

    public static String getFirstEnabledAnimeSource(Context context) {
        ArrayList<String> sources = getEnabledAnimeSources(context);
        return sources.isEmpty() ? SOURCE_DEFAULT : sources.get(0);
    }

    private static boolean hasEnabledAnimeSource(Context context) {
        return isAnimeSourceEnabled(context, SOURCE_DEFAULT) || isAnimeSourceEnabled(context, SOURCE_ANIMEKU) || isAnimeSourceEnabled(context, SOURCE_ANIMELOVERZ);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
