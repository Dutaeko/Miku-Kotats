package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Calendar;

public final class MangaHistoryManager {
    public static class Progress { 
        public final int page; 
        public final int totalPages; 
        public Progress(int page, int totalPages){ this.page=page; this.totalPages=totalPages; } 
    }

    public static class Entry {
        public final MangaPost manga; 
        public final String chapterTitle; 
        public final float chapterIndex; 
        public final int page; 
        public final int totalPages; 
        public final long time;
        public final String key;
        Entry(MangaPost manga, String chapterTitle, float chapterIndex, int page, int totalPages, long time, String key) { 
            this.manga = manga; 
            this.chapterTitle = chapterTitle; 
            this.chapterIndex = chapterIndex; 
            this.page = page; 
            this.totalPages = totalPages; 
            this.time = time; 
            this.key = key;
        }
    }

    private static final String PREFS = "miku_manga_history"; 
    private static final String KEY_ITEMS = "items"; 
    private static final String KEY_PROGRESS = "chapter_progress";
    private static final int MAX_HISTORY_ITEMS = 200;
    private static final int MAX_PROGRESS_ITEMS = 700;

    private MangaHistoryManager() {}

    public static void save(Context c, MangaPost manga, MangaChapter chapter, int page, int totalPages) {
        try {
            if (c == null || manga == null || chapter == null || manga.slug == null || manga.slug.trim().isEmpty()) return;
            
            ArrayList<JSONObject> all = raw(c);
            String mangaSlug = manga.slug.trim();
            String sourceId = manga.getSourceId();
            String sourceLabel = manga.getSourceLabel();
            long now = System.currentTimeMillis();
            String dayKey = makeDayKey(now);
            String progressKey = sourceId + ":" + mangaSlug;
            String historyKey = progressKey + ":" + dayKey;
            float currentChapterIndex = chapter.index;

            saveChapterProgress(c, progressKey, currentChapterIndex, Math.max(0, page), Math.max(1, totalPages));

            JSONObject previous = null;
            for (int i = all.size() - 1; i >= 0; i--) {
                JSONObject old = all.get(i);
                String oldSource = old.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
                String oldDayKey = old.optString("dayKey", makeDayKey(old.optLong("time", 0L)));
                if (mangaSlug.equals(old.optString("slug")) && sourceId.equals(oldSource)) {
                    if (previous == null) previous = old;
                    if (dayKey.equals(oldDayKey)) all.remove(i);
                }
            }

            JSONObject o = new JSONObject();
            o.put("key", historyKey);
            o.put("dayKey", dayKey);
            o.put("slug", mangaSlug);
            o.put("sourceId", sourceId);
            o.put("sourceLabel", valueOrPrevious(previous, "sourceLabel", sourceLabel));
            o.put("title", valueOrPrevious(previous, "title", manga.title));
            o.put("cover", valueOrPrevious(previous, "cover", manga.coverImage));
            o.put("author", valueOrPrevious(previous, "author", manga.author));
            o.put("status", valueOrPrevious(previous, "status", manga.status));
            o.put("synopsis", valueOrPrevious(previous, "synopsis", manga.synopsis));
            o.put("genre", valueOrPrevious(previous, "genre", manga.genre));
            o.put("typeLabel", valueOrPrevious(previous, "typeLabel", manga.getTypeLabel()));
            o.put("latestChapter", valueOrPrevious(previous, "latestChapter", manga.latestChapter));
            o.put("latestChapterDate", valueOrPrevious(previous, "latestChapterDate", manga.latestChapterDate));
            o.put("totalChapters", Math.max(Math.max(manga.totalChapters, 0), previous == null ? 0 : previous.optInt("totalChapters", 0)));
            o.put("chapterIndex", currentChapterIndex);
            o.put("chapterTitle", valueOrPrevious(previous, "chapterTitle", chapter.title));
            o.put("chapterSlug", valueOrPrevious(previous, "chapterSlug", chapter.slug));
            o.put("chapterId", valueOrPrevious(previous, "chapterId", chapter.chapterId));
            o.put("page", Math.max(0, page));
            o.put("totalPages", Math.max(1, totalPages));
            o.put("time", now);

            all.add(0, o); 
            while (all.size() > MAX_HISTORY_ITEMS) all.remove(all.size() - 1); 
            persist(c, all);
            saveCoverIfEnabled(c, o.optString("cover", manga.coverImage), sourceId);
        } catch (Exception ignored) {}
    }

