package miku.moe.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

public class MangaSearchFragment extends Fragment {
    private RecyclerView sourceRecyclerView;
    private ProgressBar progressBar;
    private EditText searchEditText;
    private MangaHomeSectionAdapter adapter;
    private final ArrayList<MangaHomeFragment.SourceSection> sections = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int generation = 0;
    private static final int SEARCH_LIMIT = 10;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manga_global_search, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        sourceRecyclerView = view.findViewById(R.id.sourceRecyclerView);
        progressBar = view.findViewById(R.id.progressBar);
        searchEditText = view.findViewById(R.id.searchEditText);
        sourceRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false));
        adapter = new MangaHomeSectionAdapter(requireContext(), sections, new MangaHomeSectionAdapter.ActionListener() {
            @Override public void onViewAll(MangaHomeFragment.SourceSection section) { openViewAll(section); }
            @Override public void onResolveCloudflare(MangaHomeFragment.SourceSection section) {}
            @Override public void onMangaClick(MangaPost post) { if (isAdded()) ((MainActivity) requireActivity()).openMangaDetail(post); }
            @Override public void onChapterClick(MangaPost post) { openLatestChapter(post); }
        });
        sourceRecyclerView.setAdapter(adapter);
        searchEditText.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchAll();
                return true;
            }
            return false;
        });
    }

    public void refreshSourceSettings() {
        if (searchEditText == null) return;
        String value = searchEditText.getText() == null ? "" : searchEditText.getText().toString().trim();
        if (!value.isEmpty()) searchAll();
    }

    private void searchAll() {
        if (!isAdded()) return;
        String query = searchEditText == null || searchEditText.getText() == null ? "" : searchEditText.getText().toString().trim();
        int run = ++generation;
        handler.removeCallbacksAndMessages(null);
        sections.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (query.isEmpty()) {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            return;
        }
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        ArrayList<String> sourceIds = MangaSourceFactory.enabledSourceIds(requireContext());
        if (sourceIds.isEmpty()) {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            return;
        }
        AtomicInteger remaining = new AtomicInteger(sourceIds.size());
        for (String sourceId : sourceIds) searchSource(sourceId, query, run, remaining);
    }

    private void searchSource(String sourceId, String query, int run, AtomicInteger remaining) {
        KomikcastClient api = MangaSourceFactory.createBySourceId(sourceId);
        api.list(1, "latest", query, "", new KomikcastClient.Result<ArrayList<MangaPost>>() {
            @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext) {
                if (!isAdded() || run != generation) return;
                MangaHomeFragment.SourceSection section = buildSection(sourceId, data);
                if (!section.items.isEmpty()) {
                    int index = sections.size();
                    sections.add(section);
                    if (adapter != null) adapter.notifyItemInserted(index);
                    if (MangaLabelUtils.shouldEnrichLabels(requireContext())) api.enrichLatest(section.items, () -> {
                        if (isAdded() && run == generation && adapter != null) {
                            for (MangaPost post : section.items) MangaLabelUtils.applyHiddenLabels(requireContext(), post);
                            adapter.notifyItemChanged(index);
                        }
                    });
                }
                finishOne(run, remaining);
            }
            @Override public void onError(String message) {
                if (!isAdded() || run != generation) return;
                finishOne(run, remaining);
            }
        });
    }

    private MangaHomeFragment.SourceSection buildSection(String sourceId, ArrayList<MangaPost> data) {
        MangaHomeFragment.SourceSection section = new MangaHomeFragment.SourceSection(sourceId);
        section.loading = false;
        section.cloudflareRequired = false;
        section.finished = true;
        HashSet<String> slugs = new HashSet<>();
        if (data == null) return section;
        String label = MangaSourceFactory.labelForSourceId(sourceId);
        for (MangaPost post : data) {
            if (post == null) continue;
            post.withSource(sourceId, label);
            MangaLabelUtils.applyHiddenLabels(getContext(), post);
            String key = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
            if (key == null || key.trim().isEmpty() || !slugs.add(key.trim())) continue;
            section.items.add(post);
            if (section.items.size() >= SEARCH_LIMIT) break;
        }
        return section;
    }

    private void finishOne(int run, AtomicInteger remaining) {
        if (remaining.decrementAndGet() <= 0 && isAdded() && run == generation) {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
        }
    }

    private void openViewAll(MangaHomeFragment.SourceSection section) {
        if (!isAdded() || section == null || searchEditText == null) return;
        String query = searchEditText.getText() == null ? "" : searchEditText.getText().toString().trim();
        if (requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).openMangaBrowseSource(section.sourceId, section.sourceLabel, query);
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

    @Override public void onDestroyView() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (sourceRecyclerView != null) sourceRecyclerView.setAdapter(null);
        sourceRecyclerView = null;
        progressBar = null;
        searchEditText = null;
        adapter = null;
        super.onDestroyView();
    }
}
