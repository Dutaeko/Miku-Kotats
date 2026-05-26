package miku.moe.app;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;

public class MangaFavoriteTabsFragment extends Fragment {
    private static final String KEY_SELECTED_TAB = "selected_tab";
    private static final String TAG_FAVORITE = "manga_tab_favorite";
    private static final String TAG_HISTORY = "manga_tab_history";
    private static final int TAB_FAVORITE = 0;
    private static final int TAB_HISTORY = 1;
    private TabLayout tabLayout;
    private LinearLayout favoriteActionContainer;
    private LinearLayout historyActionContainer;
    private MaterialButton clearHistoryButton;
    private int selectedTab = TAB_FAVORITE;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) selectedTab = savedInstanceState.getInt(KEY_SELECTED_TAB, TAB_FAVORITE);
        return inflater.inflate(R.layout.fragment_manga_favorite_tabs, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tabLayout = view.findViewById(R.id.mangaFavoriteTabLayout);
        favoriteActionContainer = view.findViewById(R.id.favoriteActionContainer);
        historyActionContainer = view.findViewById(R.id.historyActionContainer);
        clearHistoryButton = view.findViewById(R.id.clearHistoryButton);
        setupHeaderActions(view);
        setupTabs();
        showSelectedTab(selectedTab);
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, selectedTab);
    }

    @Override public void onResume() {
        super.onResume();
        reload();
        updateVisibleActions();
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            reload();
            updateVisibleActions();
        }
    }

    @Override public void onDestroyView() {
        tabLayout = null;
        favoriteActionContainer = null;
        historyActionContainer = null;
        clearHistoryButton = null;
        super.onDestroyView();
    }

    public void refreshFavorites() {
        Fragment favorite = getChildFragmentManager().findFragmentByTag(TAG_FAVORITE);
        if (favorite instanceof MangaFavoriteFragment) ((MangaFavoriteFragment) favorite).refreshFavorites();
    }

    public void reload() {
        Fragment favorite = getChildFragmentManager().findFragmentByTag(TAG_FAVORITE);
        Fragment history = getChildFragmentManager().findFragmentByTag(TAG_HISTORY);
        if (favorite instanceof MangaFavoriteFragment) ((MangaFavoriteFragment) favorite).refreshFavorites();
        if (history instanceof MangaHistoryFragment) ((MangaHistoryFragment) history).reload();
    }

    public void updateHistoryActionState(boolean empty, int selectedCount) {
        if (clearHistoryButton == null) return;
        clearHistoryButton.setVisibility(empty ? View.GONE : View.VISIBLE);
        clearHistoryButton.setText("");
        clearHistoryButton.setIconResource(R.drawable.ic_delete);
        clearHistoryButton.setIconPadding(0);
        int tintColor = selectedCount == 0 ? MaterialColors.getColor(clearHistoryButton, com.google.android.material.R.attr.colorPrimary) : 0xFFD32F2F;
        clearHistoryButton.setIconTint(ColorStateList.valueOf(tintColor));
        clearHistoryButton.setContentDescription(selectedCount == 0 ? "Hapus history" : "Hapus " + selectedCount + " history");
    }

    private void setupHeaderActions(View view) {
        View updateFavoriteButton = view.findViewById(R.id.updateFavoriteButton);
        View importFavoriteButton = view.findViewById(R.id.importFavoriteButton);
        View exportFavoriteButton = view.findViewById(R.id.exportFavoriteButton);
        View refreshFavoriteButton = view.findViewById(R.id.refreshFavoriteButton);
        View refreshHistoryButton = view.findViewById(R.id.refreshHistoryButton);
        if (updateFavoriteButton != null) updateFavoriteButton.setOnClickListener(v -> withFavoriteFragment(MangaFavoriteFragment::openUpdateFromHeader));
        if (importFavoriteButton != null) importFavoriteButton.setOnClickListener(v -> withFavoriteFragment(MangaFavoriteFragment::importFavoriteFromHeader));
        if (exportFavoriteButton != null) exportFavoriteButton.setOnClickListener(v -> withFavoriteFragment(MangaFavoriteFragment::exportFavoriteFromHeader));
        if (refreshFavoriteButton != null) refreshFavoriteButton.setOnClickListener(v -> withFavoriteFragment(MangaFavoriteFragment::refreshFavoriteFromHeader));
        if (refreshHistoryButton != null) refreshHistoryButton.setOnClickListener(v -> withHistoryFragment(MangaHistoryFragment::refreshHistoryFromHeader));
        if (clearHistoryButton != null) clearHistoryButton.setOnClickListener(v -> withHistoryFragment(MangaHistoryFragment::clearHistoryFromHeader));
    }

    private void setupTabs() {
        if (tabLayout == null) return;
        tabLayout.clearOnTabSelectedListeners();
        if (tabLayout.getTabCount() == 0) {
            tabLayout.addTab(tabLayout.newTab().setText("Favorite"));
            tabLayout.addTab(tabLayout.newTab().setText("History"));
        }
        TabLayout.Tab tab = tabLayout.getTabAt(selectedTab);
        if (tab != null) tab.select();
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                selectedTab = tab.getPosition() == TAB_HISTORY ? TAB_HISTORY : TAB_FAVORITE;
                showSelectedTab(selectedTab);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) { reload(); }
        });
    }

    private void showSelectedTab(int tabIndex) {
        if (!isAdded()) return;
        updateVisibleActions();
        FragmentManager fragmentManager = getChildFragmentManager();
        Fragment favorite = fragmentManager.findFragmentByTag(TAG_FAVORITE);
        Fragment history = fragmentManager.findFragmentByTag(TAG_HISTORY);
        FragmentTransaction transaction = fragmentManager.beginTransaction().setReorderingAllowed(true);
        if (favorite == null) {
            favorite = new MangaFavoriteFragment();
            transaction.add(R.id.mangaFavoriteTabContainer, favorite, TAG_FAVORITE);
        }
        if (history == null) {
            history = new MangaHistoryFragment();
            transaction.add(R.id.mangaFavoriteTabContainer, history, TAG_HISTORY).hide(history);
        }
        if (tabIndex == TAB_HISTORY) {
            transaction.hide(favorite).show(history);
            if (history instanceof MangaHistoryFragment && history.isAdded()) ((MangaHistoryFragment) history).syncHeaderActions();
        } else {
            transaction.hide(history).show(favorite);
        }
        if (fragmentManager.isStateSaved()) transaction.commitAllowingStateLoss(); else transaction.commit();
    }

    private void updateVisibleActions() {
        boolean historySelected = selectedTab == TAB_HISTORY;
        if (favoriteActionContainer != null) favoriteActionContainer.setVisibility(historySelected ? View.GONE : View.VISIBLE);
        if (historyActionContainer != null) historyActionContainer.setVisibility(historySelected ? View.VISIBLE : View.GONE);
    }

    private void withFavoriteFragment(FavoriteAction action) {
        Fragment fragment = getChildFragmentManager().findFragmentByTag(TAG_FAVORITE);
        if (fragment instanceof MangaFavoriteFragment) action.run((MangaFavoriteFragment) fragment);
    }

    private void withHistoryFragment(HistoryAction action) {
        Fragment fragment = getChildFragmentManager().findFragmentByTag(TAG_HISTORY);
        if (fragment instanceof MangaHistoryFragment) action.run((MangaHistoryFragment) fragment);
    }

    private interface FavoriteAction { void run(MangaFavoriteFragment fragment); }
    private interface HistoryAction { void run(MangaHistoryFragment fragment); }
}