    public static int getPage(Context c, String slug, float chapterIndex) { 
        Progress p = getProgress(c, slug, chapterIndex); 
        return p == null ? 0 : p.page; 
    }

    public static int getPage(Context c, MangaPost manga, float chapterIndex) {
        if (manga == null) return getPage(c, (String)null, chapterIndex);
        Progress p = getSavedChapterProgress(c, manga.getSourceId() + ":" + manga.slug, chapterIndex);
        if (p != null) return p.page;
        p = getProgress(c, manga.slug, chapterIndex);
        return p == null ? 0 : p.page;
    }

    public static int getTotalPages(Context c, MangaPost manga, float chapterIndex) {
        Progress p = getProgress(c, manga, chapterIndex);
        return p == null ? 0 : Math.max(1, p.totalPages);
    }

    public static Progress getProgress(Context c, String slug, float chapterIndex) { 
        if (slug == null) return null;
        String currentSource = MangaSettingsManager.getMangaSource(c);
        Progress saved = getSavedChapterProgress(c, currentSource + ":" + slug, chapterIndex);
        if (saved != null) return saved;
        for (JSONObject o : raw(c)) {
            if (slug.equals(o.optString("slug")) && Math.abs((float)o.optDouble("chapterIndex", -1d) - chapterIndex) < 0.001f) {
                return new Progress(Math.max(0, o.optInt("page", 0)), Math.max(1, o.optInt("totalPages", 1)));
            }
        }
        return null; 
    }

    public static Progress getProgress(Context c, MangaPost manga, float chapterIndex) {
        if (manga == null) return null;
        String slug = manga.slug == null ? "" : manga.slug.trim();
        if (slug.isEmpty()) return null;
        String sourceId = manga.getSourceId();
        if (!hasHistoryFor(c, sourceId, slug)) {
            removeProgressForManga(c, sourceId, slug);
            return null;
        }
        Progress saved = getSavedChapterProgress(c, sourceId + ":" + slug, chapterIndex);
        if (saved != null) return saved;
        for (JSONObject o : raw(c)) {
            String source = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            if (slug.equals(o.optString("slug")) && sourceId.equals(source) && Math.abs((float)o.optDouble("chapterIndex", -1d) - chapterIndex) < 0.001f) {
                return new Progress(Math.max(0, o.optInt("page", 0)), Math.max(1, o.optInt("totalPages", 1)));
            }
        }
        return null;
    }

    public static ArrayList<Entry> entries(Context c) { 
        ArrayList<Entry> out = new ArrayList<>(); 
        HashSet<String> shownKeys = new HashSet<>();
        for (JSONObject o : raw(c)) { 
            String slug = o.optString("slug");
            String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            String dayKey = o.optString("dayKey", makeDayKey(o.optLong("time", 0L)));
            String uniqueKey = o.optString("key", sourceId + ":" + slug + ":" + dayKey);
            if (slug == null || slug.trim().isEmpty() || shownKeys.contains(uniqueKey)) continue;
            shownKeys.add(uniqueKey);
            MangaPost p = new MangaPost(
                o.optString("slug"), 
                o.optString("title"), 
                o.optString("cover"), 
                o.optString("author"), 
                o.optString("status"), 
                o.optString("synopsis"), 
                o.optString("genre"), 
                o.optString("typeLabel"), 
                o.optString("latestChapter"), 
                o.optString("latestChapterDate")
            ).withSource(sourceId, o.optString("sourceLabel", MangaSourceFactory.labelForSourceId(sourceId))); 
            p.totalChapters = o.optInt("totalChapters", 0); 
            p.historyChapterIndex = (float)o.optDouble("chapterIndex", -1d); 
            p.historyPage = Math.max(0, o.optInt("page", 0)); 
            p.historyTotalPages = Math.max(1, o.optInt("totalPages", 1)); 
            p.historyLastRead = o.optLong("time", 0L); 
            out.add(new Entry(p, o.optString("chapterTitle"), p.historyChapterIndex, p.historyPage, p.historyTotalPages, p.historyLastRead, uniqueKey)); 
        } 
        return out; 
    }


