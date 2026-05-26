package miku.moe.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MangaWebtoonLayoutManager extends LinearLayoutManager {
    private final int extraLayoutSpace;

    public MangaWebtoonLayoutManager(Context context, int extraLayoutSpace) {
        super(context, RecyclerView.VERTICAL, false);
        this.extraLayoutSpace = Math.max(0, extraLayoutSpace);
        setItemPrefetchEnabled(false);
    }

    @Override
    protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
        if (state != null && state.isPreLayout()) {
            extraLayoutSpace[0] = 0;
            extraLayoutSpace[1] = 0;
            return;
        }
        extraLayoutSpace[0] = Math.min(this.extraLayoutSpace, 240);
        extraLayoutSpace[1] = Math.min(this.extraLayoutSpace, 240);
    }
}
