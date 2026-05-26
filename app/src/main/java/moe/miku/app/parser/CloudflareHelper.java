package moe.miku.app.parser;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import javax.net.ssl.SSLException;

public final class CloudflareHelper {
    private static Context appContext;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, CopyOnWriteArrayList<PendingRequest>> pending = new ConcurrentHashMap<>();
    private static final Map<String, ChallengeInfo> challenges = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> resolving = new ConcurrentHashMap<>();
    private static final CookieJar cookieJar = new WebViewCookieJar();

    private CloudflareHelper() {}

    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
        CookieManager.getInstance().setAcceptCookie(true);
        NetworkDohManager.init(appContext);
    }

    public static CookieJar cookieJar() {
        return cookieJar;
    }

    public static OkHttpClient.Builder apply(OkHttpClient.Builder builder) {
        return NetworkDohManager.apply(builder).cookieJar(cookieJar).retryOnConnectionFailure(true);
    }

    public static void enqueue(OkHttpClient client, Request request, String sourceLabel, Callback callback) {
        enqueue(client, request, sourceLabel, callback, 0);
    }

    public static String errorMessage(Throwable e) {
        if (isInternetError(e)) return "Tidak ada koneksi";
        String message = e == null ? "" : e.getMessage();
        if (message == null || message.trim().isEmpty()) return "Gagal memuat data. Coba lagi.";
        String lower = message.toLowerCase(Locale.ROOT);
        if (isRateLimitMessage(message)) return "Terlalu banyak request";
        if (lower.contains("cloudflare")) return "Lewati Cloudflare dulu";
        return message;
    }

    public static boolean isInternetError(Throwable e) {
        return e instanceof UnknownHostException || e instanceof ConnectException || e instanceof SocketTimeoutException || e instanceof SSLException;
    }

    public static boolean isCloudflareRequiredMessage(String message) {
        String text = value(message).toLowerCase(Locale.ROOT);
        return text.contains("harap selesaikan cloudflare") || text.contains("lewati cloudflare");
    }

    public static boolean needsResolution(String sourceLabel) {
        String label = cleanLabel(sourceLabel);
        for (ChallengeInfo info : challenges.values()) {
            if (label.equals(cleanLabel(info.sourceLabel))) return true;
        }
        return false;
    }

    public static boolean openResolverForSource(String sourceLabel) {
        return openResolverForSource(null, sourceIdForLabel(sourceLabel), sourceLabel);
    }

    public static boolean openResolverForSource(Context context, String sourceId, String sourceLabel) {
        String label = cleanLabel(sourceLabel);
        for (Map.Entry<String, ChallengeInfo> entry : challenges.entrySet()) {
            ChallengeInfo info = entry.getValue();
            if (label.equals(cleanLabel(info.sourceLabel))) {
                String host = entry.getKey();
                if (resolving.put(host, true) == null) openResolver(context, info.url, host, info.sourceLabel);
                return true;
            }
        }
        String fallbackUrl = fallbackUrlForSource(context, sourceId);
        String fallbackHost = hostOf(fallbackUrl);
        if (fallbackUrl.isEmpty() || fallbackHost.isEmpty()) return false;
        challenges.put(fallbackHost, new ChallengeInfo(fallbackUrl, fallbackHost, label));
        if (resolving.put(fallbackHost, true) == null) openResolver(context, fallbackUrl, fallbackHost, label);
        return true;
    }

    private static void enqueue(OkHttpClient client, Request request, String sourceLabel, Callback callback, int retry) {
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                callback.onFailure(call, new IOException(errorMessage(e), e));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String bodyPreview = "";
                try {
                    ResponseBody peek = response.peekBody(256L * 1024L);
                    bodyPreview = peek == null ? "" : peek.string();
                } catch (Exception ignored) {}
                if (isCloudflare(response, bodyPreview) && retry < 2) {
                    response.close();
                    queue(client, request, sourceLabel, callback, retry + 1);
                    return;
                }
                callback.onResponse(call, response);
            }
        });
    }

    private static void queue(OkHttpClient client, Request request, String sourceLabel, Callback callback, int retry) {
        String host = request.url().host();
        pending.computeIfAbsent(host, k -> new CopyOnWriteArrayList<>()).add(new PendingRequest(client, request, sourceLabel, callback, retry));
        challenges.put(host, new ChallengeInfo(request.url().toString(), host, sourceLabel));
        callback.onFailure(client.newCall(request), new IOException("🆘 Harap selesaikan Cloudflare pada Source Manga " + cleanLabel(sourceLabel) + " 🆘"));
    }

    private static void openResolver(Context context, String url, String host, String sourceLabel) {
        failed(host, "Cloudflare belum selesai");
    }

    public static void solved(String host) {
        resolving.remove(host);
        challenges.remove(host);
        retry(host);
    }

    public static void keepPending(String host) {
        resolving.remove(host);
    }

    public static void verifySolved(String url, String host, VerifyCallback callback) {
        OkHttpClient client = apply(new OkHttpClient.Builder()).build();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://" + host + "/")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                if (callback != null) main.post(() -> callback.onResult(false, errorMessage(e)));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String bodyPreview = "";
                try {
                    ResponseBody peek = response.peekBody(256L * 1024L);
                    bodyPreview = peek == null ? "" : peek.string();
                } catch (Exception ignored) {}
                boolean solved = response.isSuccessful() && !isCloudflare(response, bodyPreview);
                response.close();
                if (callback != null) main.post(() -> callback.onResult(solved, solved ? "" : "Cloudflare belum selesai"));
            }
        });
    }



    private static String sourceIdForLabel(String sourceLabel) {
        String label = cleanLabel(sourceLabel).toLowerCase(Locale.ROOT);
        if (label.equals("komikcast")) return MangaSettingsManager.MANGA_SOURCE_KOMIKCAST;
        if (label.equals("shinigami")) return MangaSettingsManager.MANGA_SOURCE_SHINIGAMI;
        if (label.equals("doujindesu")) return MangaSettingsManager.MANGA_SOURCE_DOUJINDESU;
        if (label.equals("westmanga")) return MangaSettingsManager.MANGA_SOURCE_WESTMANGA;
        if (label.equals("bacakomik")) return MangaSettingsManager.MANGA_SOURCE_BACAKOMIK;
        if (label.equals("komikindo")) return MangaSettingsManager.MANGA_SOURCE_KOMIKINDO;
        if (label.equals("ikiru")) return MangaSettingsManager.MANGA_SOURCE_IKIRU;
        if (label.equals("komiku")) return MangaSettingsManager.MANGA_SOURCE_KOMIKU;
        if (label.equals("mangasusuku") || label.equals("mangasusu")) return MangaSettingsManager.MANGA_SOURCE_MANGASUSU;
        if (label.equals("komiku org") || label.equals("komikuorg")) return MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG;
        if (label.equals("cosmicscans") || label.equals("cosmic scans")) return MangaSettingsManager.MANGA_SOURCE_COSMICSCANS;
        if (label.equals("kiryuu")) return MangaSettingsManager.MANGA_SOURCE_KIRYUU;
        if (label.equals("kiryuu official") || label.equals("kiryuuofficial")) return MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL;
        if (label.equals("natsu")) return MangaSettingsManager.MANGA_SOURCE_NATSU;
        if (label.equals("ainzscanss") || label.equals("ainz scanss") || label.equals("ainzscans")) return MangaSettingsManager.MANGA_SOURCE_AINZSCANSS;
        if (label.equals("apkomik")) return MangaSettingsManager.MANGA_SOURCE_APKOMIK;
        return "";
    }

    private static String fallbackUrlForSource(Context context, String sourceId) {
        String source = sourceId == null ? "" : sourceId.trim();
        if (!source.isEmpty() && MangaSettingsManager.isValidSource(source)) return MangaSettingsManager.getSourceDomain(context, source);
        return "";
    }

    private static String hostOf(String url) {
        try {
            Uri parsed = Uri.parse(url);
            String host = parsed == null ? "" : parsed.getHost();
            return host == null ? "" : host.trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static void failed(String host) {
        failed(host, "Cloudflare belum selesai");
    }

    public static void failed(String host, String reason) {
        resolving.remove(host);
        challenges.remove(host);
        CopyOnWriteArrayList<PendingRequest> list = pending.remove(host);
        if (list == null) return;
        String message = reason == null || reason.trim().isEmpty() ? "Cloudflare belum selesai" : reason.trim();
        for (PendingRequest item : list) {
            item.callback.onFailure(item.client.newCall(item.request), new IOException(errorMessage(new IOException(message))));
        }
    }

    private static void retry(String host) {
        CopyOnWriteArrayList<PendingRequest> list = pending.remove(host);
        if (list == null) return;
        for (PendingRequest item : list) enqueue(item.client, item.request, item.sourceLabel, item.callback, item.retry);
    }

    private static boolean isCloudflare(Response response, String body) {
        int code = response.code();
        String server = value(response.header("server")).toLowerCase(Locale.ROOT);
        String text = value(body).toLowerCase(Locale.ROOT);
        if (code == 429 || isRateLimitMessage(text)) return false;
        boolean status = code == 403 || code == 503;
        boolean header = server.contains("cloudflare");
        boolean challenge = text.contains("cf-browser-verification") || text.contains("cf_clearance") || text.contains("just a moment") || text.contains("challenge-platform") || text.contains("/cdn-cgi/challenge-platform") || text.contains("checking your browser") || text.contains("attention required") || text.contains("turnstile");
        return status && challenge && (header || text.contains("cloudflare"));
    }

    private static boolean isRateLimitMessage(String message) {
        String text = value(message).toLowerCase(Locale.ROOT);
        return text.contains("too many request") || text.contains("too many requests") || text.contains("retcode\": 40029") || text.contains("retcode:40029") || text.contains("rate limit") || text.contains("429");
    }

    private static String cleanLabel(String value) {
        return value == null || value.trim().isEmpty() ? "source" : value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static final class ChallengeInfo {
        final String url;
        final String host;
        final String sourceLabel;

        ChallengeInfo(String url, String host, String sourceLabel) {
            this.url = url;
            this.host = host;
            this.sourceLabel = sourceLabel;
        }
    }

    public interface VerifyCallback {
        void onResult(boolean solved, String message);
    }

    private static final class PendingRequest {
        final OkHttpClient client;
        final Request request;
        final String sourceLabel;
        final Callback callback;
        final int retry;

        PendingRequest(OkHttpClient client, Request request, String sourceLabel, Callback callback, int retry) {
            this.client = client;
            this.request = request;
            this.sourceLabel = sourceLabel;
            this.callback = callback;
            this.retry = retry;
        }
    }

    private static final class WebViewCookieJar implements CookieJar {
        @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            CookieManager manager = CookieManager.getInstance();
            for (Cookie cookie : cookies) manager.setCookie(url.toString(), cookie.toString());
            manager.flush();
        }

        @Override public List<Cookie> loadForRequest(HttpUrl url) {
            ArrayList<Cookie> out = new ArrayList<>();
            String raw = CookieManager.getInstance().getCookie(url.toString());
            if (raw == null || raw.trim().isEmpty()) return out;
            String[] parts = raw.split(";");
            for (String part : parts) {
                String item = part.trim();
                int idx = item.indexOf('=');
                if (idx <= 0) continue;
                Cookie cookie = new Cookie.Builder()
                        .domain(url.host())
                        .path("/")
                        .name(item.substring(0, idx).trim())
                        .value(item.substring(idx + 1).trim())
                        .build();
                out.add(cookie);
            }
            return out;
        }
    }
}
