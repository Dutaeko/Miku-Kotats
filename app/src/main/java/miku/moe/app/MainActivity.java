package miku.moe.app;

import android.os.Bundle;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.view.MenuItem;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.color.MaterialColors;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_OPEN_MANGA_DETAIL = "open_manga_detail";
    private static final String PREFS_NAME = "miku_moe_prefs";
    private static final String KEY_WELCOME_DONE = "welcome_done";
    private BottomNavigationView bottomNavigationView;
    private HomeFragment homeFragment;
    private SearchFragment searchFragment;
    private FavoriteFragment favoriteFragment;
    private HistoryFragment historyFragment;
    private InfoStatsFragment infoStatsFragment;
    private Fragment activeRootFragment;
    private boolean mangaMode;
    private boolean mangaNavigationLocked;
    private final Handler mangaNavigationHandler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mangaMode = MangaSettingsManager.isMangaModeEnabled(this);
        setupSystemBars();
        bottomNavigationView = findViewById(R.id.bottomNavigation);
        configureBottomNavigationForMode();
        setupBottomNavigationListener();
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                removeDanglingReaders();
                restoreSystemBars();
                restoreBottomNavigationState();
                setBottomNavigationVisible(true);
                if (activeRootFragment == null) activeRootFragment = findVisibleRootFragment();
                if (activeRootFragment != null && activeRootFragment.isHidden()) getSupportFragmentManager().beginTransaction().show(activeRootFragment).commitAllowingStateLoss();
                refreshMangaLocalRoot(activeRootFragment);
            }
        });
        boolean welcomeDone = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_WELCOME_DONE, false);
        if (!welcomeDone) { setBottomNavigationVisible(false); showWelcomeDialog(); return; }
        initializeRootFragments(savedInstanceState);
        handleMangaNavigationIntent(getIntent());
    }


    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleMangaNavigationIntent(intent);
    }

    private void handleMangaNavigationIntent(Intent intent) {
        if (intent == null || !mangaMode) return;
        Object value = intent.getSerializableExtra(EXTRA_OPEN_MANGA_DETAIL);
        if (!(value instanceof MangaPost)) return;
        intent.removeExtra(EXTRA_OPEN_MANGA_DETAIL);
        MangaPost manga = (MangaPost) value;
        if (getSupportFragmentManager().isStateSaved()) {
            mangaNavigationHandler.post(() -> openMangaDetail(manga));
        } else {
            openMangaDetail(manga);
        }
    }

    private void showWelcomeDialog() {
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Selamat Datang di Miku Moe")
                .setMessage("Aplikasi Miku Moe dibuat dengan sepenuh hati,project hasil gabut semoga suka dengan tampilan antarmuka aplikasi nya,jangan lupa untuk bergabung di saluran telegram saya agar tidak ketinggalan pembaruan aplikasi Miku Moe terimakasih telah menggunakan jika ada masalah silahkan hubungi @Miku01v di telegram.")
                .setPositiveButton("Oke", null).create();
        dialog.setCancelable(false); dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> {
            Button okButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            okButton.setEnabled(false); okButton.setText("Oke (3)");
            Handler handler = new Handler(Looper.getMainLooper()); final int[] remaining = {3};
            Runnable countdown = new Runnable() { @Override public void run() { remaining[0]--; if (remaining[0] > 0) { okButton.setText("Oke (" + remaining[0] + ")"); handler.postDelayed(this, 1000); } else { okButton.setText("Oke"); okButton.setEnabled(true); } } };
            handler.postDelayed(countdown, 1000);
            okButton.setOnClickListener(v -> { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_WELCOME_DONE, true).apply(); dialog.dismiss(); initializeRootFragments(null); handleMangaNavigationIntent(getIntent()); });
        });
        dialog.show();
    }

    private void initializeRootFragments(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            infoStatsFragment = new InfoStatsFragment();
            Fragment rootHome, rootSearch, rootFavorite, rootHistory;
            if (mangaMode) {
                rootHome = new MangaHomeFragment(); rootSearch = new MangaSearchFragment(); rootFavorite = new MangaFavoriteTabsFragment(); rootHistory = new Fragment();
            } else {
                rootHome = createAnimeHome(); rootSearch = createAnimeSearch(); rootFavorite = createAnimeFavorite(); rootHistory = createAnimeHistory();
                bindDefaultAnimeFields(rootHome, rootSearch, rootFavorite, rootHistory);
            }
            activeRootFragment = rootHome;
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragmentContainer, infoStatsFragment, "info").hide(infoStatsFragment)
                    .add(R.id.fragmentContainer, rootHistory, "history").hide(rootHistory)
                    .add(R.id.fragmentContainer, rootFavorite, "favorite").hide(rootFavorite)
                    .add(R.id.fragmentContainer, rootSearch, "search").hide(rootSearch)
                    .add(R.id.fragmentContainer, rootHome, "home").commit();
        } else {
            Fragment savedHome = findByTag("home");
            Fragment savedSearch = findByTag("search");
            Fragment savedFavorite = findByTag("favorite");
            Fragment savedHistory = findByTag("history");
            infoStatsFragment = (InfoStatsFragment) getSupportFragmentManager().findFragmentByTag("info");

            boolean savedIsManga = savedHome instanceof MangaHomeFragment
                    || savedSearch instanceof MangaSearchFragment
                    || savedFavorite instanceof MangaFavoriteFragment
                    || savedFavorite instanceof MangaFavoriteTabsFragment
                    || savedHistory instanceof MangaHistoryFragment;
            boolean savedIsAnime = isSavedAnimeRootSet(savedHome, savedSearch, savedFavorite, savedHistory);

            if (savedHome == null || savedSearch == null || savedFavorite == null || savedHistory == null
                    || infoStatsFragment == null || (mangaMode && !savedIsManga) || (!mangaMode && !savedIsAnime)) {
                androidx.fragment.app.FragmentTransaction cleanup = getSupportFragmentManager().beginTransaction();
                for (Fragment f : new ArrayList<>(getSupportFragmentManager().getFragments())) {
                    if (f != null) cleanup.remove(f);
                }
                cleanup.commitNowAllowingStateLoss();
                initializeRootFragments(null);
                return;
            }

            if (!mangaMode) bindDefaultAnimeFields(savedHome, savedSearch, savedFavorite, savedHistory);
            activeRootFragment = findVisibleRootFragment(); if (activeRootFragment == null) activeRootFragment = findByTag("home");
        }
        setBottomNavigationVisible(true);
    }

    private Fragment findByTag(String tag) { return getSupportFragmentManager().findFragmentByTag(tag); }

    private Fragment createAnimeHome() { return new AnimeHomeFragment(); }
    private Fragment createAnimeSearch() { return new AnimeGlobalSearchFragment(); }
    private Fragment createAnimeFavorite() { return new FavoriteFragment(); }
    private Fragment createAnimeHistory() { return new HistoryFragment(); }

    private void bindDefaultAnimeFields(Fragment rootHome, Fragment rootSearch, Fragment rootFavorite, Fragment rootHistory) {
        homeFragment = rootHome instanceof HomeFragment ? (HomeFragment) rootHome : null;
        searchFragment = rootSearch instanceof SearchFragment ? (SearchFragment) rootSearch : null;
        favoriteFragment = rootFavorite instanceof FavoriteFragment ? (FavoriteFragment) rootFavorite : null;
        historyFragment = rootHistory instanceof HistoryFragment ? (HistoryFragment) rootHistory : null;
    }

    private boolean isSavedAnimeRootSet(Fragment savedHome, Fragment savedSearch, Fragment savedFavorite, Fragment savedHistory) {
        return savedHome instanceof AnimeHomeFragment && savedSearch instanceof AnimeGlobalSearchFragment && savedFavorite instanceof FavoriteFragment && savedHistory instanceof HistoryFragment;
    }

    private void setupBottomNavigationListener() {
        bottomNavigationView.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_home) return selectRootFragment("home");
            if (item.getItemId() == R.id.nav_search) return selectRootFragment("search");
            if (item.getItemId() == R.id.nav_favorite) return selectRootFragment("favorite");
            if (item.getItemId() == R.id.nav_history) return selectRootFragment("history");
            if (item.getItemId() == R.id.nav_info) return selectRootFragment("info");
            return false;
        });
    }

    private boolean selectRootFragment(String tag) {
        Fragment target = "info".equals(tag) ? infoStatsFragment : findByTag(tag);
        if (target == null) return false;
        if (activeRootFragment == target && target.isAdded() && !target.isHidden()) return true;
        showRootFragment(target);
        return true;
    }

    private void refreshRoot(Fragment f) {
        if (f instanceof MangaHomeFragment) ((MangaHomeFragment) f).refreshHome();
        if (f instanceof BaseMangaGridFragment) ((BaseMangaGridFragment) f).reload();
        refreshNonMangaRoot(f);
    }


    private void refreshMangaLocalRoot(Fragment f) {
        if (f instanceof MangaFavoriteTabsFragment) ((MangaFavoriteTabsFragment) f).reload();
        if (f instanceof MangaHistoryFragment) ((MangaHistoryFragment) f).reload();
        if (f instanceof MangaFavoriteFragment) ((MangaFavoriteFragment) f).refreshFavorites();
    }

    private void configureBottomNavigationForMode() {
        if (bottomNavigationView == null) return;
        MenuItem favoriteItem = bottomNavigationView.getMenu().findItem(R.id.nav_favorite);
        if (favoriteItem != null) favoriteItem.setTitle("Favorite");
        MenuItem historyItem = bottomNavigationView.getMenu().findItem(R.id.nav_history);
        if (historyItem != null) historyItem.setVisible(!mangaMode);
    }

    private void refreshNonMangaRoot(Fragment f) {
        if (f instanceof AnimeHomeFragment) ((AnimeHomeFragment) f).refreshHome();
        if (f instanceof HomeFragment) ((HomeFragment) f).refreshHome();
        if (f instanceof AnimekuHomeFragment) ((AnimekuHomeFragment) f).refreshHome();
        if (f instanceof FavoriteFragment) ((FavoriteFragment) f).refreshFavorites();
        if (f instanceof AnimekuFavoriteFragment) ((AnimekuFavoriteFragment) f).refreshFavorites();
        if (f instanceof HistoryFragment) ((HistoryFragment) f).refreshHistory();
        if (f instanceof AnimekuHistoryFragment) ((AnimekuHistoryFragment) f).refreshHistory();
    }

    public void restoreSystemBars() { setupSystemBars(); }

    @Override protected void onResume() {
        super.onResume();
        if (!hasVisibleMangaReader()) restoreSystemBars();
    }

    private void restoreBottomNavigationState() {
        if (bottomNavigationView == null) return;
        bottomNavigationView.clearAnimation();
        bottomNavigationView.setVisibility(View.VISIBLE);
        bottomNavigationView.setAlpha(1f);
        bottomNavigationView.setTranslationY(0f);
        bottomNavigationView.setBackgroundResource(R.drawable.bottom_nav_background);
        ColorStateList colors = ContextCompat.getColorStateList(this, R.color.bottom_nav_item_color);
        bottomNavigationView.setItemIconTintList(colors);
        bottomNavigationView.setItemTextColor(colors);
        bottomNavigationView.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
        for (int i = 0; i < bottomNavigationView.getChildCount(); i++) {
            View child = bottomNavigationView.getChildAt(i);
            child.clearAnimation();
            child.setVisibility(View.VISIBLE);
            child.setAlpha(1f);
            child.setTranslationY(0f);
        }
        bottomNavigationView.requestLayout();
        bottomNavigationView.invalidate();
    }

    private void setupSystemBars() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(lp);
        }
        int surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, ContextCompat.getColor(this, R.color.md_theme_surface));
        window.setStatusBarColor(surfaceColor); window.setNavigationBarColor(surfaceColor);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) window.setNavigationBarDividerColor(surfaceColor);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(true);
            window.setNavigationBarContrastEnforced(true);
        }
        boolean isNightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE; if (!isNightMode) { flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    public void setAppBottomNavigationVisible(boolean visible) { setBottomNavigationVisible(visible); }

    private void setBottomNavigationVisible(boolean visible) {
        if (bottomNavigationView == null) return;
        if (visible) restoreBottomNavigationState();
        bottomNavigationView.setVisibility(visible ? View.VISIBLE : View.GONE);
        View container = findViewById(R.id.fragmentContainer); if (container != null) { ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) container.getLayoutParams(); params.bottomMargin = visible ? dp(96) : 0; container.setLayoutParams(params); }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Fragment findVisibleRootFragment() {
        String[] tags = {"home", "search", "favorite", "history", "info"};
        for (String tag : tags) { Fragment f = findByTag(tag); if (f != null && f.isAdded() && !f.isHidden()) return f; }
        return null;
    }

    private void showRootFragment(Fragment target) {
        if (target == null) return;
        restoreSystemBars();
        setBottomNavigationVisible(true);
        getSupportFragmentManager().popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        if (activeRootFragment == null) activeRootFragment = findVisibleRootFragment();
        if (activeRootFragment == target && target.isAdded() && !target.isHidden()) return;
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (mangaMode) transaction.setReorderingAllowed(true);
        String[] tags = {"home", "search", "favorite", "history", "info"};
        for (String tag : tags) { Fragment f = findByTag(tag); if (f != null && f.isAdded() && f != target) transaction.hide(f); }
        if (target.isAdded()) transaction.show(target);
        if (mangaMode) transaction.commitAllowingStateLoss(); else transaction.commit();
        activeRootFragment = target;
        if (target instanceof MangaFavoriteTabsFragment) ((MangaFavoriteTabsFragment) target).reload();
        if (target instanceof MangaFavoriteFragment) ((MangaFavoriteFragment) target).refreshFavorites();
    }

    public void openDetail(int categoryId) { openDetail(categoryId, -1); }
    public void openDetail(int categoryId, int channelId) {
        if (activeRootFragment == null) return; setBottomNavigationVisible(false);
        getSupportFragmentManager().beginTransaction().setCustomAnimations(R.anim.slide_in_right, R.anim.fade_out, R.anim.fade_in, R.anim.slide_out_right).hide(activeRootFragment).add(R.id.fragmentContainer, MikuDetail.newInstance(categoryId, channelId), "detail").addToBackStack("detail").commit();
    }

    public void openAnimekuDetail(int categoryId, int videoId, String title, String imageUrl, String genre, String rating, int year, String views, String episodeCount) {
        openAnimekuDetail(categoryId, videoId, title, imageUrl, genre, rating, year, views, episodeCount, "");
    }

    public void openAnimekuDetail(int categoryId, int videoId, String title, String imageUrl, String genre, String rating, int year, String views, String episodeCount, String description) {
        if (activeRootFragment == null) activeRootFragment = findVisibleRootFragment();
        setBottomNavigationVisible(false);
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction().setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (activeRootFragment != null && activeRootFragment.isAdded()) tx.hide(activeRootFragment);
        tx.add(R.id.fragmentContainer, AnimekuDetail.newInstance(categoryId, videoId, title, imageUrl, genre, rating, year, views, episodeCount, description), "animeku_detail").addToBackStack("animeku_detail").commit();
    }

    public void openAnimeLoverzDetail(String slug, String title, String imageUrl, String genre, String rating, String status, String description) {
        if (activeRootFragment == null) activeRootFragment = findVisibleRootFragment();
        setBottomNavigationVisible(false);
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction().setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (activeRootFragment != null && activeRootFragment.isAdded()) tx.hide(activeRootFragment);
        tx.add(R.id.fragmentContainer, AnimeLoverzDetail.newInstance(slug, title, imageUrl, genre, rating, status, description), "animeloverz_detail").addToBackStack("animeloverz_detail").commit();
    }

    public void openEpisode(int channelId) { getSupportFragmentManager().beginTransaction().setCustomAnimations(R.anim.slide_in_right, R.anim.fade_out, R.anim.fade_in, R.anim.slide_out_right).add(R.id.fragmentContainer, MikuAja.newInstance(channelId), "episode").addToBackStack("episode").commit(); }
    public void openPlayer(String videoUrl) { getSupportFragmentManager().beginTransaction().setCustomAnimations(R.anim.slide_in_right, R.anim.fade_out, R.anim.fade_in, R.anim.slide_out_right).add(R.id.fragmentContainer, MikuNonton.newInstance(videoUrl), "player").addToBackStack("player").commit(); }

    public void restartForMangaModeChange() {
        switchRootMode(MangaSettingsManager.isMangaModeEnabled(this));
    }

    public void restartForAnimeSourceChange() {
        if (!mangaMode) switchRootMode(false);
    }

    public void refreshMangaSourceSettings() {
        if (!mangaMode) return;
        Fragment home = findByTag("home");
        Fragment search = findByTag("search");
        if (home instanceof MangaHomeFragment) ((MangaHomeFragment) home).refreshHome(true);
        if (home instanceof BaseMangaGridFragment) ((BaseMangaGridFragment) home).refreshSourceSettings();
        if (search instanceof BaseMangaGridFragment) ((BaseMangaGridFragment) search).refreshSourceSettings();
        if (search instanceof MangaSearchFragment) ((MangaSearchFragment) search).refreshSourceSettings();
    }

    public void switchRootMode(boolean enableManga) {
        mangaMode = enableManga;
        configureBottomNavigationForMode();
        setBottomNavigationVisible(true);
        getSupportFragmentManager().popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);

        androidx.fragment.app.FragmentTransaction cleanup = getSupportFragmentManager().beginTransaction();
        for (Fragment f : new ArrayList<>(getSupportFragmentManager().getFragments())) {
            if (f != null) cleanup.remove(f);
        }
        cleanup.commitNowAllowingStateLoss();

        infoStatsFragment = new InfoStatsFragment();
        Fragment rootHome, rootSearch, rootFavorite, rootHistory;
        if (mangaMode) {
            rootHome = new MangaHomeFragment();
            rootSearch = new MangaSearchFragment();
            rootFavorite = new MangaFavoriteTabsFragment();
            rootHistory = new Fragment();
            homeFragment = null; searchFragment = null; favoriteFragment = null; historyFragment = null;
        } else {
            rootHome = createAnimeHome();
            rootSearch = createAnimeSearch();
            rootFavorite = createAnimeFavorite();
            rootHistory = createAnimeHistory();
            bindDefaultAnimeFields(rootHome, rootSearch, rootFavorite, rootHistory);
        }
        activeRootFragment = rootHome;
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragmentContainer, infoStatsFragment, "info").hide(infoStatsFragment)
                .add(R.id.fragmentContainer, rootHistory, "history").hide(rootHistory)
                .add(R.id.fragmentContainer, rootFavorite, "favorite").hide(rootFavorite)
                .add(R.id.fragmentContainer, rootSearch, "search").hide(rootSearch)
                .add(R.id.fragmentContainer, rootHome, "home")
                .commitNowAllowingStateLoss();
        bottomNavigationView.setSelectedItemId(R.id.nav_home);
        refreshRoot(rootHome);
    }

    public void openMangaDetail(MangaPost manga) {
        if (manga == null || !lockMangaNavigation()) return;
        if (activeRootFragment == null) activeRootFragment = findVisibleRootFragment();
        setBottomNavigationVisible(false);
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (activeRootFragment != null && activeRootFragment.isAdded()) tx.hide(activeRootFragment);
        tx.add(R.id.fragmentContainer, MangaSettingsManager.isDetailUiV1(this) ? MangaDetailV1Fragment.newInstance(manga) : MangaDetailFragment.newInstance(manga), "manga_detail")
                .addToBackStack("manga_detail")
                .commitAllowingStateLoss();
    }
    public void openMangaBrowseSource(String sourceId, String sourceLabel, String query) {
        if (!lockMangaNavigation()) return;
        if (activeRootFragment == null) activeRootFragment = findVisibleRootFragment();
        setBottomNavigationVisible(false);
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (activeRootFragment != null && activeRootFragment.isAdded()) tx.hide(activeRootFragment);
        tx.add(R.id.fragmentContainer, BrowseSourceScreen.newSource(sourceId, sourceLabel, query), "browse_source_screen")
                .addToBackStack("browse_source_screen")
                .commitAllowingStateLoss();
    }

    public void openMangaGenreResult(String sourceId, String sourceLabel, String genreTitle, String genreValue) {
        if (!lockMangaNavigation()) return;
        if (activeRootFragment == null) activeRootFragment = findVisibleRootFragment();
        setBottomNavigationVisible(false);
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (activeRootFragment != null && activeRootFragment.isAdded()) tx.hide(activeRootFragment);
        tx.add(R.id.fragmentContainer, BrowseSourceScreen.newGenre(sourceId, sourceLabel, genreTitle, genreValue), "browse_source_genre")
                .addToBackStack("browse_source_genre")
                .commitAllowingStateLoss();
    }

    public void openMangaReader(MangaPost manga, ArrayList<MangaChapter> chapters, int chapterPos) {
        if (manga == null || !lockMangaNavigation()) return;
        if (activeRootFragment == null) activeRootFragment = findVisibleRootFragment();
        setBottomNavigationVisible(false);
        androidx.fragment.app.FragmentTransaction tx = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        for (Fragment f : new ArrayList<>(getSupportFragmentManager().getFragments())) {
            if (f == null || !f.isAdded()) continue;
            if (isMangaReaderFragment(f)) tx.remove(f);
            else if (!f.isHidden()) tx.hide(f);
        }
        tx.add(R.id.fragmentContainer, createMangaReaderFragment(manga, chapters, chapterPos), "manga_reader")
                .addToBackStack("manga_reader")
                .commitAllowingStateLoss();
    }

    private boolean lockMangaNavigation() {
        if (mangaNavigationLocked) return false;
        mangaNavigationLocked = true;
        mangaNavigationHandler.removeCallbacksAndMessages(null);
        mangaNavigationHandler.postDelayed(() -> mangaNavigationLocked = false, 420);
        return true;
    }

    private Fragment createMangaReaderFragment(MangaPost manga, ArrayList<MangaChapter> chapters, int chapterPos) {
        String ui = MangaSettingsManager.getReaderUi(this);
        if (MangaSettingsManager.READER_UI_V2.equals(ui)) return MangaReaderFragmentV2.newInstance(manga, chapters, chapterPos);
        if (MangaSettingsManager.READER_UI_V1.equals(ui)) return MangaReaderFragmentV1.newInstance(manga, chapters, chapterPos);
        return MangaReaderFragment.newInstance(manga, chapters, chapterPos);
    }

    private boolean isMangaReaderFragment(Fragment f) {
        return f instanceof MangaReaderFragment || f instanceof MangaReaderFragmentV1 || f instanceof MangaReaderFragmentV2;
    }

    private boolean hasVisibleMangaReader() {
        for (Fragment f : new ArrayList<>(getSupportFragmentManager().getFragments())) {
            if (f != null && f.isAdded() && !f.isHidden() && isMangaReaderFragment(f)) return true;
        }
        return false;
    }

    private void removeDanglingReaders() {
        androidx.fragment.app.FragmentTransaction transaction = null;
        for (Fragment f : new ArrayList<>(getSupportFragmentManager().getFragments())) {
            if (f != null && f.isAdded() && isMangaReaderFragment(f)) {
                if (transaction == null) transaction = getSupportFragmentManager().beginTransaction();
                transaction.remove(f);
            }
        }
        if (transaction != null) transaction.commitAllowingStateLoss();
    }

    @Override protected void onDestroy() {
        mangaNavigationHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStackImmediate();
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                removeDanglingReaders();
                restoreSystemBars();
                setBottomNavigationVisible(true);
                if (activeRootFragment == null) activeRootFragment = findVisibleRootFragment();
                if (activeRootFragment != null && activeRootFragment.isHidden()) {
                    getSupportFragmentManager().beginTransaction().show(activeRootFragment).commitAllowingStateLoss();
                }
            } else {
                setBottomNavigationVisible(false);
            }
            return;
        }

        if (bottomNavigationView != null && bottomNavigationView.getSelectedItemId() != R.id.nav_home) {
            bottomNavigationView.setSelectedItemId(R.id.nav_home);
            return;
        }

        showExitConfirmDialog();
    }

    private void showExitConfirmDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Keluar aplikasi")
                .setMessage("Apakah kamu yakin ingin keluar dari aplikasi?")
                .setNegativeButton("Tidak", null)
                .setPositiveButton("Ya", (dialog, which) -> finish())
                .show();
    }
}

