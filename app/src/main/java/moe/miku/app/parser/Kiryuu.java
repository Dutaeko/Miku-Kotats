package moe.miku.app.parser;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Kiryuu extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KIRYUU); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = CloudflareHelper.apply(new OkHttpClient.Builder()).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(1, CACHE_TTL);
    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "Kiryuu"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            HttpUrl url = buildListUrl(page, sort, query, genre);
            String key = url.toString();
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 1); return; }
            getText(key, new Result<String>() {
                @Override public void onSuccess(String body, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseList(body);
                            boolean hasNext = out.size() >= 1;
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Kiryuu gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getText(buildGenreUrl(), new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenres(body);
                        appendTypeFilters(out);
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Genre Kiryuu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanMangaSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getJson(base() + "/api/manga/" + clean, new Result<JsonObject>() {
            @Override public void onSuccess(JsonObject root, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        JsonObject data = obj(root, "data");
                        JsonObject info = data == null ? null : obj(data, "info");
                        if (info == null) { MangaCoroutines.main(() -> cb.onError("Detail Kiryuu kosong")); return; }
                        MangaPost post = parsePost(info);
                        ArrayList<MangaChapter> chapters = parseChapters(info);
                        post.totalChapters = chapters.size();
                        if (!chapters.isEmpty()) post.latestChapter = chapters.get(0).title;
                        DETAIL_CACHE.put(clean, post);
                        CHAPTER_CACHE.put(clean, new ArrayList<>(chapters));
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Kiryuu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanMangaSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        detail(clean, new Result<MangaPost>() {
            @Override public void onSuccess(MangaPost data, boolean hasNext) {
                ArrayList<MangaChapter> chapters = CHAPTER_CACHE.get(clean);
                cb.onSuccess(chapters == null ? new ArrayList<>() : new ArrayList<>(chapters), false);
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String clean = cleanMangaSlug(slug);
        String pageKey = clean + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(clean, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter target = null;
                if (chapters != null) for (MangaChapter chapter : chapters) if (Math.abs(chapter.index - index) < 0.0001f) { target = chapter; break; }
                if (target == null && chapters != null && !chapters.isEmpty()) target = chapters.get(0);
                if (target == null || target.slug == null || target.slug.trim().isEmpty()) { cb.onError("Chapter Kiryuu tidak ditemukan"); return; }
                String chapterSlug = cleanChapterSlug(target.slug);
                getJson(base() + "/api/read/" + clean + "/" + chapterSlug, new Result<JsonObject>() {
                    @Override public void onSuccess(JsonObject root, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> pages = parsePages(root);
                                PAGE_CACHE.put(pageKey, new ArrayList<>(pages));
                                MangaCoroutines.main(() -> cb.onSuccess(pages, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Kiryuu gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private HttpUrl buildListUrl(int page, String sort, String query, String genre) throws Exception {
        int safePage = Math.max(1, page);
        boolean searching = query != null && !query.trim().isEmpty();
        if (searching) {
            HttpUrl.Builder builder = HttpUrl.parse(base() + "/api/manga-list").newBuilder();
            builder.addQueryParameter("q", query.trim());
            builder.addQueryParameter("page", String.valueOf(safePage));
            builder.addQueryParameter("limit", "8");
            return builder.build();
        }
        String genreFilter = extractGenreFilter(genre);
        String typeFilter = extractTypeFilter(genre);
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if (("manga".equals(s) || "manhwa".equals(s) || "manhua".equals(s)) && typeFilter.isEmpty()) typeFilter = s;
        String order = "latest";
        if ("popular".equals(s) || "popularity".equals(s)) order = "popular";
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/manga").newBuilder();
        if (!genreFilter.isEmpty()) builder.addQueryParameter("genre", genreFilter);
        if (!typeFilter.isEmpty()) builder.addQueryParameter("type", typeFilter);
        builder.addQueryParameter("order", order);
        if (safePage > 1) builder.addQueryParameter("page", String.valueOf(safePage));
        builder.addQueryParameter("_rsc", "1");
        return builder.build();
    }

    private ArrayList<MangaPost> parseList(String body) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonArray data = null;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement element = root.get("data");
            if (element != null && element.isJsonArray()) data = element.getAsJsonArray();
        } catch(Exception ignored) { }
        if (data == null) data = extractArray(body, "mangas");
        if (data == null) return out;
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            MangaPost post = parsePost(element.getAsJsonObject());
            String key = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
            if (!key.isEmpty() && seen.add(key)) out.add(post);
        }
        return out;
    }

    private MangaPost parsePost(JsonObject object) {
        String slug = cleanMangaSlug(str(object, "slug"));
        String title = cleanTitle(str(object, "title"));
        String cover = str(object, "coverImage");
        String author = str(object, "author");
        String status = str(object, "status");
        String synopsis = str(object, "synopsis");
        String type = str(object, "type");
        String genre = joinStringArray(arr(object, "genres"));
        String latest = str(object, "last_chapter");
        MangaPost post = new MangaPost(slug, title, cover, author, status, synopsis, genre, type, latest, "").withSource(MangaSettingsManager.MANGA_SOURCE_KIRYUU, "Kiryuu");
        post.totalChapters = intval(object, "chapter_count", 0);
        return post;
    }

    private ArrayList<MangaChapter> parseChapters(JsonObject info) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonArray chapters = arr(info, "chapters");
        if (chapters == null) return out;
        for (JsonElement element : chapters) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String title = str(object, "title");
            String chapterSlug = cleanChapterSlug(str(object, "slug"));
            float index = parseChapterIndex(title, out.size() + 1);
            if (!chapterSlug.isEmpty() && seen.add(chapterSlug)) out.add(new MangaChapter(chapterSlug, index, title, ""));
        }
        return out;
    }

    private ArrayList<String> parsePages(JsonObject root) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonObject data = obj(root, "data");
        JsonObject chapter = data == null ? null : obj(data, "chapter");
        JsonArray images = chapter == null ? null : arr(chapter, "images");
        if (images != null) for (JsonElement element : images) {
            if (element == null || element.isJsonNull()) continue;
            String url = element.getAsString().trim();
            if (url.startsWith("http") && seen.add(url)) out.add(url);
        }
        return out;
    }

    private ArrayList<GenreItem> parseGenres(String body) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<JsonArray> arrays = extractArrays(body, "genres");
        for (JsonArray genres : arrays) {
            if (genres == null || genres.size() == 0) continue;
            ArrayList<GenreItem> candidate = new ArrayList<>();
            LinkedHashSet<String> candidateSeen = new LinkedHashSet<>();
            for (JsonElement element : genres) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                String name = str(object, "name");
                if (name.isEmpty()) continue;
                String key = name.toLowerCase(Locale.ROOT);
                if (!candidateSeen.add(key)) continue;
                candidate.add(new GenreItem(name, name));
            }
            if (candidate.size() > out.size()) out = candidate;
        }
        if (!out.isEmpty()) return out;
        Pattern pattern = Pattern.compile("genre=([^&\\\"]+)");
        Matcher matcher = pattern.matcher(body == null ? "" : body);
        while (matcher.find()) {
            String name = decodeUrl(matcher.group(1));
            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) continue;
            out.add(new GenreItem(name, name));
        }
        return out;
    }

    private static String buildGenreUrl() {
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/manga").newBuilder();
        builder.addQueryParameter("order", "latest");
        builder.addQueryParameter("_rsc", "1");
        return builder.build().toString();
    }

    private static JsonArray extractArray(String body, String key) {
        if (body == null) return null;
        String needle = "\"" + key + "\":[";
        int start = body.indexOf(needle);
        if (start < 0) return null;
        int arrayStart = body.indexOf('[', start);
        if (arrayStart < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = arrayStart; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    try { return JsonParser.parseString(body.substring(arrayStart, i + 1)).getAsJsonArray(); }
                    catch(Exception ignored) { return null; }
                }
            }
        }
        return null;
    }


    private static ArrayList<JsonArray> extractArrays(String body, String key) {
        ArrayList<JsonArray> arrays = new ArrayList<>();
        if (body == null) return arrays;
        String needle = "\"" + key + "\":[";
        int search = 0;
        while (search >= 0 && search < body.length()) {
            int start = body.indexOf(needle, search);
            if (start < 0) break;
            int arrayStart = body.indexOf('[', start);
            if (arrayStart < 0) break;
            int depth = 0;
            boolean inString = false;
            boolean escape = false;
            boolean added = false;
            for (int i = arrayStart; i < body.length(); i++) {
                char c = body.charAt(i);
                if (escape) { escape = false; continue; }
                if (c == '\\') { escape = true; continue; }
                if (c == '"') { inString = !inString; continue; }
                if (inString) continue;
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        try { arrays.add(JsonParser.parseString(body.substring(arrayStart, i + 1)).getAsJsonArray()); } catch(Exception ignored) { }
                        search = i + 1;
                        added = true;
                        break;
                    }
                }
            }
            if (!added) break;
        }
        return arrays;
    }

    private void getJson(String url, Result<JsonObject> cb) {
        getText(url, new Result<String>() {
            @Override public void onSuccess(String body, boolean hasNext) {
                try { cb.onSuccess(JsonParser.parseString(body).getAsJsonObject(), false); }
                catch(Exception e) { cb.onError("Data Kiryuu gagal dibaca"); }
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void getText(String url, Result<String> cb) {
        Request.Builder builder = new Request.Builder().url(url).header("Referer", refererFor(url)).header("Origin", base()).header("Accept", "*/*").header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8").header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");
        if (isRscUrl(url)) builder.header("RSC", "1").header("Next-Url", "/manga").header("Sec-Fetch-Site", "same-origin").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty");
        Request request = builder.build();
        CloudflareHelper.enqueue(client, request, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                MAIN.post(() -> cb.onSuccess(body, false));
            }
        });
    }

    private static boolean isRscUrl(String url) {
        if (url == null) return false;
        return url.contains("/manga") && url.contains("_rsc=");
    }

    private static String refererFor(String url) {
        String root = base();
        if (url == null) return root + "/";
        if (url.contains("/api/read/")) return root + "/read/";
        if (url.contains("/api/manga/")) return root + "/manga";
        if (url.contains("/api/manga-list")) return root + "/manga";
        if (url.contains("/manga")) return root + "/manga";
        return root + "/";
    }

    private static JsonObject obj(JsonObject object, String key) {
        if (object == null) return null;
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject object, String key) {
        if (object == null) return null;
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String str(JsonObject object, String key) {
        if (object == null) return "";
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        try { return element.getAsString().trim(); } catch(Exception ignored) { return ""; }
    }

    private static int intval(JsonObject object, String key, int fallback) {
        if (object == null) return fallback;
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        try { return element.getAsInt(); } catch(Exception ignored) { return fallback; }
    }

    private static String joinStringArray(JsonArray array) {
        if (array == null) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) continue;
            String value;
            try { value = element.getAsString().trim(); } catch(Exception ignored) { continue; }
            if (value.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }

    private static String cleanTitle(String title) {
        if (title == null) return "";
        String value = title.trim();
        int len = value.length();
        if (len > 0 && len % 2 == 0) {
            String left = value.substring(0, len / 2).trim();
            String right = value.substring(len / 2).trim();
            if (!left.isEmpty() && left.equals(right)) return left;
        }
        return value;
    }

    private static String cleanMangaSlug(String slug) {
        if (slug == null) return "";
        String value = slug.trim();
        if (value.startsWith("http")) {
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed != null) value = parsed.encodedPath();
        }
        value = value.replaceFirst("^/manga/", "").replaceFirst("^manga/", "");
        value = value.replaceFirst("^/", "");
        int q = value.indexOf('?');
        if (q >= 0) value = value.substring(0, q);
        return value.replaceAll("/+$", "");
    }

    private static String cleanChapterSlug(String slug) {
        if (slug == null) return "";
        String value = slug.trim();
        if (value.startsWith("http")) {
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed != null) value = parsed.encodedPath();
        }
        value = value.replaceFirst("^/read/[^/]+/", "").replaceFirst("^read/[^/]+/", "");
        value = value.replaceFirst("^/", "");
        int q = value.indexOf('?');
        if (q >= 0) value = value.substring(0, q);
        return value.replaceAll("/+$", "");
    }

    private static String extractGenreFilter(String genre) {
        if (genre == null) return "";
        String[] parts = genre.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith("type:")) continue;
            if (value.toLowerCase(Locale.ROOT).startsWith("genre:")) value = value.substring(value.indexOf(':') + 1).trim();
            return value;
        }
        return "";
    }

    private static String extractTypeFilter(String genre) {
        if (genre == null) return "";
        String[] parts = genre.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.toLowerCase(Locale.ROOT).startsWith("type:")) continue;
            value = value.substring(value.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT);
            if ("manga".equals(value) || "manhwa".equals(value) || "manhua".equals(value)) return value;
        }
        return "";
    }

    private static String decodeUrl(String value) {
        if (value == null) return "";
        try { return java.net.URLDecoder.decode(value, "UTF-8").trim(); } catch(Exception ignored) { return value.trim(); }
    }

    private static void appendTypeFilters(ArrayList<GenreItem> out) {
        if (out == null) return;
        out.add(new GenreItem("Manga", "type:manga"));
        out.add(new GenreItem("Manhwa", "type:manhwa"));
        out.add(new GenreItem("Manhua", "type:manhua"));
    }

    private static float parseChapterIndex(String name, int fallback) {
        if (name == null) return fallback;
        Matcher chapterMatcher = Pattern.compile("(?i)chapter\\s*([0-9]+(?:[.,][0-9]+)?)").matcher(name);
        if (chapterMatcher.find()) {
            try { return Float.parseFloat(chapterMatcher.group(1).replace(",", ".")); } catch(Exception ignored) { }
        }
        Matcher numberMatcher = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)").matcher(name);
        if (numberMatcher.find()) {
            try { return Float.parseFloat(numberMatcher.group(1).replace(",", ".")); } catch(Exception ignored) { }
        }
        return fallback;
    }
}
