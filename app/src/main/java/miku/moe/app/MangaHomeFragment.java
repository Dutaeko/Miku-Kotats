package miku.moe.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

public class MangaHomeFragment extends Fragment {
    private RecyclerView sourceRecyclerView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private MangaHomeSectionAdapter adapter;
    private final ArrayList<SourceSection> sections = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int generation = 0;
    private static final int HOME_LIMIT = 10;
    private static final ArrayList<SourceSection> HOME_CACHE = new ArrayList<>();
    private static boolean homeCacheLoaded = false;

    public static class SourceSection {
        public final String sourceId;
        public final String sourceLabel;
        public final ArrayList<MangaPost> items = new ArrayList<>();
        public boolean loading = true;
        public boolean cloudflareRequired = false;
        public boolean finished = false;
        public SourceSection(String sourceId) {
            this.sourceId = sourceId;
            this.sourceLabel = MangaSourceFactory.labelForSourceId(sourceId);
        }
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manga_home, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        sourceRecyclerView = view.findViewById(R.id.sourceRecyclerView);
        progressBar = view.findViewById(R.id.progressBar);
        sourceRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false));
        sourceRecyclerView.setItemAnimator(null);
        sourceRecyclerView.setHasFixedSize(true);
        sourceRecyclerView.setItemViewCacheSize(8);
        adapter = new MangaHomeSectionAdapter(requireContext(), sections, new MangaHomeSectionAdapter.ActionListener() {
            @Override public void onViewAll(SourceSection section) { openViewAll(section); }
            @Override public void onResolveCloudflare(SourceSection section) { openCloudflare(section); }
            @Override public void onMangaClick(MangaPost post) { if (isAdded()) ((MainActivity) requireActivity()).openMangaDetail(post); }
            @Override public void onChapterClick(MangaPost post) { openLatestChapter(post); }
        });
        sourceRecyclerView.setAdapter(adapter);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(() -> refreshHome(true));
        if (homeCacheLoaded) restoreHomeCache(); else refreshHome(true);
    }


    @Override public void onResume() {
        super.onResume();
        if (!isAdded()) return;
        if (homeCacheLoaded && sections.isEmpty()) restoreHomeCache();
    }

    private boolean needsSourceRefresh() {
        ArrayList<String> sourceIds = MangaSourceFactory.enabledSourceIds(requireContext());
        if (sourceIds.size() != sections.size()) return true;
        for (int i = 0; i < sourceIds.size(); i++) if (!sourceIds.get(i).equals(sections.get(i).sourceId)) return true;
        return false;
    }

    private boolean hasCloudflareSection() {
        for (SourceSection section : sections) if (section != null && section.cloudflareRequired) return true;
        return false;
    }

    @Override public void onPause() {
        if (!sections.isEmpty()) saveHomeCache();
        super.onPause();
    }

    @Override public void onDestroyView() {
        if (!sections.isEmpty()) saveHomeCache();
        generation++;
        mainHandler.removeCallbacksAndMessages(null);
        if (sourceRecyclerView != null) sourceRecyclerView.setAdapter(null);
        sourceRecyclerView = null;
        swipeRefreshLayout = null;
        adapter = null;
        super.onDestroyView();
    }

    public void refreshHome() { refreshHome(false); }

    public void refreshHome(boolean forceNetwork) {
        if (!isAdded()) return;
        if (!forceNetwork && homeCacheLoaded) { restoreHomeCache(); return; }
        int run = ++generation;
        sections.clear();
        ArrayList<String> sourceIds = MangaSourceFactory.enabledSourceIds(requireContext());
        for (String sourceId : sourceIds) sections.add(new SourceSection(sourceId));
        if (adapter != null) adapter.notifyDataSetChanged();
        if (progressBar != null) progressBar.setVisibility(sections.isEmpty() || swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing() ? View.GONE : View.VISIBLE);
        AtomicInteger remaining = new AtomicInteger(sections.size());
        if (sections.isEmpty()) { if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false); return; }
        for (int i = 0; i < sections.size(); i++) loadSection(sections.get(i), i, run, remaining);
    }

    private void loadSection(SourceSection section, int index, int run, AtomicInteger remaining) {
        KomikcastClient api = MangaSourceFactory.createBySourceId(section.sourceId);
        api.list(1, "latest", "", "", new KomikcastClient.Result<ArrayList<MangaPost>>() {
            @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext) {
                if (!isAdded() || run != generation) return;
                appendSectionData(section, data);
                section.loading = false;
                section.cloudflareRequired = false;
                if (adapter != null) adapter.notifyItemChanged(index);
                finishSection(section, remaining);
                if (!section.items.isEmpty() && MangaLabelUtils.shouldEnrichLabels(requireContext())) api.enrichLatest(section.items, () -> {
                    if (isAdded() && run == generation && adapter != null) {
                        for (MangaPost post : section.items) MangaLabelUtils.applyHiddenLabels(requireContext(), post);
                        adapter.notifyItemChanged(index);
                    }
                });
            }
            @Override public void onError(String message) {
                if (!isAdded() || run != generation) return;
                section.loading = false;
                section.cloudflareRequired = CloudflareHelper.isCloudflareRequiredMessage(message) || CloudflareHelper.needsResolution(section.sourceLabel);
                if (adapter != null) adapter.notifyItemChanged(index);
                finishSection(section, remaining);
            }
        });
    }

    private void appendSectionData(SourceSection section, ArrayList<MangaPost> data) {
        section.items.clear();
        HashSet<String> slugs = new HashSet<>();
        if (data == null) return;
        for (MangaPost post : data) {
            if (post == null) continue;
            post.withSource(section.sourceId, section.sourceLabel);
            MangaLabelUtils.applyHiddenLabels(getContext(), post);
            String key = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
            if (key == null || key.trim().isEmpty() || !slugs.add(key.trim())) continue;
            section.items.add(post);
            if (section.items.size() >= HOME_LIMIT) break;
        }
    }

    private void finishSection(SourceSection section, AtomicInteger remaining) {
        if (section.finished) return;
        section.finished = true;
        finishOne(remaining);
    }

    private void finishOne(AtomicInteger remaining) {
        if (remaining.decrementAndGet() <= 0) {
            saveHomeCache();
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
        }
    }

    private void saveHomeCache() {
        HOME_CACHE.clear();
        for (SourceSection section : sections) {
            SourceSection copy = new SourceSection(section.sourceId);
            copy.loading = section.loading;
            copy.cloudflareRequired = section.cloudflareRequired;
            copy.finished = section.finished;
            copy.items.addAll(section.items);
            HOME_CACHE.add(copy);
        }
        homeCacheLoaded = true;
    }

    private void restoreHomeCache() {
        sections.clear();
        for (SourceSection section : HOME_CACHE) {
            SourceSection copy = new SourceSection(section.sourceId);
            copy.loading = section.loading;
            copy.cloudflareRequired = section.cloudflareRequired;
            copy.finished = section.finished;
            copy.items.addAll(section.items);
            sections.add(copy);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
    }

    private void openLatestChapter(MangaPost post) {
        if (!isAdded() || post == null || post.slug == null || post.slug.trim().isEmpty()) return;
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        MangaSourceFactory.createBySourceId(post.getSourceId()).chapters(post.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                if (!isAdded()) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (chapters == null || chapters.isEmpty()) {
                    Toast.makeText(requireContext(), "Chapter belum tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                ((MainActivity) requireActivity()).openMangaReader(post, new ArrayList<>(chapters), findChapterPosition(chapters, post.latestChapter));
            }
            @Override public void onError(String message) {
                if (!isAdded()) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                Toast.makeText(requireContext(), message == null || message.trim().isEmpty() ? "Gagal membuka chapter" : message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int findChapterPosition(ArrayList<MangaChapter> chapters, String latestChapter) {
        float target = parseChapterIndex(latestChapter);
        if (target >= 0f) {
            for (int i = 0; i < chapters.size(); i++) if (Math.abs(chapters.get(i).index - target) < 0.001f) return i;
        }
        int newest = 0;
        for (int i = 1; i < chapters.size(); i++) if (chapters.get(i).index > chapters.get(newest).index) newest = i;
        return newest;
    }

    private float parseChapterIndex(String text) {
        if (text == null) return -1f;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text.replace(',', '.'));
        if (!matcher.find()) return -1f;
        try { return Float.parseFloat(matcher.group(1)); } catch (Exception ignored) { return -1f; }
    }

    private void openCloudflare(SourceSection section) {
        if (!isAdded() || section == null) return;
        boolean opened = CloudflareHelper.openResolverForSource(requireContext(), section.sourceId, section.sourceLabel);
        if (!opened) Toast.makeText(requireContext(), "Gagal membuka halaman Cloudflare", Toast.LENGTH_SHORT).show();
    }

    private void openViewAll(SourceSection section) {
        if (!isAdded() || section == null) return;
        if (!sections.isEmpty()) saveHomeCache();
        if (requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).openMangaBrowseSource(section.sourceId, section.sourceLabel, "");
    }
}
