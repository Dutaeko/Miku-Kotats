package miku.moe.app;

import android.content.Context;
import android.widget.ImageView;
import coil.Coil;
import coil.ImageLoader;
import coil.request.ErrorResult;
import coil.request.ImageRequest;
import coil.request.Disposable;
import coil.request.CachePolicy;
import coil.request.SuccessResult;
import okhttp3.Headers;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class MangaImageLoader {
    private static final Map<ImageView, Disposable> ACTIVE_REQUESTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final LinkedHashMap<String, Disposable> ACTIVE_PRELOADS = new LinkedHashMap<>();
    private static final int MAX_ACTIVE_PRELOADS = 3;

    private MangaImageLoader() {}

    public interface Callback { void onSuccess(); void onError(); }

    public static void load(ImageView target, String url) { load(target, url, false, null); }
    public static void load(ImageView target, String url, boolean crossfade) { load(target, url, crossfade, null); }

    public static void load(ImageView target, String url, boolean crossfade, Callback callback) {
        loadForSource(target, url, null, crossfade, callback);
    }

    public static void loadForSource(ImageView target, String url, String sourceId, boolean crossfade, Callback callback) {
        loadForSourceInternal(target, url, sourceId, crossfade, callback, false);
    }

    public static void loadCoverForSource(ImageView target, String url, String sourceId) {
        loadCoverForSourceInternal(target, url, sourceId, false);
    }

    private static void loadCoverForSourceInternal(ImageView target, String url, String sourceId, boolean retryFromNetwork) {
        if (target == null) return;
        if (url == null || url.trim().isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        Context context = target.getContext();
        String safeUrl = url.trim();
        String requestKey = cacheKey(safeUrl, sourceId);
        Object currentTag = target.getTag();
        if (requestKey.equals(currentTag) && target.getDrawable() != null && !retryFromNetwork) {
            target.animate().cancel();
            target.setAlpha(1f);
            return;
        }
        String cachedUrl = retryFromNetwork ? null : MangaCoverCache.cachedUri(context, safeUrl);
        boolean local = isLocalUri(safeUrl);
        boolean usingCached = cachedUrl != null && !cachedUrl.trim().isEmpty();
        String requestData = usingCached ? cachedUrl : safeUrl;
        cancel(target);
        target.animate().cancel();
        target.setAlpha(1f);
        target.setTag(requestKey);
        ImageRequest request = new ImageRequest.Builder(target.getContext())
                .data(requestData)
                .headers(usingCached || local ? new Headers.Builder().build() : headersFor(safeUrl, sourceId))
                .memoryCacheKey(requestKey)
                .diskCacheKey(requestKey)
                .crossfade(true)
                .crossfade(500)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .allowHardware(true)
                .target(target)
                .listener(new ImageRequest.Listener() {
                    @Override public void onSuccess(ImageRequest request, SuccessResult result) { }
                    @Override public void onError(ImageRequest request, ErrorResult result) {
                        if (!requestKey.equals(target.getTag())) return;
                        if (usingCached && !retryFromNetwork) {
                            try { MangaCoverCache.delete(context.getApplicationContext(), safeUrl); } catch (Exception ignored) { }
                            loadCoverForSourceInternal(target, safeUrl, sourceId, true);
                        }
                    }
                })
                .build();
        ImageLoader imageLoader = Coil.imageLoader(context.getApplicationContext());
        Disposable disposable = imageLoader.enqueue(request);
        ACTIVE_REQUESTS.put(target, disposable);
    }

    private static void loadForSourceInternal(ImageView target, String url, String sourceId, boolean crossfade, Callback callback, boolean retryFromNetwork) {
        if (target == null) return;
        if (url == null || url.trim().isEmpty()) {
            target.setImageDrawable(null);
            if (callback != null) callback.onError();
            return;
        }
        Context context = target.getContext();
        String safeUrl = url.trim();
        String requestKey = cacheKey(safeUrl, sourceId);
        Object currentTag = target.getTag();
        if (requestKey.equals(currentTag) && target.getDrawable() != null && !retryFromNetwork) {
            target.animate().cancel();
            target.setAlpha(1f);
            if (callback != null) callback.onSuccess();
            return;
        }
        String cachedUrl = retryFromNetwork ? null : MangaCoverCache.cachedUri(context, safeUrl);
        boolean local = isLocalUri(safeUrl);
        boolean usingCached = cachedUrl != null && !cachedUrl.trim().isEmpty();
        String requestData = usingCached ? cachedUrl : safeUrl;
        cancel(target);
        target.animate().cancel();
        target.setTag(requestKey);
        ImageRequest request = new ImageRequest.Builder(context)
                .data(requestData)
                .headers(usingCached || local ? new Headers.Builder().build() : headersFor(safeUrl, sourceId))
                .memoryCacheKey(requestKey)
                .diskCacheKey(requestKey)
                .crossfade(crossfade)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .allowHardware(!(target instanceof MangaWebtoonImageView || target instanceof ZoomableMangaImageView))
                .target(target)
                .listener(new ImageRequest.Listener() {
                    @Override public void onSuccess(ImageRequest request, SuccessResult result) { if (requestKey.equals(target.getTag()) && callback != null) callback.onSuccess(); }
                    @Override public void onError(ImageRequest request, ErrorResult result) {
                        if (!requestKey.equals(target.getTag())) return;
                        if (usingCached && !retryFromNetwork) {
                            try { MangaCoverCache.delete(context.getApplicationContext(), safeUrl); } catch (Exception ignored) { }
                            loadForSourceInternal(target, safeUrl, sourceId, crossfade, callback, true);
                            return;
                        }
                        if (callback != null) callback.onError();
                    }
                })
                .build();
        Disposable disposable = Coil.imageLoader(context.getApplicationContext()).enqueue(request);
        ACTIVE_REQUESTS.put(target, disposable);
    }

    public static void preload(Context context, String url, String sourceId) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        String safeUrl = url.trim();
        String requestKey = cacheKey(safeUrl, sourceId);
        String cachedUrl = MangaCoverCache.cachedUri(context, safeUrl);
        if (cachedUrl != null && !cachedUrl.trim().isEmpty()) return;
        if (!registerPreload(requestKey)) return;
        ImageRequest request = new ImageRequest.Builder(context.getApplicationContext())
                .data(safeUrl)
                .headers(headersFor(safeUrl, sourceId))
                .memoryCacheKey(requestKey)
                .diskCacheKey(requestKey)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .allowHardware(false)
                .listener(new ImageRequest.Listener() {
                    @Override public void onSuccess(ImageRequest request, SuccessResult result) { finishPreload(requestKey); }
                    @Override public void onError(ImageRequest request, ErrorResult result) { finishPreload(requestKey); }
                })
                .build();
        Disposable disposable = Coil.imageLoader(context.getApplicationContext()).enqueue(request);
        attachPreload(requestKey, disposable);
    }


    private static boolean registerPreload(String requestKey) {
        if (requestKey == null || requestKey.trim().isEmpty()) return false;
        synchronized (ACTIVE_PRELOADS) {
            if (ACTIVE_PRELOADS.containsKey(requestKey)) return false;
            if (ACTIVE_PRELOADS.size() >= MAX_ACTIVE_PRELOADS) return false;
            ACTIVE_PRELOADS.put(requestKey, null);
            return true;
        }
    }

    private static void attachPreload(String requestKey, Disposable disposable) {
        if (requestKey == null || requestKey.trim().isEmpty()) return;
        synchronized (ACTIVE_PRELOADS) {
            if (ACTIVE_PRELOADS.containsKey(requestKey)) ACTIVE_PRELOADS.put(requestKey, disposable);
            else if (disposable != null) disposable.dispose();
        }
    }

    private static void finishPreload(String requestKey) {
        if (requestKey == null || requestKey.trim().isEmpty()) return;
        synchronized (ACTIVE_PRELOADS) {
            ACTIVE_PRELOADS.remove(requestKey);
        }
    }

    public static void preloadRange(Context context, java.util.List<String> pages, String sourceId, int center, int distance) {
        if (context == null || pages == null || pages.isEmpty()) return;
        int start = Math.max(0, center);
        int end = Math.min(pages.size() - 1, center + Math.max(1, distance));
        for (int i = start; i <= end; i++) preload(context, pages.get(i), sourceId);
    }

    private static boolean isLocalUri(String value) {
        if (value == null) return false;
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("android.resource://") || lower.startsWith("file://") || lower.startsWith("content://");
    }

    private static String sourceIdKey(String sourceId) { return sourceId == null ? "" : sourceId.trim(); }

    private static String cacheKey(String url, String sourceId) { return sourceIdKey(sourceId) + "|" + (url == null ? "" : url.trim()); }

    public static boolean isLoaded(ImageView target, String url, String sourceId) {
        if (target == null || url == null || url.trim().isEmpty()) return false;
        Object currentTag = target.getTag();
        return cacheKey(url.trim(), sourceId).equals(currentTag) && target.getDrawable() != null;
    }

    public static Headers headersFor(String url, String sourceId) {
        String referer;
        String origin;
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId) || url.contains("doujindesu") || url.contains("doujin")) {
            referer = "https://doujindesu.tv/";
            origin = "https://doujindesu.tv";
        } else if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId) || url.contains("westmanga.co")) {
            referer = "https://westmanga.co/";
            origin = "https://westmanga.co";
        } else if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId) || url.contains("bacakomik.my")) {
            referer = "https://bacakomik.my/";
            origin = "https://bacakomik.my";
        } else if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId) || url.contains("komikindo.ch")) {
            referer = "https://komikindo.ch/";
            origin = "https://komikindo.ch";
        } else if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId) || url.contains("ikiru.wtf")) {
            referer = "https://05.ikiru.wtf/";
            origin = "https://05.ikiru.wtf";
        } else if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId) || url.contains("shngm") || url.contains("shinigami")) {
            referer = "https://c.shinigami.asia/";
            origin = "https://c.shinigami.asia";
        } else if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId) || url.contains("v5.kiryuu.to") || url.contains("yuucdn.com")) {
            referer = "https://v5.kiryuu.to/";
            origin = "https://v5.kiryuu.to";
        } else if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId) || url.contains("natsu.tv") || url.contains("uqni.net")) {
            referer = "https://natsu.tv/";
            origin = "https://natsu.tv";
        } else if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId) || url.contains("ainzscans01.com") || url.contains("cdnainz.lonedev.my.id")) {
            referer = "https://v1.ainzscans01.com/";
            origin = "https://v1.ainzscans01.com";
        } else if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId) || url.contains("apkomik.com")) {
            String base = MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_APKOMIK);
            referer = base.endsWith("/") ? base : base + "/";
            origin = base;
        } else if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId) || url.contains("cosmicscans")) {
            String base = MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_COSMICSCANS);
            referer = base.endsWith("/") ? base : base + "/";
            origin = base;
        } else {
            referer = "https://v2.komikcast.fit/";
            origin = "https://v2.komikcast.fit";
        }
        return new Headers.Builder()
                .set("Referer", referer)
                .set("Origin", origin)
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .set("Cache-Control", "public, max-age=604800")
                .build();
    }

    public static void cancel(ImageView target) {
        if (target == null) return;
        try {
            Disposable disposable = ACTIVE_REQUESTS.remove(target);
            if (disposable != null) disposable.dispose();
        } catch (Exception ignored) { }
    }

    public static void cancelPreloads() {
        try {
            synchronized (ACTIVE_PRELOADS) {
                for (Disposable disposable : new java.util.ArrayList<>(ACTIVE_PRELOADS.values())) if (disposable != null) disposable.dispose();
                ACTIVE_PRELOADS.clear();
            }
        } catch (Exception ignored) { }
    }

    public static void clear(ImageView target) { if (target != null) { cancel(target); target.animate().cancel(); target.setAlpha(1f); target.setTag(null); target.setImageDrawable(null); } }

    public static void clearImageCache(Context context, List<String> urls, String sourceId) {
        if (context == null || urls == null || urls.isEmpty()) return;
        Context app = context.getApplicationContext();
        HashSet<String> keys = new HashSet<>();
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) continue;
            String safeUrl = url.trim();
            keys.add(cacheKey(safeUrl, sourceId));
            keys.add(safeUrl);
            String cachedUrl = MangaCoverCache.cachedUri(app, safeUrl);
            if (cachedUrl != null && !cachedUrl.trim().isEmpty()) keys.add(cachedUrl.trim());
            try { MangaCoverCache.delete(app, safeUrl); } catch (Exception ignored) { }
        }
        try {
            Object diskCache = Coil.imageLoader(app).getDiskCache();
            if (diskCache != null) {
                java.lang.reflect.Method remove = diskCache.getClass().getMethod("remove", String.class);
                for (String key : keys) {
                    if (key != null && !key.trim().isEmpty()) {
                        try { remove.invoke(diskCache, key.trim()); } catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception ignored) { }
        removeMemoryCacheKeys(app, keys);
    }

    private static void removeMemoryCacheKeys(Context context, java.util.Set<String> keys) {
        if (context == null || keys == null || keys.isEmpty()) return;
        try {
            Object memoryCache = Coil.imageLoader(context.getApplicationContext()).getMemoryCache();
            if (memoryCache == null) return;
            Class<?> keyClass = Class.forName("coil.memory.MemoryCache$Key");
            java.lang.reflect.Method remove = memoryCache.getClass().getMethod("remove", keyClass);
            for (String key : keys) {
                if (key == null || key.trim().isEmpty()) continue;
                Object memoryKey = createMemoryCacheKey(keyClass, key.trim());
                if (memoryKey != null) {
                    try { remove.invoke(memoryCache, memoryKey); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
    }

    private static Object createMemoryCacheKey(Class<?> keyClass, String value) {
        try { return keyClass.getConstructor(String.class).newInstance(value); } catch (Exception ignored) { }
        try { return keyClass.getConstructor(String.class, java.util.Map.class).newInstance(value, java.util.Collections.emptyMap()); } catch (Exception ignored) { }
        return null;
    }

    public static void clearMemoryCache(Context context) {
        if (context == null) return;
        try {
            coil.memory.MemoryCache cache = Coil.imageLoader(context.getApplicationContext()).getMemoryCache();
            if (cache != null) cache.clear();
        } catch (Exception ignored) { }
    }
}

