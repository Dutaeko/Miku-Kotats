package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class KomikcastClient {
    public interface Result<T> { void onSuccess(T data, boolean hasNext); void onError(String message); }
    public static class GenreItem {
        public final String title;
        public final String value;
        public GenreItem(String title, String value) {
            this.title = title == null ? "" : title;
            this.value = value == null ? "" : value;
        }
    }

    public void genres(Result<ArrayList<GenreItem>> cb) {
        cb.onError("Genre belum tersedia");
    }
    private static final String DEFAULT_BASE = "https://v2.komikcast.fit";
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST); }
    private static final String API = "https://be.komikcast.cc";
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = CloudflareHelper.apply(new OkHttpClient.Builder()).connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    protected String sourceLabel() { return "KomikCast"; }

    public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            StringBuilder url = new StringBuilder(API + "/series?includeMeta=true&take=12&page=" + page);
            String safeSort = sort == null || sort.trim().isEmpty() ? "latest" : sort.trim();
            if (safeSort.equals("popular")) safeSort = "popularity";
            url.append("&sort=").append(URLEncoder.encode(safeSort, "UTF-8")).append("&sortOrder=desc");
            if (query != null && !query.trim().isEmpty()) {
                String q = query.trim().replace("\"", "");
                String filter = "title=like=\"" + q + "\",nativeTitle=like=\"" + q + "\"";
                url.append("&filter=").append(URLEncoder.encode(filter, "UTF-8"));
            }
            if (genre != null && !genre.trim().isEmpty()) {
                url.append("&genreIds=").append(URLEncoder.encode(genre.trim(), "UTF-8"));
            }
            String key = url.toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 12); return; }
            get(key, new Result<JsonObject>() {
                @Override public void onSuccess(JsonObject root, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            JsonArray data = getArray(root, "data");
                            for (JsonElement el : data) if (el != null && el.isJsonObject()) {
                                MangaPost p = parsePost(el.getAsJsonObject());
                                String k = !p.slug.isEmpty() ? p.slug : p.title;
                                if (!k.isEmpty() && seen.add(k)) out.add(p);
                            }
                            boolean hasNext = false;
                            JsonObject meta = getObject(root, "meta");
                            if (meta != null) {
                                int p = getInt(meta, "page", page); int last = getInt(meta, "lastPage", page);
                                hasNext = p < last;
                            }
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            boolean finalHasNext = hasNext;
                            MangaCoroutines.main(() -> cb.onSuccess(out, finalHasNext));
                        } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar manga gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch (Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }


    public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel()) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) {
            if (p != null && (p.latestChapter == null || p.latestChapter.trim().isEmpty()) && p.slug != null && !p.slug.isEmpty()) {
                remaining.incrementAndGet();
            }
        }
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (p == null || (p.latestChapter != null && !p.latestChapter.trim().isEmpty()) || p.slug == null || p.slug.isEmpty()) continue;
            chapters(p.slug, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                    if (chapters != null && !chapters.isEmpty()) {
                        MangaChapter newest = chapters.get(0);
                        for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
                        p.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
                        p.latestChapterDate = newest.date == null ? "" : newest.date;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) {
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
            });
        }
    }

    public void detail(String slug, Result<MangaPost> cb) {
        MangaPost cached = DETAIL_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        get(API + "/series/" + slug, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        if (data == null) { MangaCoroutines.main(() -> cb.onError("Detail kosong")); return; }
                        MangaPost parsed = parsePost(data); DETAIL_CACHE.put(slug, parsed); MangaCoroutines.main(() -> cb.onSuccess(parsed, false));
                    } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Detail manga gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        get(API + "/series/" + slug + "/chapters", new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonArray data = getArray(root, "data");
                        for (JsonElement el : data) {
                            if (el == null || !el.isJsonObject()) continue;
                            JsonObject item = el.getAsJsonObject(); JsonObject d = getObject(item, "data"); if (d == null) d = item;
                            float idx = getFloat(d, "index", getFloat(item, "chapterIndex", -1));
                            if (idx < 0) continue;
                            String key = MangaChapter.formatIndex(idx);
                            if (!seen.add(key)) continue;
                            String rawDate = firstNonEmpty(getString(item, "createdAt"), getString(item, "updatedAt"), getString(d, "createdAt"), getString(d, "updatedAt"));
                            out.add(new MangaChapter(slug, idx, getString(d, "title"), prettyDate(rawDate)));
                        }
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out)); MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String pageKey = slug + ":" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        get(API + "/series/" + slug + "/chapters/" + MangaChapter.formatIndex(index), new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonObject item = getObject(root, "data");
                        if (item == null) { MangaCoroutines.main(() -> cb.onError("Chapter kosong")); return; }
                        JsonObject d = getObject(item, "data"); if (d == null) d = item;
                        JsonArray images = getArray(d, "images");
                        for (JsonElement image : images) if (image != null && !image.isJsonNull()) {
                            String url = image.getAsString();
                            if (url != null && url.startsWith("http") && seen.add(url)) out.add(url);
                        }
                        PAGE_CACHE.put(pageKey, new ArrayList<>(out)); MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman chapter gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void get(String url, Result<JsonObject> cb) {
        Request req = new Request.Builder().url(url).header("Referer", base() + "/").header("Origin", base()).header("Accept", "application/json").header("Accept-Language", "id,en-US;q=0.9").header("User-Agent", "Mozilla/5.0").build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code())); return; }
                try { JsonObject obj = JsonParser.parseString(body).getAsJsonObject(); MangaCoroutines.main(() -> cb.onSuccess(obj, false)); }
                catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Data manga gagal dibaca")); }
            }
        });
    }

    private MangaPost parsePost(JsonObject item) {
        JsonObject d = getObject(item, "data"); if (d == null) d = item;
        String slug = getString(d, "slug"); if (slug.isEmpty()) slug = getString(item, "id");
        ArrayList<String> gs = new ArrayList<>();
        JsonArray genres = getArray(d, "genres");
        for (JsonElement g : genres) if (g != null && g.isJsonObject()) { JsonObject go = g.getAsJsonObject(); JsonObject gd = getObject(go, "data"); if (gd == null) gd = go; String n = getString(gd, "name"); if(!n.isEmpty()) gs.add(n); }
        String genre = android.text.TextUtils.join(", ", gs);
        String typeLabel = firstNonEmpty(getString(d,"format"), getString(d,"type"), getString(d,"comicType"), getString(d,"seriesType"), inferTypeFromGenres(genre));
        String latestChapter = firstNonEmpty(getString(d,"latestChapter"), getString(d,"lastChapter"), getString(d,"chapter"), getString(item,"latestChapter"), getString(d,"lastChapterIndex"), getString(d,"latestChapterIndex"));
        String latestDate = firstNonEmpty(getString(d,"latestChapterDate"), getString(d,"updatedAt"), getString(item,"updatedAt"), getString(item,"createdAt"));
        if (latestChapter.isEmpty()) latestChapter = parseLatestChapterFromArrays(d);
        if (!latestChapter.isEmpty() && !latestChapter.toLowerCase(Locale.ROOT).contains("chapter")) latestChapter = "Chapter " + latestChapter;
        return new MangaPost(slug, getString(d,"title"), getString(d,"coverImage"), getString(d,"author"), getString(d,"status"), getString(d,"synopsis"), genre, typeLabel, latestChapter, prettyDate(latestDate)).withSource(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST, "KomikCast");
    }

    private String parseLatestChapterFromArrays(JsonObject d) {
        String[] names = {"chapters", "latestChapters", "chapterList"};
        for (String n : names) {
            JsonArray arr = getArray(d, n);
            if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                JsonObject o = arr.get(0).getAsJsonObject(); JsonObject data = getObject(o, "data"); if (data == null) data = o;
                String idx = firstNonEmpty(getString(data, "index"), getString(o, "chapterIndex"), getString(data, "title"));
                if (!idx.isEmpty()) return idx;
            }
        }
        return "";
    }
    private static String inferTypeFromGenres(String genre) {
        String text = genre == null ? "" : genre.toLowerCase(Locale.ROOT);
        if (text.contains("manhwa")) return "manhwa"; if (text.contains("manhua")) return "manhua"; if (text.contains("webtoon")) return "webtoon"; return "manga";
    }
    public static String prettyDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        try {
            String r = raw.replace("Z", "+00:00");
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            return new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")).format(in.parse(r));
        } catch(Exception ignored) { }
        return raw.length() > 10 ? raw.substring(0, 10) : raw;
    }
    private static JsonObject getObject(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null; } catch(Exception e){return null;} }
    private static JsonArray getArray(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); } catch(Exception e){return new JsonArray();} }
    private static String getString(JsonObject o, String k) { try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; } catch(Exception e){return "";} }
    private static String firstNonEmpty(String... values) { for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim(); return ""; }
    private static int getInt(JsonObject o, String k, int def) { try { return o.has(k) ? o.get(k).getAsInt() : def; } catch(Exception e){return def;} }
    private static float getFloat(JsonObject o, String k, float def) { try { return o.has(k) ? o.get(k).getAsFloat() : def; } catch(Exception e){return def;} }
}
