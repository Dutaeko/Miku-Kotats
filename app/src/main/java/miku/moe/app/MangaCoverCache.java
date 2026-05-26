package miku.moe.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class MangaCoverCache {
    public interface Callback { void onComplete(boolean saved); }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final OkHttpClient CLIENT = NetworkDohManager.apply(new OkHttpClient.Builder()).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HashMap<String, ArrayList<Callback>> PENDING = new HashMap<>();
    private static final long MAX_CACHE_BYTES = 80L * 1024L * 1024L;
    private static final int MAX_FILES = 500;

    private MangaCoverCache() {}

    public static String cachedUri(Context context, String url) {
        File file = fileFor(context, url);
        if (file == null || !file.exists() || file.length() <= 0) return null;
        return Uri.fromFile(file).toString();
    }

    public static long sizeBytes(Context context) {
        File dir = cacheDir(context);
        return sizeOf(dir);
    }

    public static void clear(Context context) {
        File dir = cacheDir(context);
        deleteRecursive(dir);
    }

    public static void delete(Context context, String url) {
        File file = fileFor(context, url);
        if (file == null) return;
        try { if (file.exists()) file.delete(); } catch (Exception ignored) {}
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                File tmp = new File(parent, file.getName() + ".tmp");
                if (tmp.exists()) tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    public static void saveAsync(Context context, String url, String sourceId) {
        saveAsync(context, url, sourceId, null);
    }

    public static void saveAsync(Context context, String url, String sourceId, Callback callback) {
        if (context == null || url == null || url.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        String safeUrl = url.trim();
        File file = fileFor(app, safeUrl);
        if (file == null) return;
        if (file.exists() && file.length() > 0) return;
        String key = sha256(safeUrl);
        synchronized (PENDING) {
            ArrayList<Callback> callbacks = PENDING.get(key);
            if (callbacks != null) {
                if (callback != null) callbacks.add(callback);
                return;
            }
            callbacks = new ArrayList<>();
            if (callback != null) callbacks.add(callback);
            PENDING.put(key, callbacks);
        }
        EXECUTOR.execute(() -> {
            boolean saved = save(app, safeUrl, sourceId);
            finishPending(key, saved);
        });
    }

    private static void finishPending(String key, boolean saved) {
        ArrayList<Callback> callbacks;
        synchronized (PENDING) {
            callbacks = PENDING.remove(key);
        }
        if (callbacks == null || callbacks.isEmpty()) return;
        MAIN.post(() -> {
            for (Callback callback : callbacks) {
                try { callback.onComplete(saved); } catch (Exception ignored) {}
            }
        });
    }

    private static boolean save(Context context, String url, String sourceId) {
        File file = fileFor(context, url);
        if (file == null) return false;
        if (file.exists() && file.length() > 0) return true;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            Request request = new Request.Builder().url(url).headers(MangaImageLoader.headersFor(url, sourceId)).build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) return false;
                ResponseBody body = response.body();
                if (body == null) return false;
                long total = 0L;
                try (InputStream input = body.byteStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (read > 0) {
                            out.write(buffer, 0, read);
                            total += read;
                        }
                    }
                    out.flush();
                }
                if (total <= 0L) {
                    tmp.delete();
                    return false;
                }
                if (file.exists()) file.delete();
                boolean renamed = tmp.renameTo(file);
                if (renamed) trim(context);
                return renamed;
            }
        } catch (Exception ignored) {
            try { tmp.delete(); } catch (Exception ignored2) {}
            return false;
        }
    }

    private static File fileFor(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) return null;
        return new File(cacheDir(context), sha256(url.trim()) + extension(url));
    }

    private static File cacheDir(Context context) {
        if (context == null) return null;
        return new File(context.getApplicationContext().getFilesDir(), "manga_cover_cache");
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private static String extension(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".gif")) return ".gif";
        return ".jpg";
    }

    private static void trim(Context context) {
        File dir = cacheDir(context);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        long total = 0L;
        for (File f : files) if (f.isFile()) total += Math.max(0L, f.length());
        while ((total > MAX_CACHE_BYTES || files.length > MAX_FILES) && files.length > 0) {
            File oldest = files[0];
            for (File f : files) if (f.lastModified() < oldest.lastModified()) oldest = f;
            long len = Math.max(0L, oldest.length());
            if (oldest.delete()) total -= len;
            files = dir.listFiles();
            if (files == null) break;
        }
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return Math.max(0L, file.length());
        File[] children = file.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) total += sizeOf(child);
        return total;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        try { file.delete(); } catch (Exception ignored) {}
    }
}