    public static float getLastReadChapterIndex(Context c, MangaPost manga) {
        if (c == null || manga == null || manga.slug == null) return -1f;
        String slug = manga.slug.trim();
        String sourceId = manga.getSourceId();
        if (slug.isEmpty()) return -1f;
        float fallback = -1f;
        for (Entry e : entries(c)) {
            if (e == null || e.manga == null || !slug.equals(e.manga.slug)) continue;
            if (sourceId.equals(e.manga.getSourceId())) return e.chapterIndex;
            if (fallback < 0f) fallback = e.chapterIndex;
        }
        return fallback;
    }


    public static void cleanOrphanProgress(Context c, MangaPost manga) {
        if (c == null || manga == null) return;
        String slug = manga.slug == null ? "" : manga.slug.trim();
        if (slug.isEmpty()) return;
        String sourceId = manga.getSourceId();
        if (!hasHistoryFor(c, sourceId, slug)) removeProgressForManga(c, sourceId, slug);
    }

    public static boolean hasHistoryFor(Context c, MangaPost manga) {
        if (c == null || manga == null) return false;
        String slug = manga.slug == null ? "" : manga.slug.trim();
        if (slug.isEmpty()) return false;
        return hasHistoryFor(c, manga.getSourceId(), slug);
    }

    private static boolean hasHistoryFor(Context c, String sourceId, String slug) {
        if (c == null || sourceId == null || slug == null || slug.trim().isEmpty()) return false;
        String normalizedSlug = slug.trim();
        for (JSONObject o : raw(c)) {
            String itemSource = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            if (normalizedSlug.equals(o.optString("slug")) && sourceId.equals(itemSource)) return true;
        }
        return false;
    }

