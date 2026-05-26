package miku.moe.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.util.TypedValue;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MangaHistoryFragment extends Fragment {
    private RecyclerView historyRecyclerView;
    private HistoryListAdapter historyAdapter;
    private TextView emptyTextView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private KomikcastClient historyApi;
    private final Handler coverHandler = new Handler(Looper.getMainLooper());
    private boolean historyRefreshRunning = false;
    private boolean historyCoverReloadPending = false;
    private int historyRefreshToken = 0;
    private final HashSet<String> selectedHistoryKeys = new HashSet<>();
    private static final int HISTORY_ROW_HEADER = 1;
    private static final int HISTORY_ROW_ITEM = 2;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manga_history, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        historyApi = MangaSourceFactory.create(requireContext());
        historyRecyclerView = view.findViewById(R.id.historyRecyclerView);
        historyAdapter = new HistoryListAdapter();
        if (historyRecyclerView != null) {
            historyRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
            historyRecyclerView.setAdapter(historyAdapter);
            historyRecyclerView.setHasFixedSize(true);
            historyRecyclerView.setItemAnimator(null);
            historyRecyclerView.setItemViewCacheSize(8);
        }
        emptyTextView = view.findViewById(R.id.emptyTextView);
        swipeRefreshLayout = view.findViewById(R.id.historySwipeRefreshLayout);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(this::refreshStoredHistoryData);
        loadHistory();
    }

    @Override public void onResume() { super.onResume(); loadHistory(); }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) loadHistory();
    }

    public void reload() { loadHistory(); }

    public void refreshHistoryFromHeader() {
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(true);
        refreshStoredHistoryData();
    }

    public void clearHistoryFromHeader() {
        if (selectedHistoryKeys.isEmpty()) showClearAllDialog(); else showDeleteSelectedDialog();
    }

    public void syncHeaderActions() {
        boolean empty = MangaHistoryManager.entries(requireContext()).isEmpty();
        updateClearButton(empty);
    }

    @Override public void onDestroyView() {
        historyRefreshToken++;
        historyRefreshRunning = false;
        historyCoverReloadPending = false;
        coverHandler.removeCallbacksAndMessages(null);
        clearImages(historyRecyclerView);
        if (historyRecyclerView != null) historyRecyclerView.setAdapter(null);
        historyRecyclerView = null;
        historyAdapter = null;
        emptyTextView = null;
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(null);
        swipeRefreshLayout = null;
        super.onDestroyView();
    }


    private void loadHistory() {
        if (!isAdded() || historyRecyclerView == null || historyAdapter == null) return;
        ArrayList<MangaHistoryManager.Entry> rawItems = MangaHistoryManager.entries(requireContext());
        pruneSelectedHistory(rawItems);
        autoSaveHistoryImages(rawItems);
        Map<String, List<MangaHistoryManager.Entry>> grouped = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (MangaHistoryManager.Entry item : rawItems) {
            String key = makeDayKey(item.time);
            if (!grouped.containsKey(key)) { grouped.put(key, new ArrayList<>()); labels.put(key, makeDayLabel(item.time)); }
            grouped.get(key).add(item);
        }
        ArrayList<HistoryRow> rows = new ArrayList<>();
        for (String dayKey : grouped.keySet()) {
            rows.add(HistoryRow.header(labels.get(dayKey)));
            List<MangaHistoryManager.Entry> dayItems = grouped.get(dayKey);
            for (MangaHistoryManager.Entry item : dayItems) rows.add(HistoryRow.item(item));
        }
        historyAdapter.submit(rows);
        boolean empty = rawItems.isEmpty();
        if (emptyTextView != null) {
            emptyTextView.setText("Belum ada history baca\nBuka chapter manga, nanti progres bacamu muncul di sini.");
            emptyTextView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        updateClearButton(empty);
    }

    private void pruneSelectedHistory(ArrayList<MangaHistoryManager.Entry> items) {
        if (selectedHistoryKeys.isEmpty()) return;
        HashSet<String> visibleKeys = new HashSet<>();
        for (MangaHistoryManager.Entry item : items) visibleKeys.add(historyKey(item));
        selectedHistoryKeys.retainAll(visibleKeys);
    }

    private void autoSaveHistoryImages(ArrayList<MangaHistoryManager.Entry> items) {
        if (!isAdded() || items == null || !MangaSettingsManager.isAutoSaveFavoriteHistoryImagesEnabled(requireContext())) return;
        Context app = requireContext().getApplicationContext();
        for (MangaHistoryManager.Entry item : items) {
            if (item != null && item.manga != null && item.manga.coverImage != null && !item.manga.coverImage.trim().isEmpty()) MangaCoverCache.saveAsync(app, item.manga.coverImage, item.manga.getSourceId(), saved -> { if (saved) scheduleHistoryCoverReload(); });
        }
    }

    private void scheduleHistoryCoverReload() {
        if (!isAdded() || historyCoverReloadPending) return;
        historyCoverReloadPending = true;
        coverHandler.postDelayed(() -> {
            historyCoverReloadPending = false;
            if (!isAdded() || historyRecyclerView == null) return;
            loadHistory();
        }, 180L);
    }

    private void refreshStoredHistoryData() {
        Context context = getContext();
        if (context == null || !isAdded() || historyRefreshRunning || !MangaSettingsManager.isMangaModeEnabled(context)) { setHistoryRefreshing(false); return; }
        ArrayList<MangaHistoryManager.Entry> items = MangaHistoryManager.entries(context);
        if (items.isEmpty()) { setHistoryRefreshing(false); return; }
        historyRefreshRunning = true;
        int token = ++historyRefreshToken;
        refreshStoredHistoryItem(items, 0, token);
    }

    private void setHistoryRefreshing(boolean refreshing) {
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(refreshing);
    }

    private void refreshStoredHistoryItem(ArrayList<MangaHistoryManager.Entry> items, int index, int token) {
        Context context = getContext();
        if (context == null || !isAdded() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(context)) { historyRefreshRunning = false; setHistoryRefreshing(false); return; }
        if (index >= items.size()) {
            historyRefreshRunning = false;
            loadHistory();
            setHistoryRefreshing(false);
            return;
        }
        MangaHistoryManager.Entry item = items.get(index);
        if (item == null || item.manga == null || item.manga.slug == null || item.manga.slug.trim().isEmpty()) {
            refreshStoredHistoryItem(items, index + 1, token);
            return;
        }
        KomikcastClient api = MangaSourceFactory.createFor(item.manga, context);
        final MangaPost[] freshPost = new MangaPost[1];
        api.detail(item.manga.slug, new KomikcastClient.Result<MangaPost>() {
            @Override public void onSuccess(MangaPost data, boolean hasNext) {
                Context callbackContext = getContext();
                if (callbackContext == null || !isAdded() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(callbackContext)) { historyRefreshRunning = false; setHistoryRefreshing(false); return; }
                freshPost[0] = data == null ? item.manga : data.withSource(item.manga.getSourceId(), item.manga.getSourceLabel());
                MangaLabelUtils.applyHiddenLabels(callbackContext, freshPost[0]);
                if (!MangaSettingsManager.shouldLoadLatestChapterLabel(callbackContext)) {
                    MangaHistoryManager.updateEntry(callbackContext, item, freshPost[0], null, 0);
                    refreshStoredHistoryItem(items, index + 1, token);
                    return;
                }
                refreshStoredHistoryChapter(api, items, index, item, freshPost[0], token);
            }
            @Override public void onError(String message) {
                Context callbackContext = getContext();
                if (callbackContext == null || !isAdded() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(callbackContext)) { historyRefreshRunning = false; setHistoryRefreshing(false); return; }
                refreshStoredHistoryChapter(api, items, index, item, item.manga, token);
            }
        });
    }

    private void refreshStoredHistoryChapter(KomikcastClient api, ArrayList<MangaHistoryManager.Entry> items, int index, MangaHistoryManager.Entry item, MangaPost freshPost, int token) {
        api.chapters(item.manga.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                MangaChapter matched = null;
                if (chapters != null) {
                    for (MangaChapter chapter : chapters) {
                        if (chapter != null && Math.abs(chapter.index - item.chapterIndex) < 0.001f) { matched = chapter; break; }
                    }
                }
                Context callbackContext = getContext();
                if (callbackContext == null || !isAdded() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(callbackContext)) { historyRefreshRunning = false; setHistoryRefreshing(false); return; }
                MangaHistoryManager.updateEntry(callbackContext, item, freshPost, matched, chapters == null ? 0 : chapters.size());
                refreshStoredHistoryItem(items, index + 1, token);
            }
            @Override public void onError(String message) {
                Context callbackContext = getContext();
                if (callbackContext == null || !isAdded() || token != historyRefreshToken || !MangaSettingsManager.isMangaModeEnabled(callbackContext)) { historyRefreshRunning = false; setHistoryRefreshing(false); return; }
                MangaHistoryManager.updateEntry(callbackContext, item, freshPost, null, 0);
                refreshStoredHistoryItem(items, index + 1, token);
            }
        });
    }

    private void bindHistoryCard(View itemView, MangaHistoryManager.Entry item) {
        ImageView image = itemView.findViewById(R.id.imageView);
        TextView title = itemView.findViewById(R.id.titleTextView);
        TextView meta = itemView.findViewById(R.id.metaTextView);
        ImageView sourceBadge = itemView.findViewById(R.id.sourceBadgeImageView);
        TextView badge = itemView.findViewById(R.id.historyBadgeTextView);
        ProgressBar bar = itemView.findViewById(R.id.watchProgress);
        View detailClickArea = itemView.findViewById(R.id.detailClickArea);
        MaterialButton detailButton = itemView.findViewById(R.id.detailButton);
        MaterialButton continueButton = itemView.findViewById(R.id.continueButton);
        String itemKey = historyKey(item);
        applySelectionStyle(itemView, itemKey);
        MangaImageLoader.loadCoverForSource(image, item.manga.coverImage, item.manga.getSourceId());
        title.setText(item.manga.title == null || item.manga.title.trim().isEmpty() ? "Manga" : item.manga.title);
        MangaTitleStyle.apply(title, itemView.getContext());
        if (sourceBadge != null) {
            sourceBadge.setVisibility(View.VISIBLE);
            sourceBadge.setContentDescription(item.manga.getSourceLabel());
            MangaImageLoader.loadForSource(sourceBadge, MangaSourceFactory.iconForSourceId(item.manga.getSourceId()), item.manga.getSourceId(), false, null);
        }
        if (badge != null) {
            badge.setVisibility(View.VISIBLE);
            badge.setText(formatChapterBadge(item.chapterTitle));
        }
        int percent = item.totalPages > 0 ? Math.max(0, Math.min(100, ((item.page + 1) * 100) / item.totalPages)) : 0;
        bar.setProgress(percent);
        String time = formatRelativeReadTime(item.time);
        String total = item.manga.totalChapters > 0 ? " • Total " + item.manga.totalChapters + " chapter" : "";
        meta.setText("Hal. " + (item.page + 1) + "/" + item.totalPages + " • " + percent + "% dibaca • " + time + total);
        View.OnClickListener openDetail = v -> { if (!selectedHistoryKeys.isEmpty()) { toggleSelection(item); return; } if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).openMangaDetail(item.manga); };
        View.OnClickListener continueRead = v -> { if (!selectedHistoryKeys.isEmpty()) toggleSelection(item); else openReader(item); };
        itemView.setOnLongClickListener(v -> { toggleSelection(item); return true; });
        itemView.setOnClickListener(openDetail);
        detailClickArea.setOnLongClickListener(v -> { toggleSelection(item); return true; });
        detailClickArea.setOnClickListener(openDetail);
        if (detailButton != null) detailButton.setOnClickListener(openDetail);
        if (continueButton != null) continueButton.setOnClickListener(continueRead);
    }

    private void toggleSelection(MangaHistoryManager.Entry item) {
        String key = historyKey(item);
        if (key.isEmpty()) return;
        if (selectedHistoryKeys.contains(key)) selectedHistoryKeys.remove(key); else selectedHistoryKeys.add(key);
        loadHistory();
    }

    private String historyKey(MangaHistoryManager.Entry item) {
        if (item == null || item.manga == null || item.manga.slug == null) return "";
        String itemKey = item.key == null ? "" : item.key.trim();
        if (!itemKey.isEmpty()) return itemKey;
        String slug = item.manga.slug.trim();
        if (slug.isEmpty()) return "";
        return item.manga.getSourceId() + ":" + slug + ":" + makeDayKey(item.time);
    }

    private void applySelectionStyle(View card, String key) {
        if (!(card instanceof MaterialCardView)) return;
        MaterialCardView materialCard = (MaterialCardView) card;
        boolean selected = selectedHistoryKeys.contains(key);
        materialCard.setStrokeWidth(dp(selected ? 3 : 1));
        materialCard.setStrokeColor(resolveThemeColor(selected ? com.google.android.material.R.attr.colorPrimary : com.google.android.material.R.attr.colorSurfaceContainerHighest));
        materialCard.setCardBackgroundColor(resolveThemeColor(selected ? com.google.android.material.R.attr.colorSecondaryContainer : com.google.android.material.R.attr.colorSurfaceContainer));
    }

    private int resolveThemeColor(int attr) {
        TypedValue typedValue = new TypedValue();
        if (requireContext().getTheme().resolveAttribute(attr, typedValue, true)) {
            if (typedValue.resourceId != 0) return ContextCompat.getColor(requireContext(), typedValue.resourceId);
            return typedValue.data;
        }
        return 0;
    }

    private void updateClearButton(boolean empty) {
        Fragment parent = getParentFragment();
        if (parent instanceof MangaFavoriteTabsFragment) ((MangaFavoriteTabsFragment) parent).updateHistoryActionState(empty, selectedHistoryKeys.size());
    }

    private void showDeleteSelectedDialog() {
        if (!isAdded() || selectedHistoryKeys.isEmpty()) return;
        int count = selectedHistoryKeys.size();
        new MaterialAlertDialogBuilder(requireContext())
                .setIcon(R.drawable.ic_delete)
                .setTitle("Hapus history terpilih?")
                .setMessage(count + " history manga yang dipilih akan dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> deleteSelectedHistory())
                .show();
    }

    private void deleteSelectedHistory() {
        if (!isAdded() || selectedHistoryKeys.isEmpty()) return;
        MangaHistoryManager.delete(requireContext(), new HashSet<>(selectedHistoryKeys));
        selectedHistoryKeys.clear();
        loadHistory();
    }

    private void showClearAllDialog() {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setIcon(R.drawable.ic_delete)
                .setTitle("Hapus semua history manga?")
                .setMessage("Semua history manga akan dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (dialog, which) -> {
                    MangaHistoryManager.clear(requireContext());
                    selectedHistoryKeys.clear();
                    loadHistory();
                })
                .show();
    }


    private String formatChapterBadge(String chapterTitle) {
        if (chapterTitle == null || chapterTitle.trim().isEmpty()) return "Chapter -";
        String clean = chapterTitle.trim();
        if (clean.toLowerCase(java.util.Locale.ROOT).startsWith("chapter")) return clean;
        return "Chapter " + clean;
    }

    private void openReader(MangaHistoryManager.Entry item) {
        if (!isAdded() || item == null || item.manga == null || item.manga.slug == null || item.manga.slug.isEmpty()) return;
        MangaSourceFactory.createFor(item.manga, requireContext()).chapters(item.manga.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                if (!isAdded()) return;
                if (chapters == null || chapters.isEmpty()) { Toast.makeText(requireContext(), "Daftar chapter tidak tersedia", Toast.LENGTH_SHORT).show(); return; }
                item.manga.totalChapters = chapters.size();
                int pos = 0;
                for (int i = 0; i < chapters.size(); i++) if (Math.abs(chapters.get(i).index - item.chapterIndex) < 0.001f) { pos = i; break; }
                ((MainActivity)requireActivity()).openMangaReader(item.manga, chapters, pos);
            }
            @Override public void onError(String message) { if (isAdded()) Toast.makeText(requireContext(), "History manga gagal dibuka", Toast.LENGTH_SHORT).show(); }
        });
    }

    private void clearImages(View view) {
        if (view == null) return;
        if (view instanceof ImageView) MangaImageLoader.clear((ImageView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clearImages(group.getChildAt(i));
        }
    }

    private final class HistoryListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final ArrayList<HistoryRow> rows = new ArrayList<>();

        void submit(ArrayList<HistoryRow> data) {
            rows.clear();
            if (data != null) rows.addAll(data);
            notifyDataSetChanged();
        }

        @Override public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == HISTORY_ROW_HEADER) {
                View view = inflater.inflate(R.layout.history_day_header, parent, false);
                return new HeaderHolder(view, view.findViewById(R.id.dayHeaderTextView));
            }
            View view = inflater.inflate(R.layout.manga_history_item, parent, false);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(14));
            view.setLayoutParams(params);
            return new ItemHolder(view);
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            HistoryRow row = rows.get(position);
            if (holder instanceof HeaderHolder) ((HeaderHolder) holder).title.setText(row.label == null ? "" : row.label);
            else if (holder instanceof ItemHolder && row.entry != null) bindHistoryCard(holder.itemView, row.entry);
        }

        @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            clearImages(holder.itemView);
            super.onViewRecycled(holder);
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        HeaderHolder(View itemView, TextView title) {
            super(itemView);
            this.title = title;
        }
    }

    private static final class ItemHolder extends RecyclerView.ViewHolder {
        ItemHolder(View itemView) {
            super(itemView);
        }
    }

    private static final class HistoryRow {
        final int type;
        final String label;
        final MangaHistoryManager.Entry entry;

        private HistoryRow(int type, String label, MangaHistoryManager.Entry entry) {
            this.type = type;
            this.label = label;
            this.entry = entry;
        }

        static HistoryRow header(String label) {
            return new HistoryRow(HISTORY_ROW_HEADER, label, null);
        }

        static HistoryRow item(MangaHistoryManager.Entry entry) {
            return new HistoryRow(HISTORY_ROW_ITEM, null, entry);
        }
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String makeDayKey(long timestamp) { Calendar cal = Calendar.getInstance(); cal.setTimeInMillis(timestamp <= 0 ? System.currentTimeMillis() : timestamp); return cal.get(Calendar.YEAR) + "-" + cal.get(Calendar.DAY_OF_YEAR); }

    private static String makeDayLabel(long timestamp) {
        if (timestamp <= 0) return "Dibaca hari ini";
        long diffDays = dayDiff(timestamp);
        if (diffDays <= 0) return "Dibaca hari ini";
        if (diffDays == 1) return "Dibaca kemarin";
        return "Dibaca " + diffDays + " hari yang lalu";
    }

    private static String formatRelativeReadTime(long timestamp) {
        if (timestamp <= 0) return "Baru dibaca";
        long now = System.currentTimeMillis();
        long diff = Math.max(0L, now - timestamp);
        long minute = 60L * 1000L;
        long hour = 60L * minute;
        long day = 24L * hour;
        if (diff < minute) return "Baru saja";
        if (diff < hour) {
            long minutes = Math.max(1L, diff / minute);
            return minutes + " menit yang lalu";
        }
        if (diff < day) {
            long hours = Math.max(1L, diff / hour);
            return hours + " jam yang lalu";
        }
        long diffDays = dayDiff(timestamp);
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
        return (today.getTimeInMillis() - start.getTimeInMillis()) / (24L * 60L * 60L * 1000L);
    }
}
