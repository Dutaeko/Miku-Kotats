package miku.moe.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public class MangaFavoriteFragment extends Fragment {
    private final ArrayList<MangaPost> favorites = new ArrayList<>();
    private MangaGridAdapter adapter;
    private TextView emptyTextView;
    private GridView gridView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ActivityResultLauncher<Intent> exportLauncher, importLauncher;
    private SharedPreferences favoritePreferences;
    private static final long MIN_FAVORITE_REFRESH_MS = 900L;
    private static final String DETAIL_CACHE_PREFS = "miku_manga_detail_cache";
    private static final long AUTO_FAVORITE_REFRESH_DELAY_MS = 250L;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean refreshingFavoriteData = false;
    private boolean pendingForcedFavoriteRefresh = false;
    private long favoriteRefreshStartedAt = 0L;
    private boolean suppressFavoriteReload = false;
    private boolean favoriteCoverReloadPending = false;
    private boolean pendingAutoFavoriteRefresh = false;
    private boolean pendingAutoFavoriteForceNetwork = false;
    private final Map<String, ChapterIncrease> favoriteChapterIncreases = new HashMap<>();
    private final LinkedHashMap<String, FavoriteChapterUpdate> favoriteChapterUpdates = new LinkedHashMap<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener favoriteChangeListener = (prefs, key) -> {
        if ("items".equals(key) && !suppressFavoriteReload) loadFavorites(false);
    };

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        exportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> { if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) writeExport(result.getData().getData()); });
        importLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> { if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) readImport(result.getData().getData()); });
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manga_favorite, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        swipeRefreshLayout = view.findViewById(R.id.favoriteSwipeRefreshLayout);
        gridView = view.findViewById(R.id.gridView);
        gridView.setNumColumns(MangaSettingsManager.getMangaGridColumns(requireContext()));
        gridView.setSmoothScrollbarEnabled(true);
        gridView.setScrollingCacheEnabled(false);
        gridView.setAnimationCacheEnabled(false);
        gridView.setCacheColorHint(0x00000000);
        gridView.setRecyclerListener(scrapView -> { });
        emptyTextView = view.findViewById(R.id.emptyTextView);
        adapter = new MangaGridAdapter(requireContext(), favorites, post -> openFavoriteDetail(post), true, post -> openLatestFavoriteChapter(post), false);
        adapter.setIkiruStyle(true);
        adapter.setStripChapterPrefix(true);
        adapter.setChapterInsideCover(true);
        gridView.setAdapter(adapter);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(this::refreshFavoriteLocalFromSwipe);
        loadFavorites(false);
    }

    @Override public void onStart() {
        super.onStart();
        favoritePreferences = requireContext().getApplicationContext().getSharedPreferences("miku_manga_favorites", Context.MODE_PRIVATE);
        favoritePreferences.registerOnSharedPreferenceChangeListener(favoriteChangeListener);
        loadFavorites(false);
    }

    @Override public void onResume() { super.onResume(); if (gridView != null) gridView.setNumColumns(MangaSettingsManager.getMangaGridColumns(requireContext())); loadFavorites(false); }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            if (gridView != null) gridView.setNumColumns(MangaSettingsManager.getMangaGridColumns(requireContext()));
            loadFavorites(false);
        }
    }

    @Override public void onPause() {
        super.onPause();
    }

    @Override public void onStop() {
        if (favoritePreferences != null) favoritePreferences.unregisterOnSharedPreferenceChangeListener(favoriteChangeListener);
        super.onStop();
    }

    public void refreshFavorites() { loadFavorites(false); }

    private void refreshFavoriteLocalFromSwipe() {
        loadFavorites(false);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
    }

    public void refreshFavoriteFromHeader() {
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(true);
        refreshFavoriteData(true);
    }

    public void exportFavoriteFromHeader() { exportFavorites(); }

    public void importFavoriteFromHeader() { importFavorites(); }

    public void openUpdateFromHeader() { startActivity(new Intent(requireContext(), MikuUpdate.class)); }

    @Override public void onDestroyView() {
        if (gridView != null) {
            gridView.setOnScrollListener(null);
            gridView.setRecyclerListener(null);
            gridView.setAdapter(null);
        }
        gridView = null;
        adapter = null;
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(null);
        swipeRefreshLayout = null;
        refreshHandler.removeCallbacksAndMessages(null);
        favoriteCoverReloadPending = false;
        pendingAutoFavoriteRefresh = false;
        pendingAutoFavoriteForceNetwork = false;
        super.onDestroyView();
    }

    private void exportFavorites() { Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/javascript"); intent.putExtra(Intent.EXTRA_TITLE, "miku_manga_favorite_backup.js"); exportLauncher.launch(intent); }
    private void importFavorites() { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); importLauncher.launch(intent); }
    private void writeExport(Uri uri) { try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) { out.write(MangaFavoriteManager.exportEncrypted(requireContext()).getBytes(StandardCharsets.UTF_8)); Toast.makeText(requireContext(), "Favorite manga diekspor", Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(requireContext(), "Favorite manga gagal diekspor", Toast.LENGTH_SHORT).show(); } }
    private void readImport(Uri uri) { try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) { ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) != -1) bos.write(buf, 0, n); MangaFavoriteManager.importEncrypted(requireContext(), new String(bos.toByteArray(), StandardCharsets.UTF_8)); loadFavorites(false); Toast.makeText(requireContext(), "Favorite manga berhasil diimport", Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(requireContext(), "File import manga tidak valid", Toast.LENGTH_SHORT).show(); } }
    private void loadFavorites() { loadFavorites(false); }

    private void loadFavorites(boolean syncNetwork) {
        if (!isAdded()) return;
        favorites.clear();
        favorites.addAll(MangaFavoriteManager.getFavorites(requireContext()));
        boolean localChanged = hydrateFavoriteLabelsFromLocalCache();
        applyStoredFavoriteChapterIncreases();
        autoSaveFavoriteImages();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyTextView != null) emptyTextView.setVisibility(favorites.isEmpty() ? View.VISIBLE : View.GONE);
        if (localChanged) saveFavoritesSilently();
        if (syncNetwork) scheduleFavoriteDataRefresh(true);
    }

    private boolean hydrateFavoriteLabelsFromLocalCache() {
        if (!isAdded() || favorites.isEmpty()) return false;
        SharedPreferences prefs = requireContext().getApplicationContext().getSharedPreferences(DETAIL_CACHE_PREFS, Context.MODE_PRIVATE);
        boolean changed = false;
        for (MangaPost post : favorites) {
            if (post == null || empty(post.slug)) continue;
            String raw = prefs.getString(post.getSourceId() + "_" + post.slug, "");
            if (empty(raw)) continue;
            try {
                JSONObject root = new JSONObject(raw);
                JSONObject manga = root.optJSONObject("manga");
                if (manga != null) {
                    changed |= fillIfEmpty(post, "title", manga.optString("title", ""));
                    changed |= fillIfEmpty(post, "cover", manga.optString("coverImage", ""));
                    changed |= fillIfEmpty(post, "author", manga.optString("author", ""));
                    changed |= fillIfEmpty(post, "status", manga.optString("status", ""));
                    changed |= fillIfEmpty(post, "synopsis", manga.optString("synopsis", ""));
                    changed |= fillIfEmpty(post, "genre", manga.optString("genre", ""));
                    changed |= fillIfEmpty(post, "type", manga.optString("typeLabel", ""));
                    changed |= fillIfEmpty(post, "latest", manga.optString("latestChapter", ""));
                    changed |= fillIfEmpty(post, "date", manga.optString("latestChapterDate", ""));
                    int cachedTotal = manga.optInt("totalChapters", 0);
                    if (cachedTotal > post.totalChapters) {
                        post.totalChapters = cachedTotal;
                        changed = true;
                    }
                }
                JSONArray chapters = root.optJSONArray("chapters");
                if (chapters != null && chapters.length() > 0) changed |= applyCachedLatestChapter(post, chapters);
            } catch (Exception ignored) {}
        }
        return changed;
    }

    private boolean applyCachedLatestChapter(MangaPost post, JSONArray chapters) {
        float newestIndex = -1f;
        String newestDate = "";
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject item = chapters.optJSONObject(i);
            if (item == null) continue;
            float index = (float) item.optDouble("index", -1d);
            if (index > newestIndex) {
                newestIndex = index;
                newestDate = item.optString("date", "");
            }
        }
        boolean changed = false;
        if (chapters.length() > post.totalChapters) {
            post.totalChapters = chapters.length();
            changed = true;
        }
        if (newestIndex >= 0f) {
            float currentIndex = parseChapterIndex(post.latestChapter);
            if (empty(post.latestChapter) || currentIndex < newestIndex) {
                post.latestChapter = "Chapter " + MangaChapter.formatIndex(newestIndex);
                changed = true;
            }
            if (!empty(newestDate) && empty(post.latestChapterDate)) {
                post.latestChapterDate = newestDate;
                changed = true;
            }
        }
        return changed;
    }

    private boolean fillIfEmpty(MangaPost post, String field, String value) {
        if (empty(value)) return false;
        if ("title".equals(field) && empty(post.title)) { post.title = value; return true; }
        if ("cover".equals(field) && empty(post.coverImage)) { post.coverImage = value; return true; }
        if ("author".equals(field) && empty(post.author)) { post.author = value; return true; }
        if ("status".equals(field) && empty(post.status)) { post.status = value; return true; }
        if ("synopsis".equals(field) && empty(post.synopsis)) { post.synopsis = value; return true; }
        if ("genre".equals(field) && empty(post.genre)) { post.genre = value; return true; }
        if ("type".equals(field) && empty(post.typeLabel)) { post.typeLabel = value; return true; }
        if ("latest".equals(field) && empty(post.latestChapter)) { post.latestChapter = value; return true; }
        if ("date".equals(field) && empty(post.latestChapterDate)) { post.latestChapterDate = value; return true; }
        return false;
    }

    private void saveFavoritesSilently() {
        if (!isAdded()) return;
        suppressFavoriteReload = true;
        try {
            MangaFavoriteManager.saveFavorites(requireContext(), favorites);
        } finally {
            suppressFavoriteReload = false;
        }
    }

    private void scheduleFavoriteDataRefresh(boolean forceNetwork) {
        if (!isAdded() || favorites.isEmpty()) return;
        if (forceNetwork) pendingAutoFavoriteForceNetwork = true;
        if (refreshingFavoriteData) {
            if (forceNetwork) pendingForcedFavoriteRefresh = true;
            return;
        }
        if (pendingAutoFavoriteRefresh) return;
        pendingAutoFavoriteRefresh = true;
        refreshHandler.postDelayed(() -> {
            pendingAutoFavoriteRefresh = false;
            boolean shouldForce = pendingAutoFavoriteForceNetwork;
            pendingAutoFavoriteForceNetwork = false;
            if (isAdded()) refreshFavoriteData(shouldForce);
        }, AUTO_FAVORITE_REFRESH_DELAY_MS);
    }

    private void autoSaveFavoriteImages() {
        if (!isAdded() || !MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(requireContext())) return;
        Context app = requireContext().getApplicationContext();
        for (MangaPost post : favorites) {
            if (post != null && post.coverImage != null && !post.coverImage.trim().isEmpty()) MangaCoverCache.saveAsync(app, post.coverImage, post.getSourceId(), saved -> { if (saved) scheduleFavoriteCoverReload(); });
        }
    }

    private void scheduleFavoriteCoverReload() {
        if (!isAdded() || favoriteCoverReloadPending) return;
        favoriteCoverReloadPending = true;
        refreshHandler.postDelayed(() -> {
            favoriteCoverReloadPending = false;
            if (!isAdded() || adapter == null) return;
            adapter.notifyDataSetChanged();
        }, 160L);
    }


    private void refreshFavoriteData(boolean forceNetwork){
        if(!isAdded()) { setFavoriteRefreshing(false); return; }
        if(refreshingFavoriteData) {
            if(forceNetwork) pendingForcedFavoriteRefresh = true;
            setFavoriteRefreshing(true);
            return;
        }
        if(favorites.isEmpty()) { setFavoriteRefreshing(false); return; }
        refreshingFavoriteData = true;
        favoriteRefreshStartedAt = System.currentTimeMillis();
        setFavoriteRefreshing(true);
        clearFavoriteChapterIncreases();
        favoriteChapterUpdates.clear();
        if(forceNetwork) MangaMemoryCache.clearRegistered();
        ArrayList<MangaPost> snapshot = new ArrayList<>(favorites);
        refreshFavoriteAt(snapshot, 0, false);
    }

    private void setFavoriteRefreshing(boolean refreshing) {
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(refreshing);
    }

    private void finishFavoriteRefresh(){
        refreshingFavoriteData = false;
        if(pendingForcedFavoriteRefresh){
            pendingForcedFavoriteRefresh = false;
            refreshHandler.postDelayed(() -> {
                if(isAdded()) refreshFavoriteData(true);
            }, 120);
            return;
        }
        long elapsed = System.currentTimeMillis() - favoriteRefreshStartedAt;
        long delay = Math.max(0L, MIN_FAVORITE_REFRESH_MS - elapsed);
        refreshHandler.postDelayed(() -> {
            if(isAdded()) setFavoriteRefreshing(false);
        }, delay);
    }

    private void refreshFavoriteAt(ArrayList<MangaPost> snapshot, int index, boolean changed){
        if(!isAdded()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
        if(index >= snapshot.size()){
            finishFavoriteRefresh();
            if(changed){
                suppressFavoriteReload = true;
                MangaFavoriteManager.saveFavorites(requireContext(), favorites);
                refreshHandler.postDelayed(() -> suppressFavoriteReload = false, 500);
                if(adapter != null) adapter.notifyDataSetChanged();
            }
            showFavoriteUpdateDialogIfNeeded();
            return;
        }
        MangaPost oldPost = snapshot.get(index);
        if(oldPost == null || oldPost.slug == null || oldPost.slug.trim().isEmpty()){
            refreshFavoriteAt(snapshot, index + 1, changed);
            return;
        }
        KomikcastClient sourceApi = MangaSourceFactory.createBySourceId(oldPost.getSourceId());
        sourceApi.detail(oldPost.slug, new KomikcastClient.Result<MangaPost>(){
            @Override public void onSuccess(MangaPost fresh, boolean next){
                if(!isAdded()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
                MangaPost merged = mergeFavorite(oldPost, fresh);
                applyFavoriteHiddenLabels(merged);
                sourceApi.chapters(oldPost.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>(){
                    @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext){
                        if(!isAdded()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
                        int oldChapterTotal = favoriteChapterTotal(oldPost);
                        float oldChapterIndex = parseChapterIndex(oldPost.latestChapter);
                        applyLatestChapter(merged, chapters);
                        applyFavoriteChapterIncrease(merged, oldChapterTotal, oldChapterIndex, chapters);
                        boolean itemChanged = replaceFavorite(oldPost, merged);
                        refreshHandler.postDelayed(() -> refreshFavoriteAt(snapshot, index + 1, changed || itemChanged), 120);
                    }
                    @Override public void onError(String message){
                        if(!isAdded()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
                        boolean itemChanged = replaceFavorite(oldPost, merged);
                        refreshHandler.postDelayed(() -> refreshFavoriteAt(snapshot, index + 1, changed || itemChanged), 120);
                    }
                });
            }
            @Override public void onError(String message){
                if(!isAdded()) { refreshingFavoriteData = false; setFavoriteRefreshing(false); return; }
                refreshHandler.postDelayed(() -> refreshFavoriteAt(snapshot, index + 1, changed), 120);
            }
        });
    }

    private void clearFavoriteChapterIncreases(){
        boolean changed = !favoriteChapterIncreases.isEmpty();
        favoriteChapterIncreases.clear();
        for(MangaPost post : favorites){
            if(post != null && (post.favoriteChapterBase > 0 || post.favoriteChapterAdded > 0)){
                post.favoriteChapterBase = 0;
                post.favoriteChapterAdded = 0;
                changed = true;
            }
        }
        if(changed && adapter != null) adapter.notifyDataSetChanged();
    }

    private void applyStoredFavoriteChapterIncreases(){
        if(favoriteChapterIncreases.isEmpty()) return;
        for(MangaPost post : favorites){
            if(post == null) continue;
            ChapterIncrease increase = favoriteChapterIncreases.get(favoriteKey(post));
            if(increase != null){
                post.favoriteChapterBase = increase.base;
                post.favoriteChapterAdded = increase.added;
            }
        }
    }

    private MangaPost mergeFavorite(MangaPost oldPost, MangaPost fresh){
        MangaPost result = fresh == null ? oldPost : fresh;
        if(result.title == null || result.title.trim().isEmpty()) result.title = oldPost.title;
        if(result.coverImage == null || result.coverImage.trim().isEmpty()) result.coverImage = oldPost.coverImage;
        if(result.author == null || result.author.trim().isEmpty()) result.author = oldPost.author;
        if(result.status == null || result.status.trim().isEmpty()) result.status = oldPost.status;
        if(result.synopsis == null || result.synopsis.trim().isEmpty()) result.synopsis = oldPost.synopsis;
        if(result.genre == null || result.genre.trim().isEmpty()) result.genre = oldPost.genre;
        if(result.latestChapter == null || result.latestChapter.trim().isEmpty()) result.latestChapter = oldPost.latestChapter;
        if(result.latestChapterDate == null || result.latestChapterDate.trim().isEmpty()) result.latestChapterDate = oldPost.latestChapterDate;
        result.withSource(oldPost.getSourceId(), oldPost.getSourceLabel());
        result.totalChapters = Math.max(result.totalChapters, oldPost.totalChapters);
        result.favoriteChapterBase = 0;
        result.favoriteChapterAdded = 0;
        applyFavoriteHiddenLabels(result);
        return result;
    }


    private void applyFavoriteHiddenLabels(MangaPost post){
        if(post == null || getContext() == null) return;
        if(!MangaSettingsManager.shouldLoadTypeLabel(getContext())) post.typeLabel = "";
    }

    private void applyLatestChapter(MangaPost post, ArrayList<MangaChapter> chapters){
        if(post == null || chapters == null || chapters.isEmpty()) return;
        MangaChapter newest = chapters.get(0);
        for(MangaChapter chapter : chapters) if(chapter != null && chapter.index > newest.index) newest = chapter;
        post.latestChapter = "Chapter " + MangaChapter.formatIndex(newest.index);
        post.latestChapterDate = newest.date == null ? "" : newest.date;
        post.totalChapters = Math.max(post.totalChapters, chapters.size());
    }

    private int favoriteChapterTotal(MangaPost post){
        if(post == null) return 0;
        if(post.totalChapters > 0) return post.totalChapters;
        float chapterIndex = parseChapterIndex(post.latestChapter);
        if(chapterIndex > 0f) return Math.round(chapterIndex);
        return 0;
    }

    private void applyFavoriteChapterIncrease(MangaPost post, int oldChapterTotal, float oldChapterIndex, ArrayList<MangaChapter> chapters){
        if(post == null) return;
        int freshTotal = favoriteChapterTotal(post);
        int added = 0;
        float threshold = oldChapterIndex > 0f ? oldChapterIndex : oldChapterTotal;
        if(oldChapterTotal > 0 && freshTotal > oldChapterTotal) added = freshTotal - oldChapterTotal;
        if(added <= 0 && threshold > 0f) added = countNewChapters(chapters, threshold);
        if(added > 0){
            int base = oldChapterTotal > 0 ? oldChapterTotal : Math.round(threshold);
            post.favoriteChapterBase = base;
            post.favoriteChapterAdded = added;
            favoriteChapterIncreases.put(favoriteKey(post), new ChapterIncrease(base, added));
            favoriteChapterUpdates.put(favoriteKey(post), new FavoriteChapterUpdate(post, added, collectNewChapterTitles(chapters, threshold)));
        }
    }

    private int countNewChapters(ArrayList<MangaChapter> chapters, float threshold){
        if(chapters == null || chapters.isEmpty() || threshold <= 0f) return 0;
        int count = 0;
        for(MangaChapter chapter : chapters){
            if(chapter != null && chapter.index > threshold) count++;
        }
        return count;
    }

    private ArrayList<String> collectNewChapterTitles(ArrayList<MangaChapter> chapters, float threshold){
        ArrayList<String> titles = new ArrayList<>();
        if(chapters == null || chapters.isEmpty()) return titles;
        ArrayList<MangaChapter> sorted = new ArrayList<>(chapters);
        sorted.sort((a, b) -> Float.compare(b == null ? 0f : b.index, a == null ? 0f : a.index));
        for(MangaChapter chapter : sorted){
            if(chapter == null) continue;
            if(chapter.index > threshold){
                String title = chapter.title == null ? "" : chapter.title.trim();
                if(!title.isEmpty()) titles.add(title);
            }
            if(titles.size() >= 4) break;
        }
        return titles;
    }

    private void showFavoriteUpdateDialogIfNeeded(){
        if(!isAdded() || favoriteChapterUpdates.isEmpty()) return;
        Context context = requireContext();
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        GradientDrawable dialogBg = new GradientDrawable();
        dialogBg.setColor(0xF2181820);
        dialogBg.setCornerRadius(dp(24));
        scrollView.setBackground(dialogBg);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        container.setPadding(padding, padding, padding, padding);
        scrollView.addView(container, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        TextView titleView = new TextView(context);
        titleView.setText("Update Favorite");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(titleView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView descView = new TextView(context);
        descView.setText("Chapter baru ditemukan di favorite manga kamu.");
        descView.setTextColor(0xCCFFFFFF);
        descView.setTextSize(13);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descParams.setMargins(0, dp(4), 0, dp(12));
        container.addView(descView, descParams);
        for(FavoriteChapterUpdate update : favoriteChapterUpdates.values()) addFavoriteUpdateRow(context, container, update);
        TextView okView = new TextView(context);
        okView.setText("Oke");
        okView.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE));
        okView.setTextSize(15);
        okView.setTypeface(Typeface.DEFAULT_BOLD);
        okView.setGravity(Gravity.CENTER);
        int okVertical = dp(10);
        int okHorizontal = dp(24);
        okView.setPadding(okHorizontal, okVertical, okHorizontal, okVertical);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(themeColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF7C4DFF));
        okBg.setCornerRadius(dp(18));
        okView.setBackground(okBg);
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        okParams.gravity = Gravity.END;
        okParams.setMargins(0, dp(8), 0, 0);
        container.addView(okView, okParams);
        AlertDialog dialog = new AlertDialog.Builder(context).setView(scrollView).create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        okView.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnShowListener(d -> {
            if(dialog.getWindow() != null){
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        dialog.show();
        favoriteChapterUpdates.clear();
    }

    private void addFavoriteUpdateRow(Context context, LinearLayout container, FavoriteChapterUpdate update){
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int cardPadding = dp(10);
        card.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B1B22);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x22FFFFFF);
        card.setBackground(bg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(10));
        container.addView(card, cardParams);
        ImageView imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable imageBg = new GradientDrawable();
        imageBg.setColor(0x33333333);
        imageBg.setCornerRadius(dp(12));
        imageView.setBackground(imageBg);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(64), dp(86));
        card.addView(imageView, imageParams);
        if(update.post != null) MangaImageLoader.loadCoverForSource(imageView, update.post.coverImage, update.post.getSourceId());
        LinearLayout textBox = new LinearLayout(context);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMargins(dp(12), 0, 0, 0);
        card.addView(textBox, textParams);
        TextView mangaTitle = new TextView(context);
        mangaTitle.setText(update.post == null || update.post.title == null ? "" : update.post.title);
        mangaTitle.setTextColor(Color.WHITE);
        mangaTitle.setTextSize(15);
        mangaTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mangaTitle.setMaxLines(2);
        textBox.addView(mangaTitle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView countView = new TextView(context);
        countView.setText("Ditemukan " + update.added + " chapter baru");
        countView.setTextColor(0xFFE5D8FF);
        countView.setTextSize(13);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        countParams.setMargins(0, dp(5), 0, 0);
        textBox.addView(countView, countParams);
        if(update.chapters != null && !update.chapters.isEmpty()){
            TextView chapterView = new TextView(context);
            chapterView.setText(joinChapterTitles(update.chapters));
            chapterView.setTextColor(0xB3FFFFFF);
            chapterView.setTextSize(12);
            chapterView.setMaxLines(4);
            LinearLayout.LayoutParams chapterParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chapterParams.setMargins(0, dp(5), 0, 0);
            textBox.addView(chapterView, chapterParams);
        }
    }

    private String joinChapterTitles(ArrayList<String> titles){
        StringBuilder builder = new StringBuilder();
        for(int i=0;i<titles.size();i++){
            if(i > 0) builder.append("\n");
            builder.append(titles.get(i));
        }
        return builder.toString();
    }

    private int themeColor(Context context, int attr, int fallback){
        android.util.TypedValue value = new android.util.TypedValue();
        if(context != null && context.getTheme().resolveAttribute(attr, value, true)) return value.data;
        return fallback;
    }

    private int dp(int value){
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean replaceFavorite(MangaPost oldPost, MangaPost fresh){
        for(int i=0;i<favorites.size();i++){
            MangaPost current = favorites.get(i);
            if(current != null && current.slug.equals(oldPost.slug) && current.getSourceId().equals(oldPost.getSourceId())){
                String before = signature(current);
                favorites.set(i, fresh);
                boolean changed = !before.equals(signature(fresh));
                if(changed && adapter != null) adapter.notifyDataSetChanged();
                return changed;
            }
        }
        return false;
    }

    private void openFavoriteDetail(MangaPost post){
        clearFavoriteChapterIncreaseForPost(post);
        if(isAdded()) ((MainActivity) requireActivity()).openMangaDetail(post);
    }

    private void clearFavoriteChapterIncreaseForPost(MangaPost post){
        if(post == null) return;
        String key = favoriteKey(post);
        boolean changed = favoriteChapterIncreases.remove(key) != null;
        favoriteChapterUpdates.remove(key);
        for(MangaPost item : favorites){
            if(item != null && favoriteKey(item).equals(key) && (item.favoriteChapterBase > 0 || item.favoriteChapterAdded > 0)){
                item.favoriteChapterBase = 0;
                item.favoriteChapterAdded = 0;
                changed = true;
            }
        }
        if(post.favoriteChapterBase > 0 || post.favoriteChapterAdded > 0){
            post.favoriteChapterBase = 0;
            post.favoriteChapterAdded = 0;
            changed = true;
        }
        if(changed && adapter != null) adapter.notifyDataSetChanged();
    }

    private String favoriteKey(MangaPost post){
        if(post == null) return "";
        return post.getSourceId() + "|" + (post.slug == null ? "" : post.slug);
    }

    private String signature(MangaPost post){
        if(post == null) return "";
        return post.title + "|" + post.coverImage + "|" + post.author + "|" + post.status + "|" + post.synopsis + "|" + post.genre + "|" + post.getTypeLabel() + "|" + post.latestChapter + "|" + post.latestChapterDate + "|" + post.totalChapters + "|" + post.favoriteChapterBase + "|" + post.favoriteChapterAdded;
    }

    private void openLatestFavoriteChapter(MangaPost post){
        if(!isAdded() || post == null || post.slug == null || post.slug.trim().isEmpty()) return;
        KomikcastClient sourceApi = MangaSourceFactory.createBySourceId(post.getSourceId());
        sourceApi.chapters(post.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>(){
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext){
                if(!isAdded()) return;
                if(chapters == null || chapters.isEmpty()){
                    Toast.makeText(requireContext(), "Chapter belum tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                int pos = findChapterPosition(chapters, post.latestChapter);
                clearFavoriteChapterIncreaseForPost(post);
                ((MainActivity) requireActivity()).openMangaReader(post, new ArrayList<>(chapters), pos);
            }
            @Override public void onError(String message){
                if(!isAdded()) return;
                Toast.makeText(requireContext(), message == null || message.trim().isEmpty() ? "Gagal membuka chapter" : message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int findChapterPosition(ArrayList<MangaChapter> chapters, String latestChapter){
        float target = parseChapterIndex(latestChapter);
        if(target >= 0f){
            for(int i=0;i<chapters.size();i++) if(Math.abs(chapters.get(i).index - target) < 0.001f) return i;
        }
        int newest = 0;
        for(int i=1;i<chapters.size();i++) if(chapters.get(i).index > chapters.get(newest).index) newest = i;
        return newest;
    }

    private float parseChapterIndex(String text){
        if(text == null) return -1f;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text.replace(',', '.'));
        if(!matcher.find()) return -1f;
        try { return Float.parseFloat(matcher.group(1)); } catch(Exception e){ return -1f; }
    }

    private boolean empty(String value) { return value == null || value.trim().isEmpty(); }

    private static class FavoriteChapterUpdate {
        final MangaPost post;
        final int added;
        final ArrayList<String> chapters;

        FavoriteChapterUpdate(MangaPost post, int added, ArrayList<String> chapters){
            this.post = post;
            this.added = added;
            this.chapters = chapters;
        }
    }

    private static class ChapterIncrease {
        final int base;
        final int added;

        ChapterIncrease(int base, int added){
            this.base = base;
            this.added = added;
        }
    }
}
