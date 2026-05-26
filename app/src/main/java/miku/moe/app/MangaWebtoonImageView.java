package miku.moe.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatImageView;

public class MangaWebtoonImageView extends AppCompatImageView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect cropRect = new Rect();
    private final Rect drawRect = new Rect();
    private boolean cropBorderEnabled;
    private String readerScaleType = MangaSettingsManager.IMAGE_SCALE_FIT_WIDTH;
    private int lastBitmapWidth = -1;
    private int lastBitmapHeight = -1;
    private boolean cropReady;

    public MangaWebtoonImageView(Context context) {
        super(context);
        init();
    }

    public MangaWebtoonImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MangaWebtoonImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setAdjustViewBounds(false);
        setScaleType(ScaleType.FIT_CENTER);
        setCropToPadding(false);
        setWillNotCacheDrawing(true);
    }

    public void setCropBorderEnabled(boolean enabled) {
        cropBorderEnabled = enabled;
        cropReady = false;
        requestLayout();
        invalidate();
    }

    public void setReaderScaleType(String type) {
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(type) || MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(type)) readerScaleType = type;
        else readerScaleType = MangaSettingsManager.IMAGE_SCALE_FIT_WIDTH;
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Drawable drawable = getDrawable();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int imageWidth = drawable == null ? 0 : drawable.getIntrinsicWidth();
        int imageHeight = drawable == null ? 0 : drawable.getIntrinsicHeight();
        if (cropBorderEnabled && cropReady && cropRect.width() > 0 && cropRect.height() > 0) {
            imageWidth = cropRect.width();
            imageHeight = cropRect.height();
        }
        int height;
        if (MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType)) {
            height = Math.max(dp(240), getResources().getDisplayMetrics().heightPixels);
        } else if (MangaSettingsManager.IMAGE_SCALE_ORIGINAL.equals(readerScaleType) && imageWidth > 0 && imageHeight > 0) {
            float density = getResources().getDisplayMetrics().density;
            int targetWidth = Math.min(width, Math.max(1, Math.round(imageWidth / Math.max(1f, density))));
            height = Math.round(targetWidth * (imageHeight / (float) imageWidth));
        } else if (imageWidth > 0 && imageHeight > 0) {
            height = Math.round(width * (imageHeight / (float) imageWidth));
        } else {
            height = dp(520);
        }
        if (height <= 0) height = dp(520);
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!cropBorderEnabled && !MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType)) {
            super.onDraw(canvas);
            return;
        }
        Bitmap bitmap = getBitmap();
        if (bitmap == null || bitmap.isRecycled()) {
            super.onDraw(canvas);
            return;
        }
        Rect src = cropBorderEnabled ? getCropRectSafely() : new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        if (src.width() <= 0 || src.height() <= 0) {
            super.onDraw(canvas);
            return;
        }
        makeDrawRect(src);
        canvas.drawBitmap(bitmap, src, drawRect, paint);
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        cropReady = false;
        lastBitmapWidth = -1;
        lastBitmapHeight = -1;
        requestLayout();
    }

    private void makeDrawRect(Rect src) {
        int viewWidth = Math.max(1, getWidth());
        int viewHeight = Math.max(1, getHeight());
        float scale = MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType)
                ? Math.min(viewWidth / (float) src.width(), viewHeight / (float) src.height())
                : viewWidth / (float) src.width();
        int width = Math.max(1, Math.round(src.width() * scale));
        int height = Math.max(1, Math.round(src.height() * scale));
        int left = (viewWidth - width) / 2;
        int top = MangaSettingsManager.IMAGE_SCALE_FIT_SCREEN.equals(readerScaleType) ? (viewHeight - height) / 2 : 0;
        drawRect.set(left, top, left + width, top + height);
    }

    private Rect getCropRectSafely() {
        Bitmap bitmap = getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return new Rect(0, 0, 0, 0);
        if (cropReady && lastBitmapWidth == bitmap.getWidth() && lastBitmapHeight == bitmap.getHeight()) return cropRect;
        lastBitmapWidth = bitmap.getWidth();
        lastBitmapHeight = bitmap.getHeight();
        cropRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        try {
            detectBorder(bitmap, cropRect);
            cropReady = true;
        } catch (Exception e) {
            cropRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
            cropReady = true;
        }
        return cropRect;
    }

    private Bitmap getBitmap() {
        Drawable drawable = getDrawable();
        if (drawable instanceof BitmapDrawable) return ((BitmapDrawable) drawable).getBitmap();
        return null;
    }

    private void detectBorder(Bitmap bitmap, Rect out) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int stepX = Math.max(2, width / 48);
        int stepY = Math.max(2, height / 72);
        int left = 0;
        int right = width - 1;
        int top = 0;
        int bottom = height - 1;
        while (top < bottom && isBorderRow(bitmap, top, stepX)) top += stepY;
        while (bottom > top && isBorderRow(bitmap, bottom, stepX)) bottom -= stepY;
        while (left < right && isBorderColumn(bitmap, left, stepY)) left += stepX;
        while (right > left && isBorderColumn(bitmap, right, stepY)) right -= stepX;
        left = Math.max(0, left - stepX);
        top = Math.max(0, top - stepY);
        right = Math.min(width - 1, right + stepX);
        bottom = Math.min(height - 1, bottom + stepY);
        if (right - left > width * 0.45f && bottom - top > height * 0.45f) out.set(left, top, right + 1, bottom + 1);
        else out.set(0, 0, width, height);
    }

    private boolean isBorderRow(Bitmap bitmap, int y, int step) {
        int width = bitmap.getWidth();
        int total = 0;
        int border = 0;
        for (int x = 0; x < width; x += step) {
            total++;
            if (isBorderColor(bitmap.getPixel(x, y))) border++;
        }
        return total > 0 && border >= total * 0.92f;
    }

    private boolean isBorderColumn(Bitmap bitmap, int x, int step) {
        int height = bitmap.getHeight();
        int total = 0;
        int border = 0;
        for (int y = 0; y < height; y += step) {
            total++;
            if (isBorderColor(bitmap.getPixel(x, y))) border++;
        }
        return total > 0 && border >= total * 0.92f;
    }

    private boolean isBorderColor(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return (r > 235 && g > 235 && b > 235) || (r < 24 && g < 24 && b < 24);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
