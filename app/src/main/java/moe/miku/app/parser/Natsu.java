package moe.miku.app.parser;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class Natsu extends KomikcastClient {
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_NATSU); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final OkHttpClient CLIENT = CloudflareHelper.apply(new OkHttpClient.Builder()).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(1, CACHE_TTL);
    private static String cachedNonce = "";
    private static long nonceTime = 0L;
    private final OkHttpClient client = CLIENT;

    @Override protected String sourceLabel() { return "Natsu"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            int safePage = Math.max(1, page);
            String safeSort = sort == null || sort.trim().isEmpty() ? "latest" : sort.trim().toLowerCase(Locale.ROOT);
            if ("popular".equals(safeSort)) safeSort = "popularity";
            String safeQuery = query == null ? "" : query.trim();
            FilterSpec filter = parseFilter(genre);
            boolean useAjax = !safeQuery.isEmpty() || filter.hasFilter() || "popularity".equals(safeSort);
            if (useAjax) {
                String key = "ajax|" + safePage + "|" + safeSort + "|" + safeQuery + "|" + filter.key();
                ArrayList<MangaPost> cached = LIST_CACHE.get(key);
                if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 1); return; }
                advancedSearch(safePage, safeSort, safeQuery, filter, new Result<String>() {
                    @Override public void onSuccess(String body, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<MangaPost> out = parseList(body);
                                boolean hasNext = hasAdvancedPage(body, safePage + 1);
                                LIST_CACHE.put(key, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Natsu gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
                return;
            }
            String url = buildSimpleUrl(safePage, safeSort);
            String key = url;
            ArrayList<MangaPost> cached = LIST_CACHE.get(key);
            if (cached != null) { cb.onSuccess(new ArrayList<>(cached), cached.size() >= 1); return; }
            getText(url, new Result<String>() {
                @Override public void onSuccess(String body, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = parseList(body);
                            boolean hasNext = hasThePage(body, safePage + 1);
                            LIST_CACHE.put(key, new ArrayList<>(out));
                            MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                        } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar Natsu gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getText(base() + "/library/", new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenres(body);
                        GENRE_CACHE.put("genres", new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Genre Natsu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String clean = cleanMangaSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getText(mangaUrl(clean), new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        Document doc = Jsoup.parse(body, mangaUrl(clean));
                        MangaPost post = parseDetail(clean, doc);
                        DETAIL_CACHE.put(clean, post);
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail Natsu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String clean = cleanMangaSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(clean);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getText(mangaUrl(clean), new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        String mangaId = extractMangaId(body);
                        ArrayList<MangaChapter> direct = parseChapters(Jsoup.parse(body, mangaUrl(clean)), clean);
                        if (mangaId.isEmpty()) {
                            CHAPTER_CACHE.put(clean, new ArrayList<>(direct));
                            MangaCoroutines.main(() -> cb.onSuccess(direct, false));
                            return;
                        }
                        fetchChapterPage(clean, mangaId, 1, new ArrayList<>(direct), cb);
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter Natsu gagal dibaca")); }
                });
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
                if (target == null || target.slug == null || target.slug.trim().isEmpty()) { cb.onError("Chapter Natsu tidak ditemukan"); return; }
                String chapterUrl = target.slug.startsWith("http") ? target.slug : base() + "/manga/" + clean + "/" + cleanChapterSlug(target.slug) + "/";
                getText(chapterUrl, new Result<String>() {
                    @Override public void onSuccess(String body, boolean ignored) {
                        MangaCoroutines.io(() -> {
                            try {
                                ArrayList<String> out = parsePages(Jsoup.parse(body, chapterUrl));
                                PAGE_CACHE.put(pageKey, new ArrayList<>(out));
                                MangaCoroutines.main(() -> cb.onSuccess(out, false));
                            } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Halaman Natsu gagal dibaca")); }
                        });
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private String buildSimpleUrl(int page, String sort) throws Exception {
        String path = "project".equals(sort) || "projects".equals(sort) ? "/project/" : "/latest-update/";
        HttpUrl.Builder builder = HttpUrl.parse(base() + path).newBuilder();
        if (page > 1) builder.addQueryParameter("the_page", String.valueOf(page));
        return builder.build().toString();
    }

    private void advancedSearch(int page, String sort, String query, FilterSpec filter, Result<String> cb) {
        getNonce(new Result<String>() {
            @Override public void onSuccess(String nonce, boolean ignored) {
                try {
                    String orderby = "popular";
                    if ("latest".equals(sort) || "update".equals(sort) || "updated".equals(sort)) orderby = "updated";
                    RequestBody body = new FormBody.Builder()
                            .add("nonce", nonce == null ? "" : nonce)
                            .add("inclusion", "OR")
                            .add("exclusion", "OR")
                            .add("page", String.valueOf(Math.max(1, page)))
                            .add("genre", jsonArray(filter.genres))
                            .add("genre_exclude", "[]")
                            .add("author", jsonArray(filter.authors))
                            .add("artist", jsonArray(filter.artists))
                            .add("project", ("project".equals(sort) || "projects".equals(sort)) ? "1" : "0")
                            .add("type", jsonArray(filter.types))
                            .add("status", jsonArray(filter.statuses))
                            .add("order", "desc")
                            .add("orderby", orderby)
                            .add("query", query == null ? "" : query.trim())
                            .build();
                    Request req = new Request.Builder().url(base() + "/wp-admin/admin-ajax.php?action=advanced_search").post(body).headers(headers("*/*", base() + "/library/")).build();
                    enqueueText(req, cb);
                } catch(Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void getNonce(Result<String> cb) {
        long now = System.currentTimeMillis();
        if (!cachedNonce.isEmpty() && now - nonceTime < CACHE_TTL) { cb.onSuccess(cachedNonce, false); return; }
        getText(base() + "/library/", new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                String nonce = firstMatch(body, "nonce[\\s:=\\\"']+([A-Za-z0-9_-]{6,})");
                if (nonce.isEmpty()) nonce = firstMatch(body, "[?&]nonce=([A-Za-z0-9_-]{6,})");
                cachedNonce = nonce;
                nonceTime = System.currentTimeMillis();
                cb.onSuccess(nonce, false);
            }
            @Override public void onError(String message) { cb.onSuccess("", false); }
        });
    }

    private void fetchChapterPage(String clean, String mangaId, int page, ArrayList<MangaChapter> collected, Result<ArrayList<MangaChapter>> cb) {
        if (page > 30) {
            CHAPTER_CACHE.put(clean, new ArrayList<>(collected));
            MangaCoroutines.main(() -> cb.onSuccess(collected, false));
            return;
        }
        String url = base() + "/wp-admin/admin-ajax.php?manga_id=" + mangaId + "&page=" + page + "&action=chapter_list";
        Request req = new Request.Builder().url(url).headers(headers("text/html,*/*", mangaUrl(clean))).header("HX-Request", "true").header("HX-Trigger", "chapter-list").header("HX-Target", "chapter-list").header("HX-Current-URL", mangaUrl(clean)).build();
        enqueueText(req, new Result<String>() {
            @Override public void onSuccess(String body, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> parsed = parseChapters(Jsoup.parse(body, mangaUrl(clean)), clean);
                        mergeChapters(collected, parsed);
                        boolean next = hasAdvancedPage(body, page + 1) || hasThePage(body, page + 1);
                        if (next && !parsed.isEmpty()) fetchChapterPage(clean, mangaId, page + 1, collected, cb);
                        else {
                            CHAPTER_CACHE.put(clean, new ArrayList<>(collected));
                            MangaCoroutines.main(() -> cb.onSuccess(collected, false));
                        }
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter Natsu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) {
                if (collected.isEmpty()) cb.onError(message);
                else {
                    CHAPTER_CACHE.put(clean, new ArrayList<>(collected));
                    cb.onSuccess(collected, false);
                }
            }
        });
    }

    private ArrayList<MangaPost> parseList(String body) {
        ArrayList<MangaPost> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Document doc = Jsoup.parse(body, base());
        Elements imageLinks = doc.select("a[href*=\"/manga/\"]:has(img)");
        for (Element imageLink : imageLinks) {
            String href = imageLink.absUrl("href");
            if (!isMangaUrl(href)) continue;
            String slug = extractMangaSlug(href);
            if (slug.isEmpty() || !seen.add(slug)) continue;
            Element card = findCard(imageLink, slug);
            String cover = imageFrom(imageLink);
            if (cover.isEmpty() && card != null) cover = imageFrom(card);
            String title = titleFrom(card, slug);
            if (title.isEmpty()) title = titleFrom(imageLink, slug);
            String latest = "";
            String date = "";
            if (card != null) {
                Element chapter = firstChapterLink(card);
                if (chapter != null) {
                    latest = chapterText(chapter.text());
                    date = dateText(chapter, chapter.text());
                }
            }
            MangaPost post = new MangaPost(slug, title, cover, "", "", "", "", "", latest, date).withSource(MangaSettingsManager.MANGA_SOURCE_NATSU, "Natsu");
            out.add(post);
        }
        if (out.isEmpty()) {
            for (Element link : doc.select("a[href*=\"/manga/\"]")) {
                String href = link.absUrl("href");
                if (!isMangaUrl(href)) continue;
                String slug = extractMangaSlug(href);
                if (slug.isEmpty() || !seen.add(slug)) continue;
                Element card = findCard(link, slug);
                String title = titleFrom(card, slug);
                String cover = card == null ? "" : imageFrom(card);
                out.add(new MangaPost(slug, title, cover, "", "", "", "", "", "", "").withSource(MangaSettingsManager.MANGA_SOURCE_NATSU, "Natsu"));
            }
        }
        return out;
    }

    private MangaPost parseDetail(String slug, Document doc) {
        String title = text(doc.selectFirst("h1"));
        if (title.isEmpty()) title = cleanTitle(meta(doc, "og:title"));
        String cover = meta(doc, "og:image");
        if (cover.isEmpty()) cover = imageFrom(doc.selectFirst("img.wp-post-image"));
        String synopsis = findSynopsis(doc);
        if (synopsis.isEmpty()) synopsis = meta(doc, "description");
        synopsis = cleanSynopsis(synopsis, title);
        ArrayList<String> genres = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select("a[href*=\"/genre/\"]")) {
            String g = text(link);
            if (!g.isEmpty() && seen.add(g.toLowerCase(Locale.ROOT))) genres.add(g);
        }
        String genre = TextUtils.join(", ", genres);
        String type = infoValue(doc, "Type");
        if (type.isEmpty()) type = "Manga";
        String status = infoValue(doc, "Status");
        String latestDate = infoValue(doc, "Last Updates");
        MangaPost post = new MangaPost(slug, title, cover, "", status, synopsis, genre, type, "", latestDate).withSource(MangaSettingsManager.MANGA_SOURCE_NATSU, "Natsu");
        return post;
    }

    private String findSynopsis(Document doc) {
        if (doc == null) return "";
        Element desc = doc.selectFirst("#tabpanel-description [itemprop=description][data-show=false]");
        if (desc == null || text(desc).isEmpty()) desc = doc.selectFirst("#tabpanel-description [itemprop=description] p");
        if (desc == null || text(desc).isEmpty()) desc = doc.selectFirst("#tabpanel-description [itemprop=description]");
        if (desc == null || text(desc).isEmpty()) desc = doc.selectFirst("[itemprop=description][data-show=false]");
        if (desc == null || text(desc).isEmpty()) desc = doc.selectFirst("[itemprop=description]");
        if (desc == null) return "";
        return text(desc);
    }

    private ArrayList<MangaChapter> parseChapters(Document doc, String mangaSlug) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements items = doc.select("[data-chapter-number]");
        if (items.isEmpty()) items = doc.select("a[href*=\"/chapter-\"]");
        for (Element item : items) {
            Element link = item.tagName().equals("a") ? item : item.selectFirst("a[href*=\"/chapter-\"]");
            if (link == null) continue;
            String href = link.absUrl("href");
            if (href.isEmpty()) href = link.attr("href");
            if (href.isEmpty()) continue;
            String number = attr(item, "data-chapter-number");
            float index = parseIndex(firstNonEmpty(number, link.text(), href));
            if (index < 0) continue;
            String key = MangaChapter.formatIndex(index) + "|" + href;
            if (!seen.add(key)) continue;
            String rawTitle = chapterText(link.text());
            String date = "";
            Element time = item.selectFirst("time");
            if (time != null) date = firstNonEmpty(text(time), natsuPrettyDate(time.attr("datetime")));
            if (date.isEmpty()) date = dateText(link, link.text());
            MangaChapter chapter = new MangaChapter(href, index, rawTitle, date);
            chapter.chapterId = extractChapterId(href);
            out.add(chapter);
        }
        return out;
    }

    private ArrayList<String> parsePages(Document doc) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Elements images = doc.select("section[data-image-data=\"1\"] img[src], [data-image-data=\"1\"] img[src]");
        if (images.isEmpty()) images = doc.select("img[src*=cdn], img[src*=wp-content/uploads]");
        for (Element img : images) {
            String src = firstNonEmpty(img.absUrl("src"), img.attr("data-src"), img.attr("data-lazy-src"));
            if (!src.startsWith("http")) continue;
            if (seen.add(src)) out.add(src);
        }
        return out;
    }

    private ArrayList<GenreItem> parseGenres(String body) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String json = extractSearchTermsJson(body);
        if (!json.isEmpty()) {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            appendTerms(out, seen, root, "genre", "", "");
            appendTerms(out, seen, root, "type", "Type: ", "type:");
            appendTerms(out, seen, root, "status", "Status: ", "status:");
            appendTerms(out, seen, root, "series-author", "Author: ", "author:");
            appendTerms(out, seen, root, "artist", "Artist: ", "artist:");
        }
        if (out.isEmpty()) {
            Document doc = Jsoup.parse(body, base());
            for (Element link : doc.select("a[href*=\"/genre/\"]")) {
                String name = text(link);
                String slug = extractLastSegment(link.absUrl("href"));
                if (!name.isEmpty() && !slug.isEmpty() && seen.add(slug)) out.add(new GenreItem(name, slug));
            }
        }
        return out;
    }

    private void appendTerms(ArrayList<GenreItem> out, LinkedHashSet<String> seen, JsonObject root, String key, String labelPrefix, String valuePrefix) {
        if (root == null || !root.has(key) || !root.get(key).isJsonArray()) return;
        JsonArray arr = root.getAsJsonArray(key);
        for (JsonElement element : arr) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String name = jsonString(item, "name");
            String slug = jsonString(item, "slug");
            if (name.isEmpty() || slug.isEmpty()) continue;
            String value = valuePrefix + slug;
            if (seen.add(value)) out.add(new GenreItem(labelPrefix + name, value));
        }
    }

    private void mergeChapters(ArrayList<MangaChapter> target, ArrayList<MangaChapter> source) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (MangaChapter chapter : target) seen.add(MangaChapter.formatIndex(chapter.index) + "|" + chapter.slug);
        for (MangaChapter chapter : source) {
            String key = MangaChapter.formatIndex(chapter.index) + "|" + chapter.slug;
            if (seen.add(key)) target.add(chapter);
        }
    }

    private Element findCard(Element link, String slug) {
        Element current = link;
        for (int i = 0; i < 8 && current != null; i++) {
            boolean hasImage = !current.select("a[href*=\"/manga/\"]:has(img), img").isEmpty();
            boolean hasTitle = false;
            for (Element a : current.select("a[href*=\"/manga/\"]")) if (isSameMangaLink(a, slug) && !text(a).isEmpty()) { hasTitle = true; break; }
            if (hasImage && hasTitle) return current;
            current = current.parent();
        }
        return link.parent();
    }

    private Element firstChapterLink(Element card) {
        if (card == null) return null;
        for (Element link : card.select("a[href*=\"/chapter-\"]")) {
            String text = text(link).toLowerCase(Locale.ROOT);
            if (text.contains("chapter")) return link;
        }
        return card.selectFirst("a[href*=\"/chapter-\"]");
    }

    private String titleFrom(Element root, String slug) {
        if (root == null) return "";
        for (Element h : root.select("h1, h2, h3, h4, h5")) {
            String t = text(h);
            if (!t.isEmpty() && !t.toLowerCase(Locale.ROOT).startsWith("chapter ")) return t;
        }
        for (Element a : root.select("a[href*=\"/manga/\"]")) {
            if (!isSameMangaLink(a, slug)) continue;
            String t = text(a);
            if (!t.isEmpty()) return t;
        }
        Element img = root.selectFirst("img[alt]");
        String alt = img == null ? "" : img.attr("alt").trim();
        return cleanTitle(alt);
    }

    private String imageFrom(Element root) {
        if (root == null) return "";
        Element img = root.tagName().equals("img") ? root : root.selectFirst("img");
        if (img == null) return "";
        String src = firstNonEmpty(img.absUrl("src"), img.absUrl("data-src"), img.absUrl("data-lazy-src"), img.attr("src"), img.attr("data-src"), img.attr("data-lazy-src"));
        if (src.isEmpty()) {
            String srcset = firstNonEmpty(img.attr("srcset"), img.attr("data-srcset"));
            if (!srcset.isEmpty()) src = srcset.split("\\s+")[0].trim();
        }
        return src;
    }

    private String chapterText(String raw) {
        if (raw == null) return "";
        Matcher m = Pattern.compile("(?i)(chapter\\s+[0-9]+(?:\\.[0-9]+)?)").matcher(raw);
        if (m.find()) return m.group(1).trim();
        return raw.trim();
    }

    private String dateText(Element link, String raw) {
        if (link != null) {
            Element parent = link.parent();
            Element time = parent == null ? null : parent.selectFirst("time");
            if (time != null) return firstNonEmpty(text(time), natsuPrettyDate(time.attr("datetime")));
        }
        if (raw == null) return "";
        String text = raw.replaceFirst("(?i)^.*?chapter\\s+[0-9]+(?:\\.[0-9]+)?", "").trim();
        return text.replaceAll("\\s+\\d+\\s+\\d+$", "").trim();
    }

    private FilterSpec parseFilter(String raw) {
        FilterSpec spec = new FilterSpec();
        if (raw == null || raw.trim().isEmpty()) return spec;
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:")) spec.types.add(value.substring(5).trim().toLowerCase(Locale.ROOT));
            else if (lower.startsWith("status:")) spec.statuses.add(value.substring(7).trim().toLowerCase(Locale.ROOT));
            else if (lower.startsWith("author:")) spec.authors.add(value.substring(7).trim().toLowerCase(Locale.ROOT));
            else if (lower.startsWith("artist:")) spec.artists.add(value.substring(7).trim().toLowerCase(Locale.ROOT));
            else spec.genres.add(value);
        }
        return spec;
    }

    private String jsonArray(ArrayList<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return builder.append(']').toString();
    }

    private boolean hasAdvancedPage(String body, int page) {
        if (body == null || page < 2) return false;
        String p = String.valueOf(page);
        return body.contains("'page', '" + p + "'") || body.contains("\"page\", \"" + p + "\"") || body.contains("data-page=\"" + p + "\"") || body.contains(">" + p + "</button>");
    }

    private boolean hasThePage(String body, int page) {
        if (body == null || page < 2) return false;
        String p = String.valueOf(page);
        return body.contains("the_page=" + p) || body.contains("?page=" + p) || body.contains("/page/" + p + "/");
    }

    private void getText(String url, Result<String> cb) {
        Request req = new Request.Builder().url(url).get().headers(headers("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", url)).build();
        enqueueText(req, cb);
    }

    private void enqueueText(Request req, Result<String> cb) {
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MAIN.post(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MAIN.post(() -> cb.onError("HTTP " + response.code())); return; }
                MAIN.post(() -> cb.onSuccess(body, false));
            }
        });
    }

    private okhttp3.Headers headers(String accept, String referer) {
        return new okhttp3.Headers.Builder()
                .set("Referer", referer == null || referer.trim().isEmpty() ? base() + "/" : referer)
                .set("Origin", base())
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36")
                .set("Accept", accept == null || accept.trim().isEmpty() ? "*/*" : accept)
                .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .build();
    }

    private String mangaUrl(String slug) { return base() + "/manga/" + cleanMangaSlug(slug) + "/"; }

    private boolean isMangaUrl(String url) { return url != null && url.contains("/manga/") && !url.contains("/chapter-"); }

    private boolean isSameMangaLink(Element a, String slug) { return a != null && extractMangaSlug(a.absUrl("href")).equals(slug); }

    private String cleanMangaSlug(String slug) {
        if (slug == null) return "";
        String value = slug.trim();
        if (value.startsWith("http")) return extractMangaSlug(value);
        value = value.replaceFirst("^/", "").replaceFirst("/$", "");
        if (value.startsWith("manga/")) value = value.substring(6);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        return value;
    }

    private String cleanChapterSlug(String slug) {
        if (slug == null) return "";
        String value = slug.trim();
        if (value.startsWith("http")) {
            int idx = value.indexOf("/chapter-");
            if (idx >= 0) value = value.substring(idx + 1);
        }
        value = value.replaceFirst("^/", "").replaceFirst("/$", "");
        return value;
    }

    private String extractMangaSlug(String url) {
        if (url == null) return "";
        Matcher m = Pattern.compile("/manga/([^/]+)/?").matcher(url);
        return m.find() ? m.group(1).trim() : "";
    }

    private String extractChapterId(String url) {
        if (url == null) return "";
        Matcher m = Pattern.compile("\\.([0-9]+)(?:/)?$").matcher(url.trim());
        return m.find() ? m.group(1) : "";
    }

    private String extractMangaId(String body) {
        String value = firstMatch(body, "manga_id=([0-9]+)");
        if (value.isEmpty()) value = firstMatch(body, "(?i)mangaId\\s*=\\s*([0-9]+)");
        if (value.isEmpty()) value = firstMatch(body, "(?i)manga_id['\"]?\\s*[:=]\\s*['\"]?([0-9]+)");
        return value;
    }

    private float parseIndex(String raw) {
        if (raw == null) return -1f;
        Matcher m = Pattern.compile("(?i)chapter[^0-9]*([0-9]+(?:\\.[0-9]+)?)").matcher(raw);
        if (m.find()) {
            try { return Float.parseFloat(m.group(1)); } catch(Exception ignored) { }
        }
        m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(raw);
        if (m.find()) {
            try { return Float.parseFloat(m.group(1)); } catch(Exception ignored) { }
        }
        return -1f;
    }

    private String extractSearchTermsJson(String body) {
        if (body == null) return "";
        int marker = body.indexOf("var searchTerms");
        if (marker < 0) marker = body.indexOf("searchTerms =");
        if (marker < 0) return "";
        int start = body.indexOf('{', marker);
        if (start < 0) return "";
        int depth = 0;
        boolean quote = false;
        boolean escape = false;
        for (int i = start; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (escape) { escape = false; continue; }
            if (ch == '\\') { escape = true; continue; }
            if (ch == '"') { quote = !quote; continue; }
            if (quote) continue;
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return body.substring(start, i + 1);
            }
        }
        return "";
    }

    private String infoValue(Document doc, String label) {
        String wanted = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return "";
        for (Element element : doc.select("div, li, p, span")) {
            String text = text(element);
            String lower = text.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(wanted)) continue;
            String value = text.substring(Math.min(text.length(), label.length())).replaceFirst("^\\s*[:：-]?\\s*", "").trim();
            if (!value.isEmpty() && value.length() <= 80) return value;
        }
        return "";
    }

    private String cleanSynopsis(String synopsis, String title) {
        if (synopsis == null) return "";
        String result = synopsis.replaceAll("\\s+", " ").trim();
        if (title != null && !title.trim().isEmpty()) result = result.replace("Baca " + title + " Bahasa Indonesia di Natsu", "").trim();
        return result;
    }

    private String cleanTitle(String title) {
        if (title == null) return "";
        return title.replace(" Bahasa Indonesia", "").replace(" - Natsu", "").replace(" – Natsu", "").trim();
    }

    private String meta(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "], meta[name=" + property + "]");
        return el == null ? "" : el.attr("content").trim();
    }

    private String attr(Element el, String name) { return el == null ? "" : el.attr(name).trim(); }

    private String text(Element el) { return el == null ? "" : el.text().replaceAll("\\s+", " ").trim(); }

    private String firstMatch(String body, String regex) {
        if (body == null) return "";
        Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1).trim() : "";
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private String extractLastSegment(String url) {
        if (url == null) return "";
        String value = url.trim().replaceFirst("/$", "");
        int idx = value.lastIndexOf('/');
        return idx >= 0 ? value.substring(idx + 1) : value;
    }

    private String jsonString(JsonObject obj, String key) {
        try { return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString().trim() : ""; } catch(Exception e) { return ""; }
    }

    private String natsuPrettyDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String value = raw.trim();
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}.*")) return value;
        try {
            String normalized = value.replace("Z", "+00:00");
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            return new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")).format(in.parse(normalized));
        } catch(Exception ignored) { }
        return value.length() > 10 ? value.substring(0, 10) : value;
    }

    private static class FilterSpec {
        final ArrayList<String> genres = new ArrayList<>();
        final ArrayList<String> types = new ArrayList<>();
        final ArrayList<String> statuses = new ArrayList<>();
        final ArrayList<String> authors = new ArrayList<>();
        final ArrayList<String> artists = new ArrayList<>();
        boolean hasFilter() { return !genres.isEmpty() || !types.isEmpty() || !statuses.isEmpty() || !authors.isEmpty() || !artists.isEmpty(); }
        String key() { return genres.toString() + types.toString() + statuses.toString() + authors.toString() + artists.toString(); }
    }
}
