package miku.moe.app;

import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.color.MaterialColors;

public class AnimexAll extends AppCompatActivity {
    public static final String EXTRA_SOURCE_ID = "source_id";
    public static final String EXTRA_SOURCE_LABEL = "source_label";
    public static final String EXTRA_QUERY = "query";

    @Override protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_miku_all);
        setupSystemBars();
        String sourceId = getIntent().getStringExtra(EXTRA_SOURCE_ID);
        String sourceLabel = getIntent().getStringExtra(EXTRA_SOURCE_LABEL);
        if (sourceId == null || sourceId.trim().isEmpty()) sourceId = AnimeSettingsManager.SOURCE_DEFAULT;
        if (sourceLabel == null || sourceLabel.trim().isEmpty()) sourceLabel = AnimeSettingsManager.labelForSourceId(sourceId);
        android.widget.TextView titleTextView = findViewById(R.id.titleTextView);
        android.view.View backButton = findViewById(R.id.backButton);
        if (titleTextView != null) titleTextView.setText(sourceLabel);
        if (backButton != null) backButton.setOnClickListener(v -> onBackPressed());
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateToolbarVisibility);
        if (savedInstanceState == null) {
            String query = getIntent().getStringExtra(EXTRA_QUERY);
            Fragment fragment = createSourceFragment(sourceId, query == null ? "" : query);
            getSupportFragmentManager().beginTransaction().replace(R.id.mikuAllContainer, fragment, "anime_all_grid").commit();
        }
        updateToolbarVisibility();
    }


    private Fragment createSourceFragment(String sourceId, String query) {
        if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(sourceId)) return MikuAnimekuSearch.newInstance(query);
        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(sourceId)) return MikuAnimeLoverzSearch.newInstance(query);
        return MikuAnimeXSearch.newInstance(query);
    }

    public void openDetail(int categoryId) { openDetail(categoryId, -1); }

    public void openDetail(int categoryId, int channelId) {
        Fragment current = getCurrentContentFragment();
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (current != null) transaction.hide(current);
        transaction.add(R.id.mikuAllContainer, MikuDetail.newInstance(categoryId, channelId), "detail")
                .addToBackStack("detail")
                .commitAllowingStateLoss();
        updateToolbarVisibility();
    }

    public void openAnimekuDetail(int categoryId, int videoId, String title, String imageUrl, String genre, String rating, int year, String views, String episodeCount) {
        openAnimekuDetail(categoryId, videoId, title, imageUrl, genre, rating, year, views, episodeCount, "");
    }

    public void openAnimekuDetail(int categoryId, int videoId, String title, String imageUrl, String genre, String rating, int year, String views, String episodeCount, String description) {
        Fragment current = getCurrentContentFragment();
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (current != null) transaction.hide(current);
        transaction.add(R.id.mikuAllContainer, AnimekuDetail.newInstance(categoryId, videoId, title, imageUrl, genre, rating, year, views, episodeCount, description), "animeku_detail")
                .addToBackStack("animeku_detail")
                .commitAllowingStateLoss();
        updateToolbarVisibility();
    }


    public void openAnimeLoverzDetail(String slug, String title, String imageUrl, String genre, String rating, String status, String description) {
        Fragment current = getCurrentContentFragment();
        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.manga_nav_enter, R.anim.manga_nav_exit, R.anim.manga_nav_pop_enter, R.anim.manga_nav_pop_exit);
        if (current != null) transaction.hide(current);
        transaction.add(R.id.mikuAllContainer, AnimeLoverzDetail.newInstance(slug, title, imageUrl, genre, rating, status, description), "animeloverz_detail")
                .addToBackStack("animeloverz_detail")
                .commitAllowingStateLoss();
        updateToolbarVisibility();
    }

    private Fragment getCurrentContentFragment() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment != null && fragment.isAdded() && !fragment.isHidden() && fragment.getId() == R.id.mikuAllContainer) return fragment;
        }
        return getSupportFragmentManager().findFragmentById(R.id.mikuAllContainer);
    }

    private void updateToolbarVisibility() {
        View toolbar = findViewById(R.id.mikuAllToolbar);
        if (toolbar == null) return;
        boolean showToolbar = getSupportFragmentManager().getBackStackEntryCount() == 0;
        toolbar.setVisibility(showToolbar ? View.VISIBLE : View.GONE);
    }

    private void setupSystemBars() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        int surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, ContextCompat.getColor(this, R.color.md_theme_surface));
        window.setStatusBarColor(surfaceColor);
        window.setNavigationBarColor(surfaceColor);
        boolean isNightMode = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!isNightMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }
}
