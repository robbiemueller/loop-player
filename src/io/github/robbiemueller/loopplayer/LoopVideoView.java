package io.github.robbiemueller.loopplayer;

import android.content.Context;
import android.widget.VideoView;

/**
 * A VideoView that either fits the whole video on screen (letterboxed, the default) or crops it to fill the screen.
 * In fill mode it measures larger than its parent; the parent centres it and the screen edge crops the overflow.
 */
public class LoopVideoView extends VideoView {
    private int videoWidth;
    private int videoHeight;
    private boolean fill;

    public LoopVideoView(Context context) {
        super(context);
    }

    public void setFill(boolean fill) {
        this.fill = fill;
        requestLayout();
    }

    public void setVideoDimensions(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (videoWidth <= 0 || videoHeight <= 0) {
            setMeasuredDimension(w, h);
            return;
        }
        float sx = (float) w / videoWidth;
        float sy = (float) h / videoHeight;
        float scale = fill ? Math.max(sx, sy) : Math.min(sx, sy);
        setMeasuredDimension(Math.round(videoWidth * scale), Math.round(videoHeight * scale));
    }
}