    private static void removeProgressForManga(Context c, String sourceId, String slug) {
        if (c == null || sourceId == null || slug == null || slug.trim().isEmpty()) return;
        try {
            String prefix = sourceId + ":" + slug.trim() + "_";
            JSONObject root = new JSONObject(p(c).getString(KEY_PROGRESS, "{}"));
            Iterator<String> iterator = root.keys();
            ArrayList<String> toRemove = new ArrayList<>();
            while (iterator.hasNext()) {
                String key = iterator.next();
                if (key.startsWith(prefix)) toRemove.add(key);
            }
            if (toRemove.isEmpty()) return;
            for (String key : toRemove) root.remove(key);
            p(c).edit().putString(KEY_PROGRESS, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static ArrayList<MangaPost> asPosts(Context c) { 
        ArrayList<MangaPost> out = new ArrayList<>(); 
        for (Entry e : entries(c)) { 
            e.manga.latestChapter = e.chapterTitle; 
            e.manga.latestChapterDate = "Hal. " + (e.page + 1) + "/" + Math.max(1, e.totalPages); 
            out.add(e.manga); 
        } 
        return out; 
    }

    public static void clear(Context c) { 
        if (c != null) p(c).edit().putString(KEY_ITEMS, "[]").remove(KEY_PROGRESS).apply(); 
    }

    public static void delete(Context c, java.util.HashSet<String> keys) {
        if (c == null || keys == null || keys.isEmpty()) return;
        ArrayList<JSONObject> all = raw(c);
        HashSet<String> changedMangaKeys = new HashSet<>();
        HashSet<String> removedProgressKeys = new HashSet<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            JSONObject o = all.get(i);
            String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
            String slug = o.optString("slug");
            String dayKey = o.optString("dayKey", makeDayKey(o.optLong("time", 0L)));
            String mangaKey = sourceId + ":" + slug;
            String itemKey = o.optString("key", mangaKey + ":" + dayKey);
            if (keys.contains(itemKey) || keys.contains(mangaKey)) {
                changedMangaKeys.add(mangaKey);
                removedProgressKeys.add(mangaKey + "_" + (float)o.optDouble("chapterIndex", -1d));
                all.remove(i);
            }
        }
        if (changedMangaKeys.isEmpty()) return;
        removeDeletedProgress(c, all, changedMangaKeys, removedProgressKeys);
        persist(c, all);
    }


    private static void removeDeletedProgress(Context c, ArrayList<JSONObject> remainingHistory, HashSet<String> changedMangaKeys, HashSet<String> removedProgressKeys) {
        if (c == null || changedMangaKeys == null || changedMangaKeys.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(p(c).getString(KEY_PROGRESS, "{}"));
            HashSet<String> keepProgressKeys = new HashSet<>();
            for (JSONObject o : remainingHistory) {
                String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
                String slug = o.optString("slug");
                String mangaKey = sourceId + ":" + slug;
                if (changedMangaKeys.contains(mangaKey)) keepProgressKeys.add(mangaKey + "_" + (float)o.optDouble("chapterIndex", -1d));
            }
            Iterator<String> iterator = root.keys();
            ArrayList<String> toRemove = new ArrayList<>();
            while (iterator.hasNext()) {
                String key = iterator.next();
                for (String mangaKey : changedMangaKeys) {
                    if (key.startsWith(mangaKey + "_") && (removedProgressKeys.contains(key) || !keepProgressKeys.contains(key))) {
                        toRemove.add(key);
                        break;
                    }
                }
            }
            for (String key : toRemove) root.remove(key);
            p(c).edit().putString(KEY_PROGRESS, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static int count(Context c) { 
        return entries(c).size(); 
    }

    public static void updateEntry(Context c, Entry entry, MangaPost freshManga, MangaChapter freshChapter, int totalChapters) {
        if (c == null || entry == null || entry.manga == null) return;
        try {
            String oldSlug = entry.manga.slug == null ? "" : entry.manga.slug.trim();
            String sourceId = entry.manga.getSourceId();
            String entryKey = entry.key == null ? "" : entry.key.trim();
            if (oldSlug.isEmpty()) return;
            ArrayList<JSONObject> all = raw(c);
            for (JSONObject o : all) {
                String itemSource = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
                String itemSlug = o.optString("slug");
                String itemDayKey = o.optString("dayKey", makeDayKey(o.optLong("time", 0L)));
                String itemKey = o.optString("key", itemSource + ":" + itemSlug + ":" + itemDayKey);
                float itemChapterIndex = (float)o.optDouble("chapterIndex", -1d);
                if (!entryKey.isEmpty()) {
                    if (!entryKey.equals(itemKey)) continue;
                } else if (!sourceId.equals(itemSource) || !oldSlug.equals(itemSlug) || Math.abs(itemChapterIndex - entry.chapterIndex) >= 0.001f) {
                    continue;
                }
                if (freshManga != null) {
                    putIfNotEmpty(o, "slug", freshManga.slug);
                    putIfNotEmpty(o, "title", freshManga.title);
                    putIfNotEmpty(o, "cover", freshManga.coverImage);
                    putIfNotEmpty(o, "author", freshManga.author);
                    putIfNotEmpty(o, "status", freshManga.status);
                    putIfNotEmpty(o, "synopsis", freshManga.synopsis);
                    putIfNotEmpty(o, "genre", freshManga.genre);
                    putIfNotEmpty(o, "typeLabel", freshManga.getTypeLabel());
                    putIfNotEmpty(o, "latestChapter", freshManga.latestChapter);
                    putIfNotEmpty(o, "latestChapterDate", freshManga.latestChapterDate);
                    o.put("sourceId", freshManga.getSourceId());
                    o.put("sourceLabel", freshManga.getSourceLabel());
                }
                if (freshChapter != null) {
                    putIfNotEmpty(o, "chapterTitle", freshChapter.title);
                    putIfNotEmpty(o, "chapterSlug", freshChapter.slug);
                    putIfNotEmpty(o, "chapterId", freshChapter.chapterId);
                    o.put("chapterIndex", freshChapter.index);
                }
                if (totalChapters > 0) o.put("totalChapters", totalChapters);
                break;
            }
            persist(c, all);
            MangaPost coverManga = freshManga == null ? entry.manga : freshManga;
            saveCoverIfEnabled(c, coverManga.coverImage, coverManga.getSourceId());
        } catch (Exception ignored) {}
    }

    private static void saveCoverIfEnabled(Context c, String coverImage, String sourceId) {
        if (c == null || coverImage == null || coverImage.trim().isEmpty()) return;
        if (!MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(c)) return;
        MangaCoverCache.saveAsync(c.getApplicationContext(), coverImage, sourceId);
    }

    private static String valueOrPrevious(JSONObject previous, String key, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) return text;
        if (previous == null) return "";
        return previous.optString(key, "");
    }

    private static void putIfNotEmpty(JSONObject o, String key, String value) {
        try {
            if (o != null && value != null && !value.trim().isEmpty()) o.put(key, value.trim());
        } catch (Exception ignored) {}
    }

    private static void saveChapterProgress(Context c, String slug, float chapterIndex, int page, int totalPages) {
        if (c == null || slug == null) return;
        try {
            JSONObject root = new JSONObject(p(c).getString(KEY_PROGRESS, "{}"));
            JSONObject o = new JSONObject();
            int safeTotal = Math.max(1, totalPages);
            o.put("page", Math.max(0, Math.min(page, safeTotal - 1)));
            o.put("totalPages", safeTotal);
            root.put(slug.trim() + "_" + chapterIndex, o);
            trimProgress(root);
            p(c).edit().putString(KEY_PROGRESS, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static Progress getSavedChapterProgress(Context c, String slug, float chapterIndex) {
        if (c == null || slug == null) return null;
        try {
            JSONObject root = new JSONObject(p(c).getString(KEY_PROGRESS, "{}"));
            JSONObject o = root.optJSONObject(slug.trim() + "_" + chapterIndex);
            if (o == null) return null;
            return new Progress(Math.max(0, o.optInt("page", 0)), Math.max(1, o.optInt("totalPages", 1)));
        } catch (Exception ignored) { return null; }
    }

    private static void trimProgress(JSONObject root) {
        if (root == null || root.length() <= MAX_PROGRESS_ITEMS) return;
        try {
            ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = root.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            int removeCount = Math.max(0, keys.size() - MAX_PROGRESS_ITEMS);
            for (int i = 0; i < removeCount; i++) root.remove(keys.get(i));
        } catch (Exception ignored) {}
    }

    private static ArrayList<JSONObject> raw(Context c) { 
        ArrayList<JSONObject> list = new ArrayList<>(); 
        if (c == null) return list; 
        try { 
            JSONArray arr = new JSONArray(p(c).getString(KEY_ITEMS, "[]")); 
            for (int i=0; i<arr.length(); i++) list.add(arr.getJSONObject(i)); 
        } catch(Exception ignored) {} 
        return list; 
    }

    private static void persist(Context c, ArrayList<JSONObject> list) { 
        JSONArray arr = new JSONArray(); 
        for (JSONObject o: list) arr.put(o); 
        p(c).edit().putString(KEY_ITEMS, arr.toString()).apply(); 
    }

    private static String makeDayKey(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp <= 0 ? System.currentTimeMillis() : timestamp);
        return cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.DAY_OF_YEAR);
    }

    private static SharedPreferences p(Context c) { 
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); 
    }
}
