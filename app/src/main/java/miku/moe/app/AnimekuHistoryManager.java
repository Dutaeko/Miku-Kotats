package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class AnimekuHistoryManager {
    private static final String PREF = "animeku_watch_history";
    private static final String KEY = "items";
    private static final int MAX_ITEMS = 50;

    public static void save(Context context, HistoryItem item) {
        if (context == null || item == null || item.videoUrl == null || item.videoUrl.trim().isEmpty()) return;
        ArrayList<HistoryItem> list = getHistory(context);
        String newKey = keyOf(item.channelId, item.videoUrl);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (keyOf(list.get(i).channelId, list.get(i).videoUrl).equals(newKey)) list.remove(i);
        }
        item.lastWatched = System.currentTimeMillis();
        if (item.duration < 0) item.duration = 0;
        if (item.position < 0) item.position = 0;
        if (item.duration > 0 && item.position > item.duration) item.position = item.duration;
        list.add(0, item);
        while (list.size() > MAX_ITEMS) list.remove(list.size() - 1);
        saveAll(context, list);
    }

    public static HistoryItem getByChannelId(Context context, int channelId) {
        if (context == null || channelId <= 0) return null;
        ArrayList<HistoryItem> list = getHistory(context);
        for (HistoryItem item : list) if (item != null && item.channelId == channelId) return item;
        return null;
    }

    public static long getPositionForChannel(Context context, int channelId) {
        HistoryItem item = getByChannelId(context, channelId);
        if (item == null) return 0L;
        long position = Math.max(0L, item.position);
        long duration = Math.max(0L, item.duration);
        if (duration > 0 && position >= duration - 5000L) return duration;
        return position;
    }

    public static ArrayList<HistoryItem> getHistory(Context context) {
        ArrayList<HistoryItem> result = new ArrayList<>();
        if (context == null) return result;
        try {
            String raw = prefs(context).getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                HistoryItem item = new HistoryItem(
                        o.optInt("channelId", -1),
                        o.optInt("categoryId", -1),
                        o.optString("categoryName", ""),
                        o.optString("title", ""),
                        o.optString("imageUrl", ""),
                        o.optString("videoUrl", ""),
                        o.optLong("position", 0L),
                        o.optLong("duration", 0L),
                        o.optLong("lastWatched", 0L),
                        o.optString("sourceId", AnimeSettingsManager.SOURCE_ANIMEKU)
                );
                if (!item.videoUrl.trim().isEmpty()) result.add(item);
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static void remove(Context context, HistoryItem item) {
        if (context == null || item == null) return;
        ArrayList<HistoryItem> list = getHistory(context);
        String removeKey = keyOf(item.channelId, item.videoUrl);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (keyOf(list.get(i).channelId, list.get(i).videoUrl).equals(removeKey)) list.remove(i);
        }
        saveAll(context, list);
    }

    public static void clear(Context context) {
        if (context != null) prefs(context).edit().putString(KEY, "[]").apply();
    }

    private static void saveAll(Context context, ArrayList<HistoryItem> list) {
        JSONArray arr = new JSONArray();
        try {
            for (HistoryItem item : list) {
                JSONObject o = new JSONObject();
                o.put("channelId", item.channelId);
                o.put("categoryId", item.categoryId);
                o.put("categoryName", item.categoryName == null ? "" : item.categoryName);
                o.put("title", item.title == null ? "" : item.title);
                o.put("imageUrl", item.imageUrl == null ? "" : item.imageUrl);
                o.put("videoUrl", item.videoUrl == null ? "" : item.videoUrl);
                o.put("position", item.position);
                o.put("duration", item.duration);
                o.put("lastWatched", item.lastWatched);
                o.put("sourceId", AnimeSettingsManager.SOURCE_ANIMEKU);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        prefs(context).edit().putString(KEY, arr.toString()).apply();
    }

    private static String keyOf(int channelId, String url) {
        if (channelId > 0) return "episode:" + channelId;
        return "url:" + (url == null ? "" : url.trim());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
