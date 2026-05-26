package miku.moe.app;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class DoujinDesu extends KomikcastClient {
    private static final String DEFAULT_BASE = "https://doujindesu.tv";
    protected static String base() { return MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU); }
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final String SEC_V_SESSION_COOKIE = "sec_v_session=verified_human_0000000000000";
    private static final OkHttpClient CLIENT = CloudflareHelper.apply(new OkHttpClient.Builder()).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true).addNetworkInterceptor(chain -> {
        Request request = chain.request();
        String host = request.url().host();
        if (host != null && host.endsWith("doujindesu.tv")) {
            String cookie = request.header("Cookie");
            if (cookie == null || !cookie.contains("sec_v_session=")) {
                String value = cookie == null || cookie.trim().isEmpty() ? SEC_V_SESSION_COOKIE : cookie + "; " + SEC_V_SESSION_COOKIE;
                request = request.newBuilder().header("Cookie", value).build();
            }
        }
        return chain.proceed(request);
    }).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(24, CACHE_TTL);
    private static final ArrayList<GenreItem> GENRE_CACHE = new ArrayList<>();
    private static final MangaMemoryCache<String, String> CHAPTER_URL_CACHE = new MangaMemoryCache<>(400, CACHE_TTL);
    private final OkHttpClient client = CLIENT;
    private final Handler main = MAIN;
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    @Override protected String sourceLabel() { return "DoujinDesu"; }

    @Override public void list(int page, String sort, String query, Result<ArrayList<MangaPost>> cb) { list(page, sort, query, "", cb); }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        try {
            String url;
            if (query != null && !query.trim().isEmpty()) {
                String prefix = page <= 1 ? base() + "/" : base() + "/page/" + page + "/";
                url = prefix + "?s=" + URLEncoder.encode(query.trim(), "UTF-8");
            } else if (genre != null && !genre.trim().isEmpty()) {
                String g = firstFilterPart(genre).toLowerCase(Locale.ROOT).replace(" ", "-");
                if (g.startsWith("type:")) {
                    String type = g.substring(5).trim();
                    if ("manga".equals(type)) url = base() + "/manga/" + (page <= 1 ? "" : "page/" + page + "/") + "?type=Manga";
                    else if ("manhwa".equals(type)) url = base() + "/manga/" + (page <= 1 ? "" : "page/" + page + "/") + "?type=Manhwa";
                    else url = base() + "/doujin/page/" + page + "/";
                } else {
                    if (g.startsWith("genre/")) g = g.substring("genre/".length());
                    url = base() + "/genre/" + URLEncoder.encode(g, "UTF-8") + (page <= 1 ? "/" : "/page/" + page + "/");
                }
            } else {
                String mode = sort == null ? "latest" : sort.trim().toLowerCase(Locale.ROOT);
                if (mode.equals("manga")) {
                    url = base() + "/manga/" + (page <= 1 ? "" : "page/" + page + "/") + "?type=Manga";
                } else if (mode.equals("manhwa")) {
                    url = base() + "/manga/" + (page <= 1 ? "" : "page/" + page + "/") + "?type=Manhwa";
                } else {
                    String path = mode.equals("popular") || mode.equals("popularity") ? "manhwa" : "doujin";
                    url = base() + "/" + path + "/page/" + page + "/";
                }
            }
            String forcedType = url.contains("type=Manga") ? "Manga" : (url.contains("type=Manhwa") ? "Manhwa" : (url.contains("/doujin/") ? "Doujinshi" : (url.contains("/manhwa/") ? "Manhwa" : "")));
            getHtml(url, new Result<Document>() {
                @Override public void onSuccess(Document document, boolean ignored) {
                    MangaCoroutines.io(() -> {
                        try {
                            ArrayList<MangaPost> out = new ArrayList<>();
                            LinkedHashSet<String> seen = new LinkedHashSet<>();
                            Elements articles = document.select("#archives div.entries article, div.entries article, article");
                            for (Element article : articles) {
                                MangaPost post = parseListItem(article, forcedType);
                                if (post == null) continue;
                                String key = !post.slug.isEmpty() ? post.slug : post.title;
                                if (!key.isEmpty() && seen.add(key)) out.add(post);
                            }
                            boolean next = document.selectFirst("nav.pagination li.last a, .pagination a.next, a.next") != null;
                            MangaCoroutines.main(() -> cb.onSuccess(out, next));
                        } catch (Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar DoujinDesu gagal dibaca")); }
                    });
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
        } catch (Exception e) { cb.onError(CloudflareHelper.errorMessage(e)); }
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        if (!GENRE_CACHE.isEmpty()) { cb.onSuccess(new ArrayList<>(GENRE_CACHE), false); return; }
        getHtml(base() + "/genre/", new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<GenreItem> out = parseGenreDocument(document);
                        if (out.isEmpty()) out = fallbackGenres();
                        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(out); }
                        final ArrayList<GenreItem> result = out;
                        MangaCoroutines.main(() -> cb.onSuccess(result, false));
                    } catch (Exception e) {
                        ArrayList<GenreItem> fallback = fallbackGenres();
                        synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(fallback); }
                        MangaCoroutines.main(() -> cb.onSuccess(fallback, false));
                    }
                });
            }
            @Override public void onError(String message) {
                ArrayList<GenreItem> fallback = fallbackGenres();
                synchronized (GENRE_CACHE) { GENRE_CACHE.clear(); GENRE_CACHE.addAll(fallback); }
                cb.onSuccess(fallback, false);
            }
        });
    }

    @Override public void enrichLatest(ArrayList<MangaPost> list, Runnable done) {
        if (list == null || list.isEmpty()) { if (done != null) MangaCoroutines.main(done); return; }
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel()) { if (done != null) MangaCoroutines.main(done); return; }
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(0);
        for (MangaPost p : list) if (p != null && p.slug != null && !p.slug.isEmpty()) remaining.incrementAndGet();
        if (remaining.get() == 0) { if (done != null) MangaCoroutines.main(done); return; }
        for (MangaPost p : list) {
            if (p == null || p.slug == null || p.slug.isEmpty()) continue;
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
                @Override public void onError(String message) { if (remaining.decrementAndGet() <= 0 && done != null) done.run(); }
            });
        }
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        MangaPost cached = DETAIL_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(cached, false); return; }
        getHtml(toMangaUrl(slug), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        MangaPost post = parseDetail(slug, document);
                        DETAIL_CACHE.put(slug, post);
                        MangaCoroutines.main(() -> cb.onSuccess(post, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Detail DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(slug);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        getHtml(toMangaUrl(slug), new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                MangaCoroutines.io(() -> {
                    try {
                        ArrayList<MangaChapter> out = new ArrayList<>();
                        LinkedHashSet<String> seen = new LinkedHashSet<>();
                        Elements rows = document.select("#chapter_list li, ul#chapter_list li, .chapter_list li");
                        for (Element row : rows) {
                            Element a = row.selectFirst("div.epsleft span.lchx a, span.lchx a, a[href]");
                            if (a == null) continue;
                            String href = absoluteUrl(firstNonEmpty(a.attr("abs:href"), a.attr("href")));
                            String eps = text(row.selectFirst("div.epsright chapter, .epsright chapter, .epsright"));
                            if (eps.isEmpty()) eps = a.text();
                            float idx = numberFrom(eps);
                            if (idx < 0) idx = numberFrom(href);
                            if (idx < 0) continue;
                            String key = MangaChapter.formatIndex(idx);
                            if (!seen.add(key)) continue;
                            String date = text(row.selectFirst("div.epsleft span.date, span.date, time"));
                            CHAPTER_URL_CACHE.put(chapterKey(slug, idx), href);
                            out.add(newDoujinChapter(slug, idx, a.text(), eps, date));
                        }
                        CHAPTER_CACHE.put(slug, new ArrayList<>(out));
                        MangaCoroutines.main(() -> cb.onSuccess(out, false));
                    } catch(Exception e) { MangaCoroutines.main(() -> cb.onError("Daftar chapter DoujinDesu gagal dibaca")); }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String key = chapterKey(slug, index);
        ArrayList<String> cached = PAGE_CACHE.get(key);
        if (cached != null) { cb.onSuccess(new ArrayList<>(cached), false); return; }
        String chapterUrl = CHAPTER_URL_CACHE.get(key);
        if (chapterUrl == null || chapterUrl.trim().isEmpty()) {
            chapters(slug, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) { loadPages(slug, index, cb); }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        loadPages(slug, index, cb);
    }

    private void loadPages(String slug, float index, Result<ArrayList<String>> cb) {
        String key = chapterKey(slug, index);
        String chapterUrl = CHAPTER_URL_CACHE.get(key);
        if (chapterUrl == null || chapterUrl.trim().isEmpty()) {
            chapters(slug, new Result<ArrayList<MangaChapter>>() {
                @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                    String resolved = CHAPTER_URL_CACHE.get(key);
                    if (resolved == null || resolved.trim().isEmpty()) { cb.onError("URL chapter DoujinDesu tidak ditemukan"); return; }
                    loadPagesFromUrl(key, resolved, cb);
                }
                @Override public void onError(String message) { cb.onError(message); }
            });
            return;
        }
        loadPagesFromUrl(key, chapterUrl, cb);
    }

    private void loadPagesFromUrl(String key, String chapterUrl, Result<ArrayList<String>> cb) {
        getHtml(chapterUrl, new Result<Document>() {
            @Override public void onSuccess(Document document, boolean ignored) {
                String readerId = readerIdFromDocument(document);
                if (readerId.isEmpty()) { parseImagesFromDocument(key, document, cb); return; }
                RequestBody body = new FormBody.Builder().add("id", readerId).build();
                Request req = new Request.Builder().url(base() + "/themes/ajax/ch.php").post(body).headers(ajaxHeaders(chapterUrl)).build();
                CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
                    @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> parseImagesFromDocument(key, document, cb)); }
                    @Override public void onResponse(Call call, Response response) throws IOException {
                        String html = response.body() != null ? response.body().string() : "";
                        if (!response.isSuccessful() || html.trim().isEmpty()) {
                            MangaCoroutines.main(() -> parseImagesFromDocument(key, document, cb));
                            return;
                        }
                        Document doc = Jsoup.parse(html, base());
                        MangaCoroutines.main(() -> parseImagesFromDocument(key, doc, cb));
                    }
                });
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private void parseImagesFromDocument(String key, Document doc, Result<ArrayList<String>> cb) {
        MangaCoroutines.io(() -> {
            ArrayList<String> out = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            String html = doc == null ? "" : doc.html();
            if (doc != null) {
                Elements images = doc.select("img[src*='desu.photos/storage/uploads'], img[data-src*='desu.photos/storage/uploads'], img[data-lazy-src*='desu.photos/storage/uploads'], img[data-original*='desu.photos/storage/uploads'], img[srcset*='desu.photos/storage/uploads']");
                if (images.isEmpty()) {
                    Element reader = doc.selectFirst("#anu, #reader #anu, #reader .main, main#reader .main, .reader, .chapter-content, .entry-content");
                    if (reader != null) images = reader.select("img[src], img[data-src], img[data-lazy-src], img[data-original], img[srcset]");
                }
                for (Element img : images) {
                    String url = imageFromElement(img);
                    if (isReaderImage(url) && seen.add(url)) out.add(url);
                }
            }
            Matcher matcher = Pattern.compile("https?://[^\\\"<>]*desu\\.photos/storage/uploads/[^\\\"<>]+?\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\\"<>\\s]*)?", Pattern.CASE_INSENSITIVE).matcher(html);
            while (matcher.find()) {
                String url = cleanImageUrl(matcher.group());
                if (isReaderImage(url) && seen.add(url)) out.add(url);
            }
            PAGE_CACHE.put(key, new ArrayList<>(out));
            MangaCoroutines.main(() -> cb.onSuccess(out, false));
        });
    }

    private static ArrayList<GenreItem> parseGenreDocument(Document document) {
        ArrayList<GenreItem> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (document == null) return out;
        Elements links = document.select("section#taxonomy.feed.tags div.entries div.entry a[href*=/genre/], #taxonomy .entries .entry a[href*=/genre/]");
        if (links.isEmpty()) links = document.select("a[href*=/genre/]");
        for (Element a : links) {
            String href = firstNonEmpty(a.attr("abs:href"), a.attr("href"));
            String slug = genreSlugFromHref(href);
            if (slug.isEmpty() || slug.equalsIgnoreCase("genre") || !seen.add(slug)) continue;
            String name = cleanGenreName(firstNonEmpty(text(a.selectFirst("span.name")), a.attr("title"), a.ownText(), a.text()));
            if (name.isEmpty() || name.equalsIgnoreCase("Genres") || name.equalsIgnoreCase("Home")) continue;
            out.add(new GenreItem(name, slug));
        }
        return out;
    }

    private static ArrayList<GenreItem> fallbackGenres() {
        String[][] raw = new String[][]{
                {"Age Progression","age-progression"},{"Age Regression","age-regression"},{"Ahegao","ahegao"},{"All The Way Through","all-the-way-through"},{"Amputee","amputee"},{"Anal","anal"},{"Apron","apron"},{"Artist CG","artist-cg"},{"Aunt","aunt"},{"Bestiality","bestiality"},
                {"Big Ass","big-ass"},{"Big Breast","big-breast"},{"Big Penis","big-penis"},{"Bikini","bikini"},{"Bisexual","bisexual"},{"Blackmail","blackmail"},{"Blindfold","blindfold"},{"Blowjob","blowjob"},{"Body Swap","body-swap"},{"Bondage","bondage"},
                {"Cheating","cheating"},{"Collar","collar"},{"Condom","condom"},{"Cousin","cousin"},{"Crossdressing","crossdressing"},{"Cunnilingus","cunnilingus"},{"Dark Skin","dark-skin"},{"Daughter","daughter"},{"Demon","demon"},{"Demon Girl","demon-girl"},
                {"DILF","dilf"},{"Double Penetration","double-penetration"},{"Drugs","drugs"},{"Elf","elf"},{"Exhibitionism","exhibitionism"},{"Females Only","females-only"},{"Femdom","femdom"},{"Filming","filming"},{"Fingering","fingering"},{"Footjob","footjob"},
                {"Full Color","full-color"},{"Furry","furry"},{"Futanari","futanari"},{"Gender Bender","gender-bender"},{"Glasses","glasses"},{"Group","group"},{"Guro","guro"},{"Gyaru","gyaru"},{"Hairy","hairy"},{"Handjob","handjob"},
                {"Harem","harem"},{"Huge Breast","huge-breast"},{"Humiliation","humiliation"},{"Impregnation","impregnation"},{"Incest","incest"},{"Lactation","lactation"},{"Lingerie","lingerie"},{"Lolicon","lolicon"},{"Maid","maid"},{"Masturbation","masturbation"},
                {"MILF","milf"},{"Mind Break","mind-break"},{"Mind Control","mind-control"},{"Monster Girl","monster-girl"},{"Mother","mother"},{"Netorare","netorare"},{"Netori","netori"},{"Nurse","nurse"},{"Paizuri","paizuri"},{"Pregnant","pregnant"},
                {"Rape","rape"},{"Schoolgirl Uniform","schoolgirl-uniform"},{"Shotacon","shotacon"},{"Sister","sister"},{"Socks","socks"},{"Stockings","stockings"},{"Teacher","teacher"},{"Tentacles","tentacles"},{"Toys","toys"},{"Trap","trap"},
                {"Twintails","twintails"},{"Ugly Bastard","ugly-bastard"},{"Vanilla","vanilla"},{"Virginity","virginity"},{"Yandere","yandere"},{"Yuri","yuri"}
        };
        ArrayList<GenreItem> out = new ArrayList<>();
        for (String[] row : raw) out.add(new GenreItem(row[0], row[1]));
        return out;
    }

    private static String firstFilterPart(String raw) {
        if (raw == null) return "";
        String[] parts = raw.split("\\|");
        for (String part : parts) if (part != null && !part.trim().isEmpty()) return part.trim();
        return "";
    }

    private MangaPost parseListItem(Element e, String forcedType) {
        Element a = e.selectFirst("a[href]");
        if (a == null) return null;
        String title = text(e.selectFirst("h3.title, .title, h3"));
        if (title.isEmpty()) title = a.attr("title");
        if (title.isEmpty()) title = a.text();
        String slug = slugFromUrl(a.attr("abs:href"));
        String cover = "";
        Element img = e.selectFirst("a figure.thumbnail img, figure.thumbnail img, img");
        if (img != null) cover = imageFromElement(img);
        String type = firstNonEmpty(labelFromElement(e), forcedType, labelFromSlug(slug));
        String latest = text(e.selectFirst(".chapter, .eps, .lchx, span:contains(Chapter)"));
        if (!latest.isEmpty() && !latest.toLowerCase(Locale.ROOT).contains("chapter")) latest = "Chapter " + latest;
        return new MangaPost(slug, title, cover, "", "", "", "", type, latest, "").withSource(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU, "DoujinDesu");
    }

    private MangaPost parseDetail(String slug, Document doc) {
        Element info = doc.selectFirst("section.metadata");
        String title = text(doc.selectFirst("section.metadata h1.title, h1.title, h1.entry-title, .entry-title, .post-title"));
        String alt = text(doc.selectFirst("h1.title span.alter"));
        if (!alt.isEmpty()) title = title.replace(alt, "").trim();
        title = cleanDetailTitle(firstNonEmpty(title, meta(info, "Title"), attr(doc.selectFirst("meta[property=og:title]"), "content"), attr(doc.selectFirst("meta[name=twitter:title]"), "content"), titleFromSlug(slug)));
        if (title.isEmpty()) title = titleFromSlug(slug);
        String cover = "";
        Element img = doc.selectFirst("figure.thumbnail img, .thumbnail img, img.wp-post-image, meta[property=og:image], meta[itemprop=image]");
        if (img != null) cover = img.tagName().equalsIgnoreCase("meta") ? cleanImageUrl(attr(img, "content")) : imageFromElement(img);
        String status = meta(info, "Status");
        String type = firstNonEmpty(meta(info, "Type"), meta(info, "Category"), labelFromDocument(doc), labelFromSlug(slug));
        String series = meta(info, "Series");
        String author = meta(info, "Author");
        String group = meta(info, "Group");
        String rating = firstNonEmpty(meta(info, "Rating"), text(info == null ? null : info.selectFirst(".rating-prc")));
        String created = firstNonEmpty(meta(info, "Created Date"), text(info == null ? null : info.selectFirst("tr.created td:last-child")));
        String genre = info == null ? "" : cleanCommaList(info.select("div.tags a, .tags a").eachText().toString());
        String synopsis = "";
        Element pb = info == null ? null : info.selectFirst("div.pb-2, .summary, .entry-content, .synopsis, .desc");
        if (pb != null) synopsis = pb.text();
        if (synopsis.isEmpty()) synopsis = firstNonEmpty(attr(doc.selectFirst("meta[name=description]"), "content"), text(doc.selectFirst(".entry-content, .synopsis, .desc")));
        MangaPost post = new MangaPost(slug, title, cover, firstNonEmpty(author, group), status, synopsis, genre, firstNonEmpty(type, MangaPost.normalizeType("", genre, status)), "", "").withSource(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU, "DoujinDesu");
        post.info = buildInfo(status, type, series, author, group, rating, created);
        return post;
    }

    private void getHtml(String url, Result<Document> cb) {
        Request req = new Request.Builder().url(url).headers(headers()).build();
        CloudflareHelper.enqueue(client, req, sourceLabel(), new Callback() {
            @Override public void onFailure(Call call, IOException e) { MangaCoroutines.main(() -> cb.onError(CloudflareHelper.errorMessage(e))); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) { MangaCoroutines.main(() -> cb.onError("HTTP " + response.code() + " DoujinDesu (" + base() + ")")); return; }
                Document doc = Jsoup.parse(body, url);
                MangaCoroutines.main(() -> cb.onSuccess(doc, false));
            }
        });
    }

    private okhttp3.Headers headers() {
        return new okhttp3.Headers.Builder()
                .add("Referer", base() + "/")
                .add("Origin", base())
                .add("X-Requested-With", "XMLHttpRequest")
                .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .add("Cache-Control", "no-cache")
                .add("Pragma", "no-cache")
                .add("Sec-Fetch-Dest", "document")
                .add("Sec-Fetch-Mode", "navigate")
                .add("Sec-Fetch-Site", "same-origin")
                .add("Upgrade-Insecure-Requests", "1")
                .build();
    }

    private okhttp3.Headers ajaxHeaders(String referer) {
        return new okhttp3.Headers.Builder()
                .add("Referer", referer == null || referer.trim().isEmpty() ? base() + "/" : referer)
                .add("Origin", base())
                .add("X-Requested-With", "XMLHttpRequest")
                .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36")
                .add("Accept", "*/*")
                .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .add("Sec-Fetch-Dest", "empty")
                .add("Sec-Fetch-Mode", "cors")
                .add("Sec-Fetch-Site", "same-origin")
                .build();
    }

    private static String toMangaUrl(String slug) {
        if (slug == null) return base() + "/manga/";
        if (slug.startsWith("http")) return slug.replace("http://doujindesu.tv", base()).replace("https://doujindesu.tv", base()).replace("https://www.doujindesu.tv", base());
        String clean = trimSlash(slug);
        if (clean.startsWith("manga/")) return base() + "/" + clean + "/";
        return base() + "/manga/" + clean + "/";
    }
    private static String slugFromUrl(String url) {
        if (url == null) return "";
        return trimSlash(url.replace("http://doujindesu.tv", base()).replace("https://doujindesu.tv", base()).replace("https://www.doujindesu.tv", base()).replace(base(), "").split("\\?")[0]);
    }
    private static String trimSlash(String s) { if (s == null) return ""; while (s.startsWith("/")) s = s.substring(1); while (s.endsWith("/")) s = s.substring(0, s.length()-1); return s; }
    private static String absoluteUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (u.startsWith("http")) return u.replace("http://doujindesu.tv", base()).replace("https://doujindesu.tv", base()).replace("https://www.doujindesu.tv", base());
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("/")) return base() + u;
        return base() + "/" + u;
    }
    private static String imageFromElement(Element img) {
        if (img == null) return "";
        String u = "";
        if (img.hasAttr("data-src")) u = attrUrl(img, "data-src");
        if (u.isEmpty() && img.hasAttr("data-lazy-src")) u = attrUrl(img, "data-lazy-src");
        if (u.isEmpty() && img.hasAttr("data-original")) u = attrUrl(img, "data-original");
        if (u.isEmpty() && img.hasAttr("srcset")) u = srcsetUrl(img);
        if (u.isEmpty() && img.hasAttr("src")) u = attrUrl(img, "src");
        return cleanImageUrl(u);
    }
    private static String attrUrl(Element img, String attr) {
        if (img == null || attr == null || attr.isEmpty()) return "";
        String abs = img.attr("abs:" + attr);
        if (abs != null && !abs.trim().isEmpty()) return abs;
        String raw = img.attr(attr);
        if (raw == null) return "";
        raw = raw.trim();
        if (raw.startsWith("//")) return "https:" + raw;
        return raw;
    }
    private static String srcsetUrl(Element img) {
        String raw = attrUrl(img, "srcset");
        if (raw.isEmpty()) return "";
        String[] candidates = raw.split(",");
        String first = candidates.length == 0 ? raw : candidates[0];
        int webp = first.toLowerCase(Locale.ROOT).indexOf(".webp");
        if (webp >= 0) return first.substring(0, webp + 5);
        int jpg = first.toLowerCase(Locale.ROOT).indexOf(".jpg");
        if (jpg >= 0) return first.substring(0, jpg + 4);
        int jpeg = first.toLowerCase(Locale.ROOT).indexOf(".jpeg");
        if (jpeg >= 0) return first.substring(0, jpeg + 5);
        int png = first.toLowerCase(Locale.ROOT).indexOf(".png");
        if (png >= 0) return first.substring(0, png + 4);
        return first.trim().split("\\s+")[0];
    }
    private static String cleanImageUrl(String url) {
        if (url == null) return "";
        return url.replace("\\/", "/").replace(" ", "%20").trim();
    }
    private static boolean isReaderImage(String url) {
        if (url == null) return false;
        String u = url.replace("\\/", "/").trim().toLowerCase(Locale.ROOT);
        if (!u.startsWith("http")) return false;
        if (!(u.contains(".jpg") || u.contains(".jpeg") || u.contains(".png") || u.contains(".webp"))) return false;
        if (u.contains(".gif") || u.contains("/ads/") || u.contains("banner") || u.contains("logo") || u.contains("avatar") || u.contains("button") || u.contains("sponsor")) return false;
        return u.contains("desu.photos/storage/uploads/");
    }
    private static String text(Element e) { return e == null ? "" : e.text().trim(); }
    private static String firstNonEmpty(String... values) { for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim(); return ""; }
    private static float numberFrom(String s) { if (s == null) return -1f; Matcher m = NUMBER.matcher(s); if (m.find()) try { return Float.parseFloat(m.group(1)); } catch(Exception ignored) {} return -1f; }
    private static String chapterKey(String slug, float index) { return mangaKey(slug) + ":" + MangaChapter.formatIndex(index); }
    private static String mangaKey(String slug) {
        if (slug == null) return "";
        String s = slug.trim();
        if (s.startsWith("http")) s = slugFromUrl(s);
        s = trimSlash(s);
        if (s.startsWith("manga/")) s = s.substring("manga/".length());
        return s;
    }
    private static String readerIdFromDocument(Document doc) {
        if (doc == null) return "";
        Element reader = doc.selectFirst("#reader[data-id], main#reader[data-id]");
        String id = reader == null ? "" : reader.attr("data-id").trim();
        if (!id.isEmpty()) return id;
        Matcher matcher = Pattern.compile("load_data\\s*\\(\\s*['\"]?(\\d+)['\"]?\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(doc.html());
        return matcher.find() ? matcher.group(1) : "";
    }
    private static MangaChapter newDoujinChapter(String slug, float index, String rawTitle, String rawEpisode, String date) {
        MangaChapter chapter = new MangaChapter(slug, index, "", date);
        chapter.title = formatDoujinChapterTitle(index, firstNonEmpty(rawTitle, rawEpisode));
        return chapter;
    }
    private static String formatDoujinChapterTitle(float index, String raw) {
        String idx = MangaChapter.formatIndex(index);
        String base = "Chapter " + idx;
        String t = raw == null ? "" : raw.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return base;
        boolean hasEnd = t.toLowerCase(Locale.ROOT).matches(".*\\bend\\b.*");
        t = t.replaceFirst("(?i)^chapter\\s*" + Pattern.quote(idx) + "\\b", "").trim();
        t = t.replaceFirst("^[:\\-–—\\s]+", "").trim();
        t = t.replaceFirst("(?i)^chapter\\s*", "").trim();
        t = t.replaceFirst("^[:\\-–—\\s]+", "").trim();
        if (t.equals(idx) || t.matches("^" + Pattern.quote(idx) + "(?:\\.0+)?$") || t.isEmpty()) return hasEnd ? base + " End" : base;
        if (t.equalsIgnoreCase("end")) return base + " End";
        t = t.replaceFirst("^" + Pattern.quote(idx) + "\\s*[:\\-–—]?\\s*", "").trim();
        if (t.isEmpty()) return hasEnd ? base + " End" : base;
        if (t.equalsIgnoreCase("end")) return base + " End";
        return base + " " + t;
    }
    private static String cleanChapterTitle(String title, String idx) { String t = title == null ? "" : title.trim(); return t.replaceFirst("(?i)^chapter\\s*" + Pattern.quote(idx), "").replaceFirst("^[:\\-–\\s]+", "").trim(); }
    private static String meta(Element info, String label) { if (info == null) return ""; Element td = info.selectFirst("td:matchesOwn((?i)^\\s*" + Pattern.quote(label) + "\\s*$) ~ td"); return text(td); }
    private static String genreSlugFromHref(String href) {
        if (href == null) return "";
        String path = trimSlash(href.replace("http://doujindesu.tv", base()).replace("https://doujindesu.tv", base()).replace("https://www.doujindesu.tv", base()).replace(base(), "").split("\\?")[0]);
        if (path.equals("genre")) return "";
        if (path.startsWith("genre/")) path = path.substring("genre/".length());
        int slash = path.indexOf('/');
        if (slash >= 0) path = path.substring(0, slash);
        return path.trim();
    }

    private static String cleanGenreName(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").replaceAll("\\s+\\d+\\s*$", "").trim();
    }

    private static String labelFromSlug(String slug) { String s = slug == null ? "" : slug.toLowerCase(Locale.ROOT); if (s.contains("doujin")) return "Doujinshi"; if (s.contains("manhwa")) return "Manhwa"; if (s.contains("manga")) return "Manga"; return ""; }
    private static String labelFromElement(Element e) { if (e == null) return ""; String text = e.select("a[href*=/doujin/], a[href*=/manhwa/], a[href*=/manga/], .type, .category").text(); return directType(text); }
    private static String labelFromDocument(Document doc) { return doc == null ? "" : directType(doc.select("section.metadata a[href*=/doujin/], section.metadata a[href*=/manhwa/], section.metadata a[href*=/manga/], section.metadata").text()); }
    private static String directType(String raw) { String t = raw == null ? "" : raw.toLowerCase(Locale.ROOT); if (t.contains("doujinshi") || t.contains("doujin")) return "Doujinshi"; if (t.contains("manhwa")) return "Manhwa"; if (t.contains("manhua")) return "Manhua"; if (t.contains("manga")) return "Manga"; return ""; }
    private static String attr(Element e, String name) { return e == null || name == null ? "" : e.attr(name).trim(); }
    private static String buildInfo(String status, String type, String series, String author, String group, String rating, String created) {
        StringBuilder out = new StringBuilder();
        appendInfo(out, "Status", status);
        appendInfo(out, "Type", type);
        appendInfo(out, "Series", series);
        appendInfo(out, "Author", author);
        appendInfo(out, "Group", group);
        appendInfo(out, "Rating", rating);
        appendInfo(out, "Created Date", created);
        return out.toString();
    }
    private static void appendInfo(StringBuilder out, String label, String value) {
        if (value == null) return;
        String clean = value.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return;
        if (out.length() > 0) out.append("||");
        out.append(label).append(": ").append(clean);
    }
    private static String cleanCommaList(String raw) {
        if (raw == null) return "";
        return raw.replace("[", "").replace("]", "").replace("  ", " ").replace(", ", ",").replace(",", ", ").trim();
    }
    private static String cleanDetailTitle(String raw) {
        String title = raw == null ? "" : raw.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        title = title.replaceFirst("(?i)\\s*[-|]\\s*DoujinDesu.*$", "").trim();
        title = title.replaceFirst("(?i)^Manga\\s*[:\\-]\\s*", "").trim();
        if (title.equalsIgnoreCase("manga") || title.equalsIgnoreCase("manhwa") || title.equalsIgnoreCase("doujin")) return "";
        return title;
    }
    private static String titleFromSlug(String slug) {
        String s = mangaKey(slug).replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String part : s.split(" ")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) out.append(part.substring(1));
            out.append(' ');
        }
        return out.toString().trim();
    }
}