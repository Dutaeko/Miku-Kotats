package miku.moe.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.List;

public class BrowseSourceMihonAdapter extends BaseAdapter {
    public interface Listener { void onClick(MangaPost post); }
    public interface ChapterListener { void onChapterClick(MangaPost post); }

    public static final int MODE_COMPACT_GRID = 0;
    public static final int MODE_COMFORTABLE_GRID = 1;
    public static final int MODE_LIST = 2;

    private final Context context;
    private final List<MangaPost> data;
    private final Listener listener;
    private final ChapterListener chapterListener;
    private int mode = MODE_COMPACT_GRID;
    private boolean showTypeLabel;
    private boolean showLatestChapterLabel;
    private boolean boldTitle;

    public BrowseSourceMihonAdapter(Context context, List<MangaPost> data, Listener listener, ChapterListener chapterListener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        this.chapterListener = chapterListener;
    }

    public void bindFlags(boolean showTypeLabel, boolean showLatestChapterLabel, boolean boldTitle) {
        this.showTypeLabel = showTypeLabel;
        this.showLatestChapterLabel = showLatestChapterLabel;
        this.boldTitle = boldTitle;
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    @Override public int getCount() { return data == null ? 0 : data.size(); }
    @Override public Object getItem(int position) { return data.get(position); }
    @Override public long getItemId(int position) {
        MangaPost post = data.get(position);
        String key = itemKey(post);
        return key.isEmpty() ? position : key.hashCode();
    }
    @Override public boolean hasStableIds() { return true; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) { return mode == MODE_LIST ? 1 : 0; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        MangaPost post = data.get(position);
        if (mode == MODE_LIST) return getListView(post, convertView, parent);
        return getGridView(post, convertView, parent);
    }

    private View getGridView(MangaPost post, View convertView, ViewGroup parent) {
        GridHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof GridHolder)) {
            convertView = LayoutInflater.from(context).inflate(R.layout.ikiru_manga_grid_item, parent, false);
            holder = new GridHolder();
            holder.image = convertView.findViewById(R.id.imageView);
            holder.typeFlag = convertView.findViewById(R.id.typeFlagImageView);
            holder.title = convertView.findViewById(R.id.textView);
            holder.coverTitle = convertView.findViewById(R.id.coverTitleTextView);
            holder.coverChapter = convertView.findViewById(R.id.coverChapterTextView);
            holder.status = convertView.findViewById(R.id.statusTextView);
            holder.progress = convertView.findViewById(R.id.cardLoadingProgress);
            convertView.setTag(holder);
        } else {
            holder = (GridHolder) convertView.getTag();
        }
        bindCover(holder.image, post, holder);
        bindTypeFlag(holder.typeFlag, post);
        String title = post.title == null ? "" : post.title;
        bindTitle(holder.title, title);
        bindTitle(holder.coverTitle, title);
        boolean compact = mode == MODE_COMPACT_GRID;
        if (holder.title != null) holder.title.setVisibility(compact ? View.GONE : View.VISIBLE);
        if (holder.coverTitle != null) holder.coverTitle.setVisibility(compact ? View.VISIBLE : View.GONE);
        if (holder.status != null) holder.status.setVisibility(View.GONE);
        bindChapter(holder.coverChapter, post);
        if (holder.progress != null) holder.progress.setVisibility(View.GONE);
        convertView.setOnClickListener(v -> { if (listener != null) listener.onClick(post); });
        return convertView;
    }

    private View getListView(MangaPost post, View convertView, ViewGroup parent) {
        ListHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof ListHolder)) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(16), dp(8), dp(16), dp(8));
            root.setMinimumHeight(dp(56));
            root.setClickable(true);
            root.setForeground(resolveSelectableItemBackground());

            FrameLayout coverFrame = new FrameLayout(context);
            LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            coverFrame.setLayoutParams(coverParams);

            ShapeableImageView image = new ShapeableImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            coverFrame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView flag = new ImageView(context);
            flag.setScaleType(ImageView.ScaleType.FIT_XY);
            FrameLayout.LayoutParams flagParams = new FrameLayout.LayoutParams(dp(18), dp(13), Gravity.START | Gravity.TOP);
            coverFrame.addView(flag, flagParams);

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMarginStart(dp(16));
            textColumn.setLayoutParams(textParams);

            TextView title = new TextView(context);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextColor(ContextCompat.getColor(context, R.color.md_theme_onSurface));
            title.setTextSize(14);

            TextView chapter = new TextView(context);
            chapter.setSingleLine(true);
            chapter.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chapter.setTextColor(ContextCompat.getColor(context, R.color.md_theme_primary));
            chapter.setTextSize(12);

            textColumn.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            textColumn.addView(chapter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(coverFrame);
            root.addView(textColumn);

            holder = new ListHolder();
            holder.root = root;
            holder.image = image;
            holder.typeFlag = flag;
            holder.title = title;
            holder.chapter = chapter;
            root.setTag(holder);
            convertView = root;
        } else {
            holder = (ListHolder) convertView.getTag();
        }
        bindCover(holder.image, post, holder);
        bindTypeFlag(holder.typeFlag, post);
        bindTitle(holder.title, post.title == null ? "" : post.title);
        bindChapter(holder.chapter, post);
        holder.root.setOnClickListener(v -> { if (listener != null) listener.onClick(post); });
        return convertView;
    }

    private void bindCover(ImageView image, MangaPost post, Object holder) {
        if (image == null) return;
        String cover = post.coverImage == null ? "" : post.coverImage;
        String key = itemKey(post) + "|" + cover + "|" + post.getSourceId();
        String current = holder instanceof GridHolder ? ((GridHolder) holder).coverKey : ((ListHolder) holder).coverKey;
        if (!key.equals(current) || image.getDrawable() == null) {
            if (holder instanceof GridHolder) ((GridHolder) holder).coverKey = key;
            else ((ListHolder) holder).coverKey = key;
            image.animate().cancel();
            image.setAlpha(1f);
            image.setBackgroundColor(0x1F888888);
            MangaImageLoader.loadCoverForSource(image, cover, post.getSourceId());
        } else {
            image.animate().cancel();
            image.setAlpha(1f);
        }
    }

    private void bindTitle(TextView view, String title) {
        if (view == null) return;
        view.setText(title == null ? "" : title);
        view.setTypeface(Typeface.DEFAULT, boldTitle ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void bindTypeFlag(ImageView view, MangaPost post) {
        if (view == null) return;
        if (!showTypeLabel) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        String type = post == null ? "" : post.getTypeLabel();
        if ("MANHUA".equalsIgnoreCase(type)) view.setImageResource(R.drawable.ic_flag_china);
        else if ("MANHWA".equalsIgnoreCase(type)) view.setImageResource(R.drawable.ic_flag_korea);
        else view.setImageResource(R.drawable.ic_flag_japan);
    }

    private void bindChapter(TextView view, MangaPost post) {
        if (view == null) return;
        String chapter = post.latestChapter == null ? "" : post.latestChapter.trim();
        if (!showLatestChapterLabel || chapter.isEmpty()) {
            view.setText("");
            view.setVisibility(View.GONE);
            view.setOnClickListener(null);
            return;
        }
        view.setText(chapter);
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(chapterListener == null ? null : v -> chapterListener.onChapterClick(post));
    }

    private android.graphics.drawable.Drawable resolveSelectableItemBackground() {
        android.util.TypedValue outValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        return ContextCompat.getDrawable(context, outValue.resourceId);
    }

    private String itemKey(MangaPost post) {
        if (post == null) return "";
        if (post.slug != null && !post.slug.isEmpty()) return post.slug;
        return post.title == null ? "" : post.title;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static final class GridHolder {
        ImageView image;
        ImageView typeFlag;
        TextView title;
        TextView coverTitle;
        TextView coverChapter;
        TextView status;
        View progress;
        String coverKey;
    }

    private static final class ListHolder {
        LinearLayout root;
        ImageView image;
        ImageView typeFlag;
        TextView title;
        TextView chapter;
        String coverKey;
    }
}
