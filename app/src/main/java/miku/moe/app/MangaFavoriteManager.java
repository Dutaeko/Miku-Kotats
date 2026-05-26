package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class MangaFavoriteManager {
    private static final String PREFS = "miku_manga_favorites";
    private static final String KEY_ITEMS = "items";
    private static final String AES_SECRET = "Miku01v-Manga-Favorite-Backup-Key";
    private MangaFavoriteManager() {}

    public static boolean isFavorite(Context c, String slug) {
        if (c == null || slug == null) return false;
        String safeSlug = slug.trim();
        if (safeSlug.isEmpty()) return false;
        String sourceId = MangaSettingsManager.getMangaSource(c);
        for (MangaPost p : getFavorites(c)) {
            if (p != null && safeSlug.equals(p.slug) && p.getSourceId().equals(sourceId)) return true;
        }
        return false;
    }

    public static boolean isFavorite(Context c, MangaPost post) {
        if (c == null || post == null || post.slug == null) return false;
        String safeSlug = post.slug.trim();
        if (safeSlug.isEmpty()) return false;
        String sourceId = post.getSourceId();
        for (MangaPost p : getFavorites(c)) {
            if (p != null && safeSlug.equals(p.slug) && p.getSourceId().equals(sourceId)) return true;
        }
        return false;
    }

    public static void toggle(Context c, MangaPost post) {
        if (isFavorite(c, post)) remove(c, post); else add(c, post);
    }

    public static void remove(Context c, MangaPost post) {
        if (c == null || post == null || post.slug == null) return;
        String safeSlug = post.slug.trim();
        ArrayList<MangaPost> all = getFavorites(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            MangaPost current = all.get(i);
            if (current != null && safeSlug.equals(current.slug) && current.getSourceId().equals(post.getSourceId())) all.remove(i);
        }
        persist(c, all);
    }

    public static void add(Context c, MangaPost post) {
        try {
            if (c == null || post == null || post.slug == null || post.slug.trim().isEmpty()) return;
            ArrayList<MangaPost> all = getFavorites(c);
            int existingIndex = -1;
            for (int i = 0; i < all.size(); i++) {
                MangaPost current = all.get(i);
                if (current != null && current.slug.equals(post.slug) && current.getSourceId().equals(post.getSourceId())) {
                    existingIndex = i;
                    break;
                }
            }
            if (existingIndex >= 0) all.set(existingIndex, post); else all.add(0, post);
            persist(c, all);
            saveCoverIfEnabled(c, post);
        } catch (Exception ignored) {}
    }

    public static void remove(Context c, String slug) {
        if (c == null || slug == null) return;
        String safeSlug = slug.trim();
        String sourceId = MangaSettingsManager.getMangaSource(c);
        ArrayList<MangaPost> all = getFavorites(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            MangaPost current = all.get(i);
            if (current != null && safeSlug.equals(current.slug) && current.getSourceId().equals(sourceId)) all.remove(i);
        }
        persist(c, all);
    }

    public static ArrayList<MangaPost> getFavorites(Context c) {
        if (c == null) return new ArrayList<>();
        return parse(p(c).getString(KEY_ITEMS, "[]"));
    }

    public static String exportEncrypted(Context c) throws Exception {
        if (c == null) return "const MIKU_MANGA_FAVORITES_AES = \"\";";
        return "const MIKU_MANGA_FAVORITES_AES = \"" + encrypt(p(c).getString(KEY_ITEMS, "[]")) + "\";";
    }

    public static void importEncrypted(Context c, String fileContent) throws Exception {
        if (c == null) return;
        String data = fileContent == null ? "" : fileContent.trim();
        int q1 = data.indexOf('"');
        int q2 = data.lastIndexOf('"');
        if (q1 >= 0 && q2 > q1) data = data.substring(q1 + 1, q2);
        ArrayList<MangaPost> list = parse(decrypt(data));
        persist(c, list);
    }

    private static ArrayList<MangaPost> parse(String raw) {
        ArrayList<MangaPost> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String sourceId = o.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
                MangaPost p = new MangaPost(o.optString("slug"), o.optString("title"), o.optString("cover"), o.optString("author"), o.optString("status"), o.optString("synopsis"), o.optString("genre"), o.optString("typeLabel"), o.optString("latestChapter"), o.optString("latestChapterDate")).withSource(sourceId, o.optString("sourceLabel", MangaSourceFactory.labelForSourceId(sourceId)));
                p.totalChapters = o.optInt("totalChapters", 0);
                if (!p.slug.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void saveFavorites(Context c, ArrayList<MangaPost> list) { persist(c, list); }

    private static void persist(Context c, ArrayList<MangaPost> list) {
        if (c == null || list == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (MangaPost m : list) {
                if (m == null || m.slug == null || m.slug.trim().isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("slug", m.slug.trim());
                o.put("sourceId", m.getSourceId());
                o.put("sourceLabel", m.getSourceLabel());
                o.put("title", m.title);
                o.put("cover", m.coverImage);
                o.put("author", m.author);
                o.put("status", m.status);
                o.put("synopsis", m.synopsis);
                o.put("genre", m.genre);
                o.put("typeLabel", m.getTypeLabel());
                o.put("latestChapter", m.latestChapter);
                o.put("latestChapterDate", m.latestChapterDate);
                o.put("totalChapters", Math.max(0, m.totalChapters));
                arr.put(o);
            }
            p(c).edit().putString(KEY_ITEMS, arr.toString()).commit();
        } catch (Exception ignored) {}
    }

    private static void saveCoverIfEnabled(Context c, MangaPost post) {
        if (c == null || post == null || post.coverImage == null || post.coverImage.trim().isEmpty()) return;
        if (!MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(c)) return;
        MangaCoverCache.saveAsync(c.getApplicationContext(), post.coverImage, post.getSourceId());
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String decrypt(String enc) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key());
        return new String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(AES_SECRET.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
