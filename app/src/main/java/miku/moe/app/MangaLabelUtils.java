package miku.moe.app;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

public final class MangaLabelUtils {
    private MangaLabelUtils() {}

    public static String typeLabel(MangaPost post) {
        if (post == null) return "";
        if ((MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(post.getSourceId()) || MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(post.getSourceId())) && (post.typeLabel == null || post.typeLabel.trim().isEmpty())) return "";
        return post.getTypeLabel();
    }

    public static void bindType(TextView view, MangaPost post, Context context, boolean respectSettings) {
        if (view == null) return;
        String text = typeLabel(post);
        boolean hide = respectSettings && context != null && MangaSettingsManager.shouldHideTypeLabel(context);
        if (hide || text.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            view.setText("");
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(text);
        }
    }

    public static void bindChapter(TextView view, CharSequence text, Context context, boolean respectSettings) {
        if (view == null) return;
        boolean hide = respectSettings && MangaSettingsManager.shouldHideLatestChapterLabel(context);
        if (hide || text == null || text.toString().trim().isEmpty()) {
            view.setVisibility(View.GONE);
            view.setText("");
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(text instanceof String ? text.toString().trim() : text);
        }
    }

    public static CharSequence favoriteChapterIncreaseLabel(int base, int added) {
        if (base <= 0 || added <= 0) return "";
        String text = base + "+" + added;
        SpannableString spannable = new SpannableString(text);
        int start = String.valueOf(base).length();
        spannable.setSpan(new ForegroundColorSpan(0xFFFF2D2D), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    public static void applyHiddenLabels(Context context, MangaPost post) {
        if (post == null) return;
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel(context)) {
            post.latestChapter = "";
            post.latestChapterDate = "";
            post.totalChapters = 0;
        }
        if (!MangaSettingsManager.shouldLoadTypeLabel(context)) post.typeLabel = "";
    }

    public static boolean shouldEnrichLabels(Context context) {
        return MangaSettingsManager.shouldLoadLatestChapterLabel(context) || MangaSettingsManager.shouldLoadTypeLabel(context);
    }
}
