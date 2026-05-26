package moe.miku.app.parser;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Shinigami extends KomikcastClient {
    private static final String DEFAULT_BASE = "https://c.shinigami.asia";
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI); }
    private static final String API = "https://api.shngm.io";
    private static final String CDN = "https://storage.shngm.id";

    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = CloudflareHelper.apply(new OkHttpClient.Builder()).connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, String> CHAPTER_ID_CACHE = new MangaMemoryCache<>(400, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;

    @Override protected String sourceLabel() { return "Shinigami"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            String safeQuery = query == null ? "" : query.trim();
            String safeSort = sort == null || sort.trim().isEmpty() ? "latest" : sort.trim();
            if (safeSort.equals("popular") || safeSort.equals("popularity")) safeSort = "popularity";
            else safeSort = "latest";
            StringBuilder url = new StringBuilder(API + "/v1/manga/list?page=" + page + "&page_size=30");
            if (!safeQuery.isEmpty()) url.append("&q=").append(URLEncoder.encode(safeQuery, "UTF-8"));
            else url.append("&sort=").append(URLEncoder.encode(safeSort, "UTF-8"));
            String genreFilter = normalizeFilterValue(genre);
            if (!genreFilter.isEmpty()) url.append("&genre=").append(URLEncoder.encode(genreFilter, "UTF-8"));
            String key = url.toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 30); return; }
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
                                int current = getInt(meta, "page", page);
                                int total = firstPositive(getInt(meta, "total_page", -1), getInt(meta, "totalPage", page));
                                hasNext = current < total;
                            }
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            boolean finalHasNext = hasNext;
                            MangaCoroutines.main(() -> cb.onSuccess(out, finalHasNext));
                        } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Shinigami gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch (Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    private String normalizeFilterValue(String raw) {
        if (raw == null) return "";
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            if (value.toLowerCase(Locale.ROOT).startsWith("type:")) value = value.substring(5).trim();
            value = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+", "").replaceAll("-+$", "");
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        final boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel();
        final boolean loadType = MangaSettingsManager.shouldLoadTypeLabel();
        if (!loadChapter && !loadType) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) if (p != null && p.slug != null && !p.slug.isEmpty()) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (p == null || p.slug == null || p.slug.isEmpty()) continue;
            if (!loadType) {
                chapters(p.slug, chapterCallback(p, remaining, done));
                continue;
            }
            detail(p.slug, new Result<MangaPost>() {
                @Override public void onSuccess(MangaPost d, boolean ignored) {
                    if (d != null) {
                        if (p.genre == null || p.genre.trim().isEmpty()) p.genre = d.genre;
                        if (d.typeLabel != null && !d.typeLabel.trim().isEmpty()) p.typeLabel = d.typeLabel;
                        if (p.author == null || p.author.trim().isEmpty()) p.author = d.author;
                        if (p.status == null || p.status.trim().isEmpty()) p.status = d.status;
                    }
                    if (loadChapter) chapters(p.slug, chapterCallback(p, remaining, done)); else if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (loadChapter) chapters(p.slug, chapterCallback(p, remaining, done)); else if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }
    private Result<ArrayList<MangaChapter>> chapterCallback(MangaPost p, java.util.concurrent.atomic.AtomicInteger remaining, Runnable done) {
        return new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                if (chapters != null && !chapters.isEmpty()) {
                    MangaChapter newest = chapters.get(0);
                    for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
                    p.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
                    p.latestChapterDate = newest.date == null ? "" : newest.date;
                }
                if (remaining.decrementAndGet() <= 0 && done != null) done.run();
            }
            @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
        };
    }
    @Override public void detail(String slug, Result<MangaPost> cb) {
        MangaPost cached = DETAIL_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        get(API + "/v1/manga/detail/" + slug, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = getObject(root, "data");
                        if (data == null) { MangaCoroutines.main(() -> cb.onError("Detail manga Shinigami kosong")); return; }
                        MangaPost parsed = parseDetail(slug, data);
                        DETAIL_CACHE.put(slug, parsed);
                        MangaCoroutines.main(() -> cb.onSuccess(parsed, false));
                    } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Shinigami gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        get(API + "/v1/chapter/" + slug + "/list?page_size=3000", new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonArray data = getArray(root, "data");
                        for (JsonElement el : data) {
                            if (el == null || !el.isJsonObject()) continue;
                            JsonObject item = el.getAsJsonObject();
                            float idx = getFloat(item, "chapter_number", -1f);
                            if (idx < 0) continue;
                            String key = MangaChapter.formatIndex(idx);
                            if (!seen.add(key)) continue;
                            String chapterId = firstNonEmpty(getString(item, "chapter_id"), getString(item, "chapterId"), getString(item, "id"), getString(item, "chapter_uuid"), getString(item, "uuid"));
                            if (!chapterId.isEmpty()) {
                                CHAPTER_ID_CACHE.put(slug + ":" + key, chapterId);
                                CHAPTER_ID_CACHE.put(slug + ":" + idx, chapterId);
                            }
                            MangaChapter chapter = new MangaChapter(slug, idx, getString(item, "chapter_title"), KomikcastClient.prettyDate(getString(item, "release_date")));
                            chapter.chapterId = chapterId;
                            out.add(chapter);
                        }
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter Shinigami gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String chapterKey = slug + ":" + MangaChapter.formatIndex(index);
        String pageKey = "shinigami:" + chapterKey;
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        String chapterId = CHAPTER_ID_CACHE.get(chapterKey);
        if (chapterId == null || chapterId.trim().isEmpty()) chapterId = CHAPTER_ID_CACHE.get(slug + ":" + index);
        if (chapterId == null || chapterId.trim().isEmpty()) chapterId = findChapterIdFromCachedList(slug, index);
        if (chapterId == null || chapterId.trim().isEmpty()) {
            chapters(slug, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) { pages(slug, index, cb); }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        get(API + "/v1/chapter/detail/" + chapterId, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<String> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        JsonObject data = getObject(root, "data");
                        JsonObject chapter = getObject(data, "chapter");
                        if (chapter == null) chapter = data;
                        String path = firstNonEmpty(getString(chapter, "path"), getString(chapter, "image_path"), getString(chapter, "imagePath"), getString(chapter, "directory"));
                        JsonArray pages = getArray(chapter, "data");
                        if (pages.size() == 0) pages = getArray(chapter, "pages");
                        if (pages.size() == 0) pages = getArray(chapter, "images");
                        for (JsonElement image : pages) if (image != null && !image.isJsonNull()) {
                            String name = image.isJsonObject() ? firstNonEmpty(getString(image.getAsJsonObject(), "url"), getString(image.getAsJsonObject(), "src"), getString(image.getAsJsonObject(), "image"), getString(image.getAsJsonObject(), "filename"), getString(image.getAsJsonObject(), "name")) : image.getAsString();
                            String url = buildImageUrl(path, name);
                            if (url.startsWith("http") && seen.add(url)) out.add(url);
                        }
                        if (!out.isEmpty()) PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Shinigami gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String findChapterIdFromCachedList(String slug, float index) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached == null || cached.isEmpty()) return "";
        for (MangaChapter ch : cached) {
            if (ch == null) continue;
            if (Math.abs(ch.index - index) < 0.001f && ch.chapterId != null && !ch.chapterId.trim().isEmpty()) return ch.chapterId.trim();
        }
        return "";
    }

    private String buildImageUrl(String path, String name) {
        String safeName = name == null ? "" : name.trim();
        if (safeName.startsWith("http://") || safeName.startsWith("https://")) {
            return safeName.replace("https://storage.shngm.id", CDN).replace("http://storage.shngm.id", CDN).replace("https://storage.shngm.io", CDN).replace("http://storage.shngm.io", CDN).replace("https://storage.shinigami.id", CDN).replace("http://storage.shinigami.id", CDN);
        }
        String safePath = path == null ? "" : path.trim();
        if (safePath.startsWith("http://") || safePath.startsWith("https://")) {
            if (!safePath.endsWith("/")) safePath = safePath + "/";
            while (safeName.startsWith("/")) safeName = safeName.substring(1);
            return safePath.replace("https://storage.shngm.id", CDN).replace("http://storage.shngm.id", CDN).replace("https://storage.shngm.io", CDN).replace("http://storage.shngm.io", CDN) + safeName;
        }
        if (!safePath.startsWith("/")) safePath = "/" + safePath;
        if (!safePath.endsWith("/")) safePath = safePath + "/";
        while (safeName.startsWith("/")) safeName = safeName.substring(1);
        return CDN + safePath + safeName;
    }

    private void get(String url, Result<JsonObject> cb) {
        Request req = new Request.Builder().url(url)
                .header("Referer", base() + "/")
                .header("Origin", base())
                .header("Accept", "application/json")
                .header("DNT", "1")
                .header("Sec-GPC", "1")
                .header("Accept-Language", "id,en-US;q=0.9")
                .header("User-Agent", "Mozilla/5.0")
                .build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code())); return; }
                try { JsonObject obj = JsonParser.parseString(body).getAsJsonObject(); MangaCoroutines.main(() -> cb.onSuccess(obj, false)); }
                catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Data Shinigami gagal dibaca")); }
            }
        });
    }

    private MangaPost parsePost(JsonObject item) {
        String slug = firstNonEmpty(getString(item, "manga_id"), getString(item, "mangaId"), getString(item, "id"), getString(item, "slug"));
        String title = getString(item, "title");
        String cover = firstNonEmpty(getString(item, "cover_image_url"), getString(item, "coverImageUrl"), getString(item, "thumbnail"), getString(item, "cover"));
        String format = firstNonEmpty(getString(item, "format"), getString(item, "type"), getString(item, "comic_type"), taxonomyText(item, "Format"));
        String genre = taxonomyText(item, "Genre");
        return new MangaPost(slug, title, cover, "", "", "", genre, firstNonEmpty(format, inferTypeFromText(title + " " + genre)), "", "").withSource(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI, "Shinigami");
    }

    private MangaPost parseDetail(String slug, JsonObject data) {
        String description = getString(data, "description");
        String status = statusLabel(getInt(data, "status", 0));
        JsonObject taxonomy = getObject(data, "taxonomy");
        String author = joinTaxonomy(taxonomy, "Author");
        String artist = joinTaxonomy(taxonomy, "Artist");
        String genres = joinTaxonomy(taxonomy, "Genre");
        String format = joinTaxonomy(taxonomy, "Format");
        String creator = firstNonEmpty(author, artist);
        return new MangaPost(slug, getString(data, "title"), firstNonEmpty(getString(data, "cover_image_url"), getString(data, "coverImageUrl"), getString(data, "thumbnail")), creator, status, description, genres, firstNonEmpty(format, inferTypeFromText(genres)), "", "").withSource(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI, "Shinigami");
    }

    private String taxonomyText(JsonObject item, String key) {
        JsonObject taxonomy = getObject(item, "taxonomy");
        String joined = joinTaxonomy(taxonomy, key);
        if (!joined.isEmpty()) return joined;
        JsonArray arr = getArray(item, key.toLowerCase(Locale.ROOT));
        ArrayList<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el == null || el.isJsonNull()) continue;
            if (el.isJsonObject()) {
                String name = getString(el.getAsJsonObject(), "name");
                if (!name.isEmpty()) out.add(name);
            } else {
                String name = el.getAsString();
                if (name != null && !name.trim().isEmpty()) out.add(name.trim());
            }
        }
        return android.text.TextUtils.join(", ", out);
    }

    private String joinTaxonomy(JsonObject taxonomy, String key) {
        JsonArray arr = getArray(taxonomy, key);
        ArrayList<String> out = new ArrayList<>();
        for (JsonElement el : arr) if (el != null && el.isJsonObject()) {
            String name = getString(el.getAsJsonObject(), "name");
            if (!name.isEmpty()) out.add(name);
        }
        return android.text.TextUtils.join(", ", out);
    }

    private String inferTypeFromText(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (t.contains("manhwa")) return "manhwa";
        if (t.contains("manhua")) return "manhua";
        if (t.contains("webtoon")) return "webtoon";
        return "manga";
    }

    private String statusLabel(int status) {
        if (status == 1) return "Ongoing";
        if (status == 2) return "Completed";
        return "Unknown";
    }

    private static JsonObject getObject(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null; } catch(Exception e){return null;} }
    private static JsonArray getArray(JsonObject o, String k) { try { return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); } catch(Exception e){return new JsonArray();} }
    private static String getString(JsonObject o, String k) { try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; } catch(Exception e){return "";} }
    private static String firstNonEmpty(String... values) { for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim(); return ""; }
    private static int getInt(JsonObject o, String k, int def) { try { return o != null && o.has(k) ? o.get(k).getAsInt() : def; } catch(Exception e){return def;} }
    private static int firstPositive(int... values) { for (int v : values) if (v > 0) return v; return 0; }
    private static float getFloat(JsonObject o, String k, float def) { try { return o != null && o.has(k) ? o.get(k).getAsFloat() : def; } catch(Exception e){return def;} }
}
