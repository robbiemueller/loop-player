package io.github.robbiemueller.loopplayer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Grabs a frame from a few seconds into each video, cached as a small JPEG on disk and as a bitmap in memory.
 * Shared by the swipe-up picker (which loads them in the background) and the phone remote (which serves the JPEGs).
 */
final class Thumbnails {
    interface Callback {
        void onThumbnail(File source, Bitmap bitmap);
    }

    private final File dir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<>(24);

    Thumbnails(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    void load(final File f, final Callback callback) {
        final String key = key(f);
        Bitmap cached = memory.get(key);
        if (cached != null) {
            callback.onThumbnail(f, cached);
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                File jpeg = jpeg(f);
                final Bitmap bitmap = jpeg == null ? null : BitmapFactory.decodeFile(jpeg.getPath());
                if (bitmap == null) return;
                memory.put(key, bitmap);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onThumbnail(f, bitmap);
                    }
                });
            }
        });
    }

    /** The cached JPEG for a video, made now if it isn't there yet (so call it off the main thread); null if the
     *  video can't be decoded. */
    synchronized File jpeg(File f) {
        File cacheFile = new File(dir, key(f).replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg");
        if (cacheFile.exists()) return cacheFile;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(f.getPath());
            long durationMs = 0;
            try {
                durationMs = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            } catch (Exception ignored) {
                // Unknown length: fall back to the first frame.
            }
            // A few seconds in, since loops often start on black.
            long at = Math.min(durationMs / 3, 12000) * 1000;
            Bitmap frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int w = 320;
            int h = Math.max(1, Math.round(frame.getHeight() * (w / (float) frame.getWidth())));
            Bitmap small = Bitmap.createScaledBitmap(frame, w, h, true);
            if (small != frame) frame.recycle();
            File part = new File(dir, cacheFile.getName() + ".part");
            FileOutputStream out = new FileOutputStream(part);
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
            out.close();
            small.recycle();
            return part.renameTo(cacheFile) ? cacheFile : null;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Some old devices throw from release(); nothing to clean up.
            }
        }
    }

    private static String key(File f) {
        return f.getName() + "-" + f.length() + "-" + f.lastModified();
    }
}
