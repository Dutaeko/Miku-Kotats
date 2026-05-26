package miku.moe.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

public class MikuUpdate extends AppCompatActivity {
    private static final String PREFS = "miku_manga_update_results";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_LAST_CHECK_FOUND = "last_check_found";
    private static final String KEY_LAST_CHECK_AT = "last_check_at";
    private static final String PREFS_BASELINE = "miku_manga_update_baseline";
    private static final String KEY_BASELINES = "baselines";
    private final ArrayList<UpdateItem> updateItems = new ArrayList<>();
    private JSONObject updateBaselines = new JSONObject();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UpdateAdapter adapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView statusTextView;
    private TextView emptyTextView;
    private MaterialButton checkUpdateButton;
    private MaterialButton clearUpdateHistoryButton;
    private ArrayList<MangaPost> workingFavorites = new ArrayList<>();
    private static MikuUpdate visibleActivity;
    private static boolean checking;
    private static String runningStatusText = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_miku_update);
        setupSystemBars();
        statusTextView = findViewById(R.id.statusTextView);
        emptyTextView = findViewById(R.id.emptyUpdateTextView);
        checkUpdateButton = findViewById(R.id.checkUpdateButton);
        clearUpdateHistoryButton = findViewById(R.id.clearUpdateHistoryButton);
        MaterialButton backButton = findViewById(R.id.backButton);
        RecyclerView recyclerView = findViewById(R.id.updateRecyclerView);
        swipeRefreshLayout = findViewById(R.id.updateSwipeRefreshLayout);
        updateBaselines = loadBaselines();
        updateItems.addAll(loadStoredUpdates());
        sortUpdateItems();
        adapter = new UpdateAdapter(updateItems, this::openDetail);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        backButton.setOnClickListener(v -> finish());
        checkUpdateButton.setOnClickListener(v -> checkUpdates(true));
        clearUpdateHistoryButton.setOnClickListener(v -> showClearUpdateHistoryDialog());
        swipeRefreshLayout.setOnRefreshListener(this::refreshStoredHistory);
        visibleActivity = this;
        updateEmpty(false, false);
        setStatus(checking && !runningStatusText.trim().isEmpty() ? runningStatusText : initialStatusText());
        updateActionButtons();
    }

    @Override protected void onResume() {
        super.onResume();
        visibleActivity = this;
        refreshStoredHistory(false);
        setStatus(checking && !runningStatusText.trim().isEmpty() ? runningStatusText : initialStatusText());
        updateActionButtons();
    }

    @Override protected void onDestroy() {
        if (visibleActivity == this) visibleActivity = null;
        super.onDestroy();
    }


    private void showClearUpdateHistoryDialog() {
        if (updateItems.isEmpty()) {
            setStatus("History update masih kosong");
            updateActionButtons();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Hapus history update")
                .setMessage("Apakah kamu yakin ingin menghapus semua history hasil update manga?")
                .setNegativeButton("Tidak", null)
                .setPositiveButton("Ya", (dialog, which) -> clearUpdateHistory())
                .show();
    }

    private void clearUpdateHistory() {
        updateItems.clear();
        prefs().edit().remove(KEY_ITEMS).remove(KEY_LAST_CHECK_FOUND).remove(KEY_LAST_CHECK_AT).apply();
        if (adapter != null) adapter.refreshData();
        if (emptyTextView != null) {
            emptyTextView.setText("History update kosong");
            emptyTextView.setVisibility(View.VISIBLE);
        }
        setStatus("History update sudah dihapus");
        updateActionButtons();
    }

    private void updateActionButtons() {
        MikuUpdate target = visibleActivity == null ? this : visibleActivity;
        if (target.checkUpdateButton != null) target.checkUpdateButton.setEnabled(!checking);
        if (target.clearUpdateHistoryButton != null) {
            boolean enabled = !checking && !target.updateItems.isEmpty();
            target.clearUpdateHistoryButton.setEnabled(enabled);
            target.clearUpdateHistoryButton.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private void refreshStoredHistory() {
        refreshStoredHistory(true);
    }

    private void refreshStoredHistory(boolean showRefresh) {
        if (showRefresh) setRefreshing(true);
        updateItems.clear();
        updateItems.addAll(loadStoredUpdates());
        sortUpdateItems();
        if (adapter != null) adapter.refreshData();
        updateEmpty(false, false);
        if (!checking) setStatus(initialStatusText());
        updateActionButtons();
        setRefreshing(checking);
    }

    private void refreshVisibleStoredHistory() {
        MikuUpdate target = visibleActivity;
        if (target != null) target.refreshStoredHistory(false);
    }

    private void checkUpdates(boolean forceNetwork) {
        if (checking) {
            setRefreshing(true);
            updateActionButtons();
            return;
        }
        ArrayList<MangaPost> favorites = MangaFavoriteManager.getFavorites(this);
        if (favorites.isEmpty()) {
            updateEmpty(false, updateItems.isEmpty());
            setStatus("Belum ada manga favorit");
            setRefreshing(false);
            updateActionButtons();
            return;
        }
        checking = true;
        runningStatusText = "Mengecek 0/" + favorites.size();
        updateEmpty(false, false);
        setRefreshing(true);
        updateActionButtons();
        if (forceNetwork) MangaMemoryCache.clearRegistered();
        workingFavorites = new ArrayList<>(favorites);
        updateBaselines = loadBaselines();
        checkAt(new ArrayList<>(favorites), 0, false, 0);
    }

    private void checkAt(ArrayList<MangaPost> snapshot, int index, boolean changed, int found) {
        if (index >= snapshot.size()) {
            checking = false;
            if (changed) MangaFavoriteManager.saveFavorites(getApplicationContext(), workingFavorites);
            persistUpdateItems();
            persistLastCheckResult(found);
            setRefreshing(false);
            refreshVisibleStoredHistory();
            updateActionButtons();
            updateEmpty(true, false);
            setStatus(updateStatusTextAfterCheck(found));
            return;
        }
        MangaPost oldPost = snapshot.get(index);
        setStatus("Mengecek " + (index + 1) + "/" + snapshot.size());
        if (oldPost == null || oldPost.slug == null || oldPost.slug.trim().isEmpty()) {
            handler.postDelayed(() -> checkAt(snapshot, index + 1, changed, found), 80);
            return;
        }
        KomikcastClient sourceApi = MangaSourceFactory.createBySourceId(oldPost.getSourceId());
        sourceApi.detail(oldPost.slug, new KomikcastClient.Result<MangaPost>() {
            @Override public void onSuccess(MangaPost fresh, boolean next) {
                MangaPost merged = mergeFavorite(oldPost, fresh);
                sourceApi.chapters(oldPost.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
                    @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                        String key = updateKey(oldPost);
                        Baseline baseline = getBaseline(key);
                        UpdateItem existingUpdate = findUpdateItem(key);
                        float baseIndex = baseline != null && baseline.chapterIndex > 0f ? baseline.chapterIndex : parseChapterIndex(oldPost.latestChapter);
                        boolean hasBaseline = baseIndex > 0f;
                        applyLatestChapter(merged, chapters);
                        int added = hasBaseline ? calculateAddedFromBaseline(baseIndex, chapters) : 0;
                        boolean itemChanged = replaceFavorite(oldPost, merged);
                        updateBaseline(key, merged, chapters);
                        persistBaselines();
                        int nextFound = found;
                        if (added > 0) {
                            if (existingUpdate != null) added += existingUpdate.added;
                            upsertUpdateItem(merged, added, chapters, System.currentTimeMillis());
                            nextFound++;
                        } else if (existingUpdate != null) {
                            refreshExistingUpdateItem(existingUpdate, merged, chapters);
                        }
                        final boolean nextChanged = changed || itemChanged;
                        final int finalFound = nextFound;
                        handler.postDelayed(() -> checkAt(snapshot, index + 1, nextChanged, finalFound), 100);
                    }

                    @Override public void onError(String message) {
                        boolean itemChanged = replaceFavorite(oldPost, merged);
                        handler.postDelayed(() -> checkAt(snapshot, index + 1, changed || itemChanged, found), 100);
                    }
                });
            }

            @Override public void onError(String message) {
                handler.postDelayed(() -> checkAt(snapshot, index + 1, changed, found), 100);
            }
        });
    }

    private void upsertUpdateItem(MangaPost post, int added, ArrayList<MangaChapter> chapters, long checkedAt) {
        MangaChapter newest = newestChapter(chapters);
        String chapterLabel = newest == null ? safe(post.latestChapter) : "Chapter " + MangaChapter.formatIndex(newest.index);
        String key = updateKey(post);
        for (int i = 0; i < updateItems.size(); i++) {
            UpdateItem current = updateItems.get(i);
            if (current != null && current.key.equals(key)) {
                updateItems.set(i, new UpdateItem(key, post, chapterLabel, checkedAt, added));
                sortUpdateItems();
                persistUpdateItems();
                if (adapter != null) adapter.refreshData();
                refreshVisibleStoredHistory();
                updateEmpty(false, false);
                return;
            }
        }
        updateItems.add(new UpdateItem(key, post, chapterLabel, checkedAt, added));
        sortUpdateItems();
        persistUpdateItems();
        if (adapter != null) adapter.refreshData();
        refreshVisibleStoredHistory();
        updateEmpty(false, false);
    }

    private UpdateItem findUpdateItem(String key) {
        if (key == null || key.trim().isEmpty()) return null;
        for (UpdateItem item : updateItems) {
            if (item != null && key.equals(item.key)) return item;
        }
        return null;
    }

    private boolean shouldKeepExistingUpdateItem(UpdateItem existing, ArrayList<MangaChapter> chapters) {
        if (existing == null) return false;
        MangaChapter newest = newestChapter(chapters);
        if (newest == null) return true;
        float existingIndex = parseChapterIndex(existing.chapterLabel);
        return existingIndex <= 0f || newest.index >= existingIndex;
    }

    private void refreshExistingUpdateItem(UpdateItem existing, MangaPost post, ArrayList<MangaChapter> chapters) {
        if (existing == null) return;
        MangaChapter newest = newestChapter(chapters);
        String chapterLabel = existing.chapterLabel;
        int added = existing.added;
        if (newest != null) {
            float existingIndex = parseChapterIndex(existing.chapterLabel);
            if (existingIndex > 0f && newest.index > existingIndex) {
                chapterLabel = "Chapter " + MangaChapter.formatIndex(newest.index);
                added += countNewChapters(chapters, existingIndex);
            }
        }
        for (int i = 0; i < updateItems.size(); i++) {
            UpdateItem current = updateItems.get(i);
            if (current != null && current.key.equals(existing.key)) {
                updateItems.set(i, new UpdateItem(existing.key, post, chapterLabel, existing.checkedAt, added));
                sortUpdateItems();
                persistUpdateItems();
                if (adapter != null) adapter.refreshData();
                refreshVisibleStoredHistory();
                updateEmpty(false, false);
                return;
            }
        }
    }

    private boolean removeUpdateItem(String key) {
        if (key == null || key.trim().isEmpty()) return false;
        boolean removed = false;
        for (int i = updateItems.size() - 1; i >= 0; i--) {
            UpdateItem current = updateItems.get(i);
            if (current != null && key.equals(current.key)) {
                updateItems.remove(i);
                removed = true;
            }
        }
        if (removed) {
            sortUpdateItems();
            persistUpdateItems();
            if (adapter != null) adapter.refreshData();
            updateEmpty(false, false);
        }
        return removed;
    }

    private MangaChapter newestChapter(ArrayList<MangaChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return null;
        MangaChapter newest = null;
        for (MangaChapter chapter : chapters) {
            if (chapter == null) continue;
            if (newest == null || chapter.index > newest.index) newest = chapter;
        }
        return newest;
    }

    private MangaPost mergeFavorite(MangaPost oldPost, MangaPost fresh) {
        MangaPost result = fresh == null ? oldPost : fresh;
        if (result.title == null || result.title.trim().isEmpty()) result.title = oldPost.title;
        if (result.coverImage == null || result.coverImage.trim().isEmpty()) result.coverImage = oldPost.coverImage;
        if (result.author == null || result.author.trim().isEmpty()) result.author = oldPost.author;
        if (result.status == null || result.status.trim().isEmpty()) result.status = oldPost.status;
        if (result.synopsis == null || result.synopsis.trim().isEmpty()) result.synopsis = oldPost.synopsis;
        if (result.genre == null || result.genre.trim().isEmpty()) result.genre = oldPost.genre;
        if (result.latestChapter == null || result.latestChapter.trim().isEmpty()) result.latestChapter = oldPost.latestChapter;
        if (result.latestChapterDate == null || result.latestChapterDate.trim().isEmpty()) result.latestChapterDate = oldPost.latestChapterDate;
        result.withSource(oldPost.getSourceId(), oldPost.getSourceLabel());
        result.totalChapters = Math.max(result.totalChapters, oldPost.totalChapters);
        result.favoriteChapterBase = 0;
        result.favoriteChapterAdded = 0;
        if (!MangaSettingsManager.shouldLoadTypeLabel(this)) result.typeLabel = "";
        return result;
    }

    private void applyLatestChapter(MangaPost post, ArrayList<MangaChapter> chapters) {
        if (post == null || chapters == null || chapters.isEmpty()) return;
        MangaChapter newest = newestChapter(chapters);
        if (newest == null) return;
        post.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
        post.latestChapterDate = newest.date == null ? "" : newest.date;
        post.totalChapters = Math.max(post.totalChapters, chapters.size());
    }

    private int calculateAddedFromBaseline(float baseIndex, ArrayList<MangaChapter> chapters) {
        if (chapters == null || chapters.isEmpty() || baseIndex <= 0f) return 0;
        return Math.max(0, countNewChapters(chapters, baseIndex));
    }

    private int countNewChapters(ArrayList<MangaChapter> chapters, float threshold) {
        if (chapters == null || chapters.isEmpty() || threshold <= 0f) return 0;
        int count = 0;
        for (MangaChapter chapter : chapters) if (chapter != null && chapter.index > threshold) count++;
        return count;
    }

    private int favoriteChapterTotal(MangaPost post) {
        if (post == null) return 0;
        if (post.totalChapters > 0) return post.totalChapters;
        float chapterIndex = parseChapterIndex(post.latestChapter);
        if (chapterIndex > 0f) return Math.round(chapterIndex);
        return 0;
    }

    private float parseChapterIndex(String text) {
        if (text == null) return -1f;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text.replace(',', '.'));
        if (!matcher.find()) return -1f;
        try { return Float.parseFloat(matcher.group(1)); } catch(Exception e) { return -1f; }
    }

    private boolean replaceFavorite(MangaPost oldPost, MangaPost fresh) {
        for (int i = 0; i < workingFavorites.size(); i++) {
            MangaPost current = workingFavorites.get(i);
            if (current != null && current.slug.equals(oldPost.slug) && current.getSourceId().equals(oldPost.getSourceId())) {
                String before = signature(current);
                workingFavorites.set(i, fresh);
                return !before.equals(signature(fresh));
            }
        }
        return false;
    }

    private String signature(MangaPost post) {
        if (post == null) return "";
        return safe(post.title) + "|" + safe(post.coverImage) + "|" + safe(post.author) + "|" + safe(post.status) + "|" + safe(post.synopsis) + "|" + safe(post.genre) + "|" + safe(post.getTypeLabel()) + "|" + safe(post.latestChapter) + "|" + safe(post.latestChapterDate) + "|" + post.totalChapters;
    }

    private void openDetail(UpdateItem item) {
        if (item == null || item.post == null) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_MANGA_DETAIL, item.post);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void updateEmpty(boolean done, boolean noFavorite) {
        if (emptyTextView == null) return;
        if (noFavorite && updateItems.isEmpty()) {
            emptyTextView.setText("Belum ada manga favorit");
            emptyTextView.setVisibility(View.VISIBLE);
            return;
        }
        if (done && updateItems.isEmpty()) {
            emptyTextView.setText("Tidak ada chapter terbaru");
            emptyTextView.setVisibility(View.VISIBLE);
            return;
        }
        emptyTextView.setVisibility(updateItems.isEmpty() && !checking ? View.VISIBLE : View.GONE);
    }

    private void setRefreshing(boolean refreshing) {
        MikuUpdate target = visibleActivity == null ? this : visibleActivity;
        if (target.swipeRefreshLayout != null) target.swipeRefreshLayout.setRefreshing(refreshing);
    }

    private void setStatus(String text) {
        String value = text == null ? "" : text;
        if (checking) runningStatusText = value;
        MikuUpdate target = visibleActivity == null ? this : visibleActivity;
        if (target.statusTextView != null) target.statusTextView.setText(value);
    }

    private String initialStatusText() {
        if (checking && !runningStatusText.trim().isEmpty()) return runningStatusText;
        long lastCheckAt = prefs().getLong(KEY_LAST_CHECK_AT, 0L);
        int lastFound = prefs().getInt(KEY_LAST_CHECK_FOUND, -1);
        if (lastCheckAt > 0 && lastFound >= 0 && makeDayKey(lastCheckAt).equals(makeDayKey(System.currentTimeMillis()))) {
            return updateStatusTextAfterCheck(lastFound);
        }
        if (updateItems.isEmpty()) return "Tekan tombol update untuk mengecek favorite manga";
        return "History update tersimpan";
    }

    private String updateStatusTextAfterCheck(int found) {
        if (found > 0) return found + " manga punya chapter terbaru";
        return "Tidak ada chapter terbaru dari check terakhir";
    }

    private void persistLastCheckResult(int found) {
        prefs().edit().putInt(KEY_LAST_CHECK_FOUND, Math.max(0, found)).putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String updateKey(MangaPost post) {
        if (post == null) return "";
        return safe(post.getSourceId()) + "|" + safe(post.slug).trim();
    }

    private void sortUpdateItems() {
        Collections.sort(updateItems, new Comparator<UpdateItem>() {
            @Override public int compare(UpdateItem a, UpdateItem b) {
                long diff = b.checkedAt - a.checkedAt;
                if (diff > 0) return 1;
                if (diff < 0) return -1;
                String at = a.post == null ? "" : safe(a.post.title).toLowerCase(Locale.ROOT);
                String bt = b.post == null ? "" : safe(b.post.title).toLowerCase(Locale.ROOT);
                return at.compareTo(bt);
            }
        });
    }

    private ArrayList<UpdateItem> loadStoredUpdates() {
        ArrayList<UpdateItem> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs().getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                MangaPost post = postFromJson(object.optJSONObject("post"));
                if (post == null || post.slug == null || post.slug.trim().isEmpty()) continue;
                String key = object.optString("key", updateKey(post));
                String chapterLabel = object.optString("chapterLabel", post.latestChapter);
                long checkedAt = object.optLong("checkedAt", System.currentTimeMillis());
                int added = Math.max(1, object.optInt("added", 1));
                list.add(new UpdateItem(key, post, chapterLabel, checkedAt, added));
            }
        } catch(Exception ignored) {}
        return list;
    }

    private void persistUpdateItems() {
        try {
            JSONArray array = new JSONArray();
            for (UpdateItem item : updateItems) {
                if (item == null || item.post == null || item.post.slug == null || item.post.slug.trim().isEmpty()) continue;
                JSONObject object = new JSONObject();
                object.put("key", item.key);
                object.put("post", postToJson(item.post));
                object.put("chapterLabel", item.chapterLabel);
                object.put("checkedAt", item.checkedAt);
                object.put("added", Math.max(1, item.added));
                array.put(object);
            }
            prefs().edit().putString(KEY_ITEMS, array.toString()).apply();
        } catch(Exception ignored) {}
    }

    private JSONObject postToJson(MangaPost post) throws Exception {
        JSONObject object = new JSONObject();
        object.put("slug", post.slug);
        object.put("sourceId", post.getSourceId());
        object.put("sourceLabel", post.getSourceLabel());
        object.put("title", post.title);
        object.put("cover", post.coverImage);
        object.put("author", post.author);
        object.put("status", post.status);
        object.put("synopsis", post.synopsis);
        object.put("genre", post.genre);
        object.put("typeLabel", post.getTypeLabel());
        object.put("latestChapter", post.latestChapter);
        object.put("latestChapterDate", post.latestChapterDate);
        object.put("totalChapters", Math.max(0, post.totalChapters));
        return object;
    }

    private MangaPost postFromJson(JSONObject object) {
        if (object == null) return null;
        String sourceId = object.optString("sourceId", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
        MangaPost post = new MangaPost(object.optString("slug"), object.optString("title"), object.optString("cover"), object.optString("author"), object.optString("status"), object.optString("synopsis"), object.optString("genre"), object.optString("typeLabel"), object.optString("latestChapter"), object.optString("latestChapterDate")).withSource(sourceId, object.optString("sourceLabel", MangaSourceFactory.labelForSourceId(sourceId)));
        post.totalChapters = object.optInt("totalChapters", 0);
        return post;
    }

    private JSONObject loadBaselines() {
        try {
            return new JSONObject(baselinePrefs().getString(KEY_BASELINES, "{}"));
        } catch(Exception ignored) {
            return new JSONObject();
        }
    }

    private void persistBaselines() {
        try {
            baselinePrefs().edit().putString(KEY_BASELINES, updateBaselines.toString()).apply();
        } catch(Exception ignored) {}
    }

    private Baseline getBaseline(String key) {
        if (key == null || key.trim().isEmpty()) return null;
        try {
            JSONObject object = updateBaselines.optJSONObject(key);
            if (object == null) return null;
            return new Baseline(object.optDouble("chapterIndex", -1d), object.optInt("totalChapters", 0), object.optString("chapterLabel", ""), object.optString("fingerprint", ""));
        } catch(Exception ignored) {
            return null;
        }
    }

    private void updateBaseline(String key, MangaPost post, ArrayList<MangaChapter> chapters) {
        if (key == null || key.trim().isEmpty()) return;
        MangaChapter newest = newestChapter(chapters);
        float chapterIndex = newest == null ? parseChapterIndex(post == null ? "" : post.latestChapter) : newest.index;
        int total = chapters == null ? favoriteChapterTotal(post) : chapters.size();
        String label = newest == null ? safe(post == null ? "" : post.latestChapter) : "Chapter " + MangaChapter.formatIndex(newest.index);
        try {
            JSONObject object = new JSONObject();
            object.put("chapterIndex", chapterIndex);
            object.put("totalChapters", Math.max(0, total));
            object.put("chapterLabel", label);
            object.put("fingerprint", chapterFingerprint(chapters, newest));
            object.put("checkedAt", System.currentTimeMillis());
            updateBaselines.put(key, object);
        } catch(Exception ignored) {}
    }

    private String chapterFingerprint(ArrayList<MangaChapter> chapters, MangaChapter newest) {
        StringBuilder builder = new StringBuilder();
        builder.append(chapters == null ? 0 : chapters.size()).append('|');
        if (newest != null) {
            builder.append(newest.index).append('|');
            builder.append(safe(newest.title)).append('|');
            builder.append(safe(newest.slug)).append('|');
            builder.append(safe(newest.date));
        }
        return builder.toString();
    }

    private SharedPreferences baselinePrefs() {
        return getApplicationContext().getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE);
    }

    private SharedPreferences prefs() {
        return getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void setupSystemBars() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        int surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, ContextCompat.getColor(this, R.color.md_theme_surface));
        window.setStatusBarColor(surfaceColor);
        window.setNavigationBarColor(surfaceColor);
        boolean isNightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!isNightMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private static String makeDayKey(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp <= 0 ? System.currentTimeMillis() : timestamp);
        return calendar.get(Calendar.YEAR) + "-" + calendar.get(Calendar.DAY_OF_YEAR);
    }

    private static String makeDayLabel(long timestamp) {
        long diffDays = dayDiff(timestamp <= 0 ? System.currentTimeMillis() : timestamp);
        if (diffDays <= 0) return "Hari ini";
        if (diffDays == 1) return "Kemarin";
        return diffDays + " hari yang lalu";
    }

    private static long dayDiff(long timestamp) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(timestamp);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        return Math.max(0L, (today.getTimeInMillis() - start.getTimeInMillis()) / 86400000L);
    }

    private static class Baseline {
        final float chapterIndex;
        final int totalChapters;
        final String chapterLabel;
        final String fingerprint;

        Baseline(double chapterIndex, int totalChapters, String chapterLabel, String fingerprint) {
            this.chapterIndex = (float) chapterIndex;
            this.totalChapters = Math.max(0, totalChapters);
            this.chapterLabel = chapterLabel == null ? "" : chapterLabel;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
        }
    }

    private static class UpdateItem {
        final String key;
        final MangaPost post;
        final String chapterLabel;
        final long checkedAt;
        final int added;

        UpdateItem(String key, MangaPost post, String chapterLabel, long checkedAt, int added) {
            this.key = key == null ? "" : key;
            this.post = post;
            this.chapterLabel = chapterLabel == null ? "" : chapterLabel;
            this.checkedAt = checkedAt <= 0 ? System.currentTimeMillis() : checkedAt;
            this.added = Math.max(1, added);
        }
    }

    private static class Row {
        static final int TYPE_HEADER = 0;
        static final int TYPE_ITEM = 1;
        final int type;
        final String header;
        final UpdateItem item;

        Row(String header) {
            this.type = TYPE_HEADER;
            this.header = header;
            this.item = null;
        }

        Row(UpdateItem item) {
            this.type = TYPE_ITEM;
            this.header = "";
            this.item = item;
        }
    }

    private static class UpdateAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        interface Listener { void onClick(UpdateItem item); }
        private final ArrayList<UpdateItem> items;
        private final ArrayList<Row> rows = new ArrayList<>();
        private final Listener listener;

        UpdateAdapter(ArrayList<UpdateItem> items, Listener listener) {
            this.items = items;
            this.listener = listener;
            rebuildRows();
        }

        void refreshData() {
            rebuildRows();
            notifyDataSetChanged();
        }

        private void rebuildRows() {
            rows.clear();
            String lastKey = "";
            for (UpdateItem item : items) {
                if (item == null) continue;
                String key = makeDayKey(item.checkedAt);
                if (!key.equals(lastKey)) {
                    rows.add(new Row(makeDayLabel(item.checkedAt)));
                    lastKey = key;
                }
                rows.add(new Row(item));
            }
        }

        @Override public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == Row.TYPE_HEADER) return new HeaderHolder(inflater.inflate(R.layout.history_day_header, parent, false));
            return new Holder(inflater.inflate(R.layout.manga_update_item, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).text.setText(row.header);
                return;
            }
            Holder itemHolder = (Holder) holder;
            UpdateItem item = row.item;
            MangaPost post = item == null ? null : item.post;
            itemHolder.title.setText(post == null ? "" : post.title);
            itemHolder.chapter.setText(item == null || item.chapterLabel.trim().isEmpty() ? "Chapter" : item.chapterLabel);
            itemHolder.count.setText(item == null || item.added <= 1 ? "1 chapter baru" : item.added + " chapter baru");
            itemHolder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(item);
            });
            if (post != null) {
                MangaImageLoader.loadCoverForSource(itemHolder.cover, post.coverImage, post.getSourceId());
                itemHolder.sourceBadge.setVisibility(View.VISIBLE);
                itemHolder.sourceBadge.setContentDescription(post.getSourceLabel());
                MangaImageLoader.loadForSource(itemHolder.sourceBadge, MangaSourceFactory.iconForSourceId(post.getSourceId()), post.getSourceId(), false, null);
            } else {
                itemHolder.cover.setImageDrawable(null);
                itemHolder.sourceBadge.setImageDrawable(null);
                itemHolder.sourceBadge.setVisibility(View.GONE);
            }
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        static class HeaderHolder extends RecyclerView.ViewHolder {
            final TextView text;

            HeaderHolder(@NonNull View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.dayHeaderTextView);
            }
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final ImageView sourceBadge;
            final TextView title;
            final TextView chapter;
            final TextView count;

            Holder(@NonNull View itemView) {
                super(itemView);
                cover = itemView.findViewById(R.id.coverImageView);
                sourceBadge = itemView.findViewById(R.id.sourceBadgeImageView);
                title = itemView.findViewById(R.id.mangaTitleTextView);
                chapter = itemView.findViewById(R.id.chapterLabelTextView);
                count = itemView.findViewById(R.id.newCountTextView);
            }
        }
    }
}
