package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Ikiru extends KomikcastClient {
    private static final String DEFAULT_BASE = "https://05.ikiru.wtf";
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_IKIRU); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = CloudflareHelper.apply(new OkHttpClient.Builder()).connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(2, 24L * 60L * 60L * 1000L);
    private static final Map<String, Integer> ID_CACHE = new HashMap<>();
    private static String nonce;
    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "Ikiru"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        MangaCoroutines.io(() -> {
            try {
                String key = Math.max(1, page) + "|" + (sort == null ? "" : sort) + "|" + (query == null ? "" : query) + "|" + (genre == null ? "" : genre);
                ArrayList<MangaPost> cached = LIST_CACHE.get(key);
                if (cached != null) { MangaCoroutines.main(() -> cb.onSuccess(new ArrayList<>(cached), cached.size() >= 1)); return; }
                String html = executeSearch(page, sort, query, genre);
                Document document = Jsoup.parseBodyFragment(html, base());
                ArrayList<String> slugs = new ArrayList<>();
                LinkedHashSet<String> seenSlug = new LinkedHashSet<>();
                for (Element element : document.select("div > a[href*=/manga/]:has(> img), a[href*=/manga/]:has(img)")) {
                    String slug = extractMangaSlug(element.attr("abs:href"));
                    if (!slug.isEmpty() && seenSlug.add(slug)) slugs.add(slug);
                }
                ArrayList<MangaPost> out = fetchPostsBySlugs(slugs);
                boolean hasNext = document.selectFirst("button:has(svg)") != null;
                LIST_CACHE.put(key, new ArrayList<>(out));
                MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
        });
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        MangaCoroutines.io(() -> {
            try {
                ArrayList<GenreItem> out = fetchGenres();
                GENRE_CACHE.put("genres", new ArrayList<>(out));
                MangaCoroutines.main(() -> cb.onSuccess(out, false));
            } catch(Exception e) {
                ArrayList<GenreItem> fallback = fallbackGenres();
                MangaCoroutines.main(() -> cb.onSuccess(fallback, false));
            }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel()) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) if (p != null && (p.latestChapter == null || p.latestChapter.trim().isEmpty()) && p.slug != null && !p.slug.isEmpty()) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (p == null || (p.latestChapter != null && !p.latestChapter.trim().isEmpty()) || p.slug == null || p.slug.isEmpty()) continue;
            chapters(p.slug, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                    if (chapters != null && !chapters.isEmpty()) {
                        MangaChapter newest = chapters.get(0);
                        for (MangaChapter ch : chapters) if (ch.index > newest.index) newest = ch;
                        p.latestChapter = newest.title == null || newest.title.isEmpty() ? "Chapter " + MangaChapter.formatIndex(newest.index) : newest.title;
                        p.latestChapterDate = newest.date == null ? "" : newest.date;
                    }
                    if (remaining.decrementAndGet() <= 0 && done != null) done.run();
                }
                @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String cleanSlug = normalizeMangaSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(cleanSlug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        MangaCoroutines.io(() -> {
            try {
                MangaPost post = fetchPostBySlug(cleanSlug);
                if (post == null) { MangaCoroutines.main(() -> cb.onError("Detail Ikiru kosong")); return; }
                DETAIL_CACHE.put(cleanSlug, post);
                MangaCoroutines.main(() -> cb.onSuccess(post, false));
            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Ikiru gagal dibaca")); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String cleanSlug = normalizeMangaSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(cleanSlug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        MangaCoroutines.io(() -> {
            try {
                int id = getMangaId(cleanSlug);
                HttpUrl url = HttpUrl.parse(base() + "/wp-admin/admin-ajax.php").newBuilder().addQueryParameter("manga_id", String.valueOf(id)).addQueryParameter("page", String.valueOf(new Random().nextInt(9900) + 99)).addQueryParameter("action", "chapter_list").build();
                String html = execute(url.toString());
                Document document = Jsoup.parseBodyFragment(html, base());
                ArrayList<MangaChapter> out = parseChapters(document);
                CHAPTER_CACHE.put(cleanSlug, new ArrayList<>(out));
                MangaCoroutines.main(() -> cb.onSuccess(out, false));
            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Chapter Ikiru gagal dibaca")); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String pageKey = normalizeMangaSlug(slug) + "#" + MangaChapter.formatIndex(index);
        ArrayList<String> cached = PAGE_CACHE.get(pageKey);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        chapters(slug, new Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter chapter = null;
                if (chapters != null) for (MangaChapter ch : chapters) if (Math.abs(ch.index - index) < 0.0001f) { chapter = ch; break; }
                if (chapter == null || chapter.slug == null || chapter.slug.isEmpty()) { cb.onError("Chapter Ikiru tidak ditemukan"); return; }
                getDocument(toAbsolute(chapter.slug), new Result<Document>() {
                    @Override public void onSuccess(Document document, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parsePages(document);
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Ikiru gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String executeSearch(int page, String sort, String query, String genre) throws Exception {
        MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
        body.addFormDataPart("nonce", getNonce());
        body.addFormDataPart("inclusion", "OR");
        body.addFormDataPart("exclusion", "OR");
        body.addFormDataPart("page", String.valueOf(Math.max(1, page)));
        body.addFormDataPart("genre", genreJson(genre));
        body.addFormDataPart("genre_exclude", "[]");
        body.addFormDataPart("author", "[]");
        body.addFormDataPart("artist", "[]");
        body.addFormDataPart("project", "0");
        body.addFormDataPart("type", typeJson(genre));
        body.addFormDataPart("status", statusJson(genre));
        body.addFormDataPart("order", "desc");
        body.addFormDataPart("orderby", orderBy(sort));
        body.addFormDataPart("query", query == null ? "" : query.trim());
        return executePost(base() + "/wp-admin/admin-ajax.php?action=advanced_search", body.build());
    }

    private synchronized String getNonce() throws Exception {
        if (nonce != null && !nonce.trim().isEmpty()) return nonce;
        String html = execute(base() + "/wp-admin/admin-ajax.php?type=search_form&action=get_nonce");
        Document document = Jsoup.parseBodyFragment(html, base());
        String value = document.selectFirst("input[name=search_nonce]") == null ? "" : document.selectFirst("input[name=search_nonce]").attr("value");
        if (value.trim().isEmpty()) throw new Exception("Nonce kosong");
        nonce = value.trim();
        return nonce;
    }

    private ArrayList<GenreItem> fetchGenres() throws Exception {
        Request req = request(base() + "/wp-json/wp/v2/genre?per_page=100&page=1&orderby=count&order=desc").cacheControl(CacheControl.FORCE_NETWORK).build();
        Response response = client.newCall(req).execute();
        String raw = response.body() != null ? response.body().string() : "";
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
        JsonArray data = JsonParser.parseString(transformJson(raw)).getAsJsonArray();
        ArrayList<GenreItem> out = new ArrayList<>();
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String name = getString(object, "name");
            String slug = getString(object, "slug");
            if (!name.isEmpty() && !slug.isEmpty()) out.add(new GenreItem(name, "genre:" + slug));
        }
        out.add(new GenreItem("Manga", "type:manga"));
        out.add(new GenreItem("Manhwa", "type:manhwa"));
        out.add(new GenreItem("Manhua", "type:manhua"));
        out.add(new GenreItem("Ongoing", "status:ongoing"));
        out.add(new GenreItem("Completed", "status:completed"));
        out.add(new GenreItem("Cancelled", "status:cancelled"));
        out.add(new GenreItem("On Hiatus", "status:on-hiatus"));
        out.add(new GenreItem("Unknown", "status:unknown"));
        return out;
    }

    private ArrayList<MangaPost> fetchPostsBySlugs(ArrayList<String> slugs) throws Exception {
        ArrayList<MangaPost> out = new ArrayList<>();
        if (slugs == null || slugs.isEmpty()) return out;
        HttpUrl.Builder builder = HttpUrl.parse(base() + "/wp-json/wp/v2/manga").newBuilder();
        for (String slug : slugs) builder.addQueryParameter("slug[]", slug);
        builder.addQueryParameter("per_page", String.valueOf(slugs.size() + 1));
        builder.addQueryParameter("_embed", null);
        String raw = execute(builder.build().toString());
        JsonArray data = JsonParser.parseString(transformJson(raw)).getAsJsonArray();
        Map<String, MangaPost> map = new HashMap<>();
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            MangaPost post = parsePost(element.getAsJsonObject(), false);
            if (!post.genre.toLowerCase(Locale.ROOT).contains("novel")) map.put(normalizeMangaSlug(post.slug), post);
        }
        for (String slug : slugs) {
            MangaPost post = map.get(slug);
            if (post != null) out.add(post);
        }
        return out;
    }

    private MangaPost fetchPostBySlug(String slug) throws Exception {
        HttpUrl url = HttpUrl.parse(base() + "/wp-json/wp/v2/manga").newBuilder().addQueryParameter("slug[]", slug).addQueryParameter("_embed", null).build();
        String raw = execute(url.toString());
        JsonArray data = JsonParser.parseString(transformJson(raw)).getAsJsonArray();
        if (data.size() <= 0 || !data.get(0).isJsonObject()) return null;
        return parsePost(data.get(0).getAsJsonObject(), true);
    }

    private MangaPost parsePost(JsonObject object, boolean appendId) {
        int id = getInt(object, "id", 0);
        String slugValue = getString(object, "slug");
        if (id > 0 && !slugValue.isEmpty()) ID_CACHE.put(slugValue, id);
        String title = Parser.unescapeEntities(getString(getObject(object, "title"), "rendered"), false);
        String synopsis = Jsoup.parseBodyFragment(getString(getObject(object, "content"), "rendered")).wholeText().trim();
        if (appendId && id > 0 && !synopsis.contains("ID: ")) synopsis = synopsis + "\n\nID: " + id;
        JsonObject embedded = getObject(object, "_embedded");
        String cover = parseCover(embedded);
        String author = joinTerms(embedded, "series-author");
        String genre = joinUnique(joinTerms(embedded, "genre"), joinTerms(embedded, "type"));
        String type = firstType(joinTerms(embedded, "type"));
        String status = normalizeStatus(joinTerms(embedded, "status"));
        return new MangaPost("/manga/" + slugValue + "/", title, cover, author, status, synopsis, genre, type, "", "").withSource(MangaSettingsManager.MANGA_SOURCE_IKIRU, "Ikiru");
    }

    private int getMangaId(String slug) throws Exception {
        String clean = normalizeMangaSlug(slug);
        Integer cached = ID_CACHE.get(clean);
        if (cached != null && cached > 0) return cached;
        MangaPost post = fetchPostBySlug(clean);
        Integer loaded = ID_CACHE.get(clean);
        if (post != null && loaded != null && loaded > 0) return loaded;
        throw new Exception("ID kosong");
    }

    private ArrayList<MangaChapter> parseChapters(Document document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element element : document.select("div a:has(time), a:has(time)")) {
            String url = withoutDomain(element.attr("abs:href"));
            String name = text(element.selectFirst("span"));
            if (name.isEmpty()) name = text(element);
            String date = element.selectFirst("time") == null ? "" : element.selectFirst("time").attr("datetime");
            float index = parseChapterIndex(name, out.size() + 1);
            if (!url.isEmpty() && seen.add(url)) out.add(new MangaChapter(url, index, name, date));
        }
        return out;
    }

    private ArrayList<String> parsePages(Document document) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements images = document.select("main .relative section > img, main section img, article img");
        for (Element img : images) {
            String url = image(img);
            if (url.startsWith("http") && seen.add(url)) out.add(url);
        }
        return out;
    }

    private void getDocument(String url, Result<Document> cb) {
        Request req = request(url).build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                Document document = Jsoup.parse(body, url);
                MAIN.post(() -> cb.onSuccess(document, false));
            }
        });
    }

    private String execute(String url) throws Exception {
        Response response = client.newCall(request(url).build()).execute();
        String body = response.body() != null ? response.body().string() : "";
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
        return body;
    }

    private String executePost(String url, RequestBody body) throws Exception {
        Response response = client.newCall(request(url).post(body).build()).execute();
        String text = response.body() != null ? response.body().string() : "";
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
        return text;
    }

    private Request.Builder request(String url) {
        return new Request.Builder().url(url).header("Referer", base() + "/").header("Origin", base()).header("Accept", "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8").header("Accept-Language", "id,en-US;q=0.9").header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");
    }

    private static String genreJson(String genre) { return jsonForKey(genre, "genre"); }
    private static String typeJson(String genre) { return jsonForKey(genre, "type"); }
    private static String statusJson(String genre) { return jsonForKey(genre, "status"); }

    private static String jsonForKey(String raw, String wanted) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "[]";
        ArrayList<String> values = new ArrayList<>();
        String[] parts = value.split("\\|");
        for (String part : parts) {
            String item = part == null ? "" : part.trim();
            if (item.isEmpty()) continue;
            String key = "genre";
            String id = item;
            int split = item.indexOf(':');
            if (split > 0 && split < item.length() - 1) {
                key = item.substring(0, split).trim();
                id = item.substring(split + 1).trim();
            }
            if (!wanted.equals(key)) continue;
            id = normalizeSlug(id);
            if (!id.isEmpty()) values.add("\"" + id.replace("\"", "") + "\"");
        }
        return values.isEmpty() ? "[]" : "[" + android.text.TextUtils.join(",", values) + "]";
    }


    private static String orderBy(String sort) {
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if ("popular".equals(s) || "popularity".equals(s)) return "popular";
        if ("latest".equals(s) || "update".equals(s) || "updated".equals(s)) return "updated";
        if ("title".equals(s)) return "title";
        return "updated";
    }

    private static String transformJson(String raw) {
        if (raw == null) return "";
        int object = raw.indexOf('{');
        int array = raw.indexOf('[');
        int start;
        if (object < 0) start = array;
        else if (array < 0) start = object;
        else start = Math.min(object, array);
        return start >= 0 ? raw.substring(start) : raw;
    }

    private static String parseCover(JsonObject embedded) {
        JsonArray media = getArray(embedded, "wp:featuredmedia");
        if (media.size() > 0 && media.get(0).isJsonObject()) return getString(media.get(0).getAsJsonObject(), "source_url");
        return "";
    }

    private static String joinTerms(JsonObject embedded, String taxonomy) {
        JsonArray groups = getArray(embedded, "wp:term");
        ArrayList<String> values = new ArrayList<>();
        for (JsonElement groupElement : groups) {
            if (groupElement == null || !groupElement.isJsonArray()) continue;
            JsonArray group = groupElement.getAsJsonArray();
            if (group.size() <= 0 || !group.get(0).isJsonObject()) continue;
            if (!taxonomy.equals(getString(group.get(0).getAsJsonObject(), "taxonomy"))) continue;
            for (JsonElement termElement : group) {
                if (termElement != null && termElement.isJsonObject()) {
                    String name = getString(termElement.getAsJsonObject(), "name");
                    if (!name.isEmpty()) values.add(name);
                }
            }
        }
        return android.text.TextUtils.join(", ", values);
    }

    private static String joinUnique(String first, String second) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String part : (first + ", " + second).split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) values.add(value);
        }
        return android.text.TextUtils.join(", ", new ArrayList<>(values));
    }

    private static String firstType(String types) {
        String lower = types == null ? "" : types.toLowerCase(Locale.ROOT);
        if (lower.contains("manhwa")) return "Manhwa";
        if (lower.contains("manhua")) return "Manhua";
        if (lower.contains("manga")) return "Manga";
        return "Manga";
    }

    private static String normalizeStatus(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (lower.contains("ongoing")) return "Ongoing";
        if (lower.contains("completed")) return "Completed";
        if (lower.contains("cancelled")) return "Cancelled";
        if (lower.contains("hiatus")) return "On Hiatus";
        return value == null ? "" : value.trim();
    }

    private static String toAbsolute(String url) {
        if (url == null || url.trim().isEmpty()) return base();
        String value = url.trim();
        if (value.startsWith("http")) return value;
        if (!value.startsWith("/")) value = "/" + value;
        return base() + value;
    }

    private static String withoutDomain(String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.isEmpty()) return "";
        try {
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed != null) {
                String path = parsed.encodedPath();
                String query = parsed.encodedQuery();
                return query == null || query.isEmpty() ? path : path + "?" + query;
            }
        } catch(Exception ignored) { }
        return value.replace(base(), "");
    }

    private static String extractMangaSlug(String url) { return normalizeMangaSlug(url); }

    private static String normalizeMangaSlug(String value) {
        if (value == null) return "";
        String v = value.trim();
        try {
            HttpUrl parsed = HttpUrl.parse(v.startsWith("http") ? v : base() + (v.startsWith("/") ? v : "/" + v));
            if (parsed != null && parsed.pathSegments().size() >= 2 && "manga".equals(parsed.pathSegments().get(0))) return parsed.pathSegments().get(1);
        } catch(Exception ignored) { }
        v = v.replace(base(), "").replace("/manga/", "");
        int slash = v.indexOf('/');
        if (slash >= 0) v = v.substring(0, slash);
        return v.trim();
    }

    private static String image(Element element) {
        if (element == null) return "";
        String[] attrs = {"abs:data-lazy-src", "abs:data-src", "abs:src", "data-lazy-src", "data-src", "src"};
        for (String attr : attrs) {
            String value = element.attr(attr);
            if (value != null && value.trim().startsWith("http")) return value.trim().split("\\?", 2)[0];
        }
        return "";
    }

    private static String text(Element element) { return element == null ? "" : element.text().trim().replaceAll("\\s+", " "); }

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

    private static String normalizeSlug(String value) {
        if (value == null) return "";
        String out = value.trim().toLowerCase(Locale.ROOT);
        out = out.replace(".", "");
        out = out.replaceAll("[^a-z0-9]+", "-");
        out = out.replaceAll("^-+", "").replaceAll("-+$", "");
        return out;
    }

    private static ArrayList<GenreItem> fallbackGenres() {
        ArrayList<GenreItem> out = new ArrayList<>();
        addGenre(out, "Action", "action"); addGenre(out, "Adventure", "adventure"); addGenre(out, "Comedy", "comedy"); addGenre(out, "Drama", "drama"); addGenre(out, "Fantasy", "fantasy"); addGenre(out, "Horror", "horror"); addGenre(out, "Isekai", "isekai"); addGenre(out, "Romance", "romance"); addGenre(out, "School Life", "school-life"); addGenre(out, "Slice of Life", "slice-of-life"); addGenre(out, "Supernatural", "supernatural"); addGenre(out, "Manga", "type:manga"); addGenre(out, "Manhwa", "type:manhwa"); addGenre(out, "Manhua", "type:manhua"); addGenre(out, "Ongoing", "status:ongoing"); addGenre(out, "Completed", "status:completed");
        return out;
    }

    private static void addGenre(ArrayList<GenreItem> out, String title, String value) { out.add(new GenreItem(title, value.contains(":") ? value : "genre:" + value)); }

    private static JsonObject getObject(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) return new JsonObject();
        return object.getAsJsonObject(name);
    }

    private static JsonArray getArray(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonArray()) return new JsonArray();
        return object.getAsJsonArray(name);
    }

    private static String getString(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        try { return object.get(name).getAsString().trim(); } catch(Exception e) { return ""; }
    }

    private static int getInt(JsonObject object, String name, int fallback) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return fallback;
        try { return object.get(name).getAsInt(); } catch(Exception e) { return fallback; }
    }
}
