package io.github.robbiemueller.loopplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The swipe-up panel for choosing what loops. Tap a tag to loop every video with it (tap more tags to add theirs);
 * tap a video to add it to (or take it out of) the loop, in the order tapped; hold a video to loop just that one;
 * "All" loops everything, including videos added later; "Shuffle" plays the loop in random order.
 * Changes apply immediately; the panel only reports them through {@link Listener}.
 */
public class VideoPicker extends FrameLayout {
    public interface Listener {
        void onSelectionChanged(List<String> selected, boolean all);

        void onTagsChanged(List<String> tags);

        void onShuffleChanged(boolean shuffle);

        void onSolo(String name);

        void onClosed();
    }

    private static final int ACCENT = Color.rgb(0xe3, 0x9a, 0x3b);
    private static final android.graphics.ColorMatrixColorFilter GREYSCALE;

    static {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setSaturation(0);
        GREYSCALE = new android.graphics.ColorMatrixColorFilter(m);
    }
    private static final long AUTO_CLOSE_MS = 30000;

    private final Listener listener;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout sheet;
    private final GridView grid;
    private final TextView status;
    private final TextView allButton;
    private final TextView shuffleButton;
    private final LinearLayout chips;
    private final View chipScroller;
    private final Adapter adapter = new Adapter();
    private final Thumbnails thumbnails;

    private List<File> library = new ArrayList<>();
    /** The tiles on show: the whole library, or only the videos with the chosen tags while tags are on. */
    private List<File> shown = new ArrayList<>();
    private final List<String> selected = new ArrayList<>();
    private boolean all = true;
    private Tags tags = new Tags();
    private final List<String> activeTags = new ArrayList<>();
    private boolean shuffle;
    private String nowPlaying;
    private boolean open;

    private final Runnable autoClose = new Runnable() {
        @Override
        public void run() {
            close();
        }
    };

    public VideoPicker(Context context, Listener listener, Thumbnails thumbnails) {
        super(context);
        this.listener = listener;
        this.thumbnails = thumbnails;
        density = getResources().getDisplayMetrics().density;
        setVisibility(GONE);

        // Tapping the dimmed strip above the sheet closes it.
        setBackgroundColor(Color.argb(0x66, 0, 0, 0));
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });

        sheet = new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(Color.argb(0xf2, 0x12, 0x10, 0x0e));
        sheet.setPadding(dp(12), dp(10), dp(12), dp(12));
        sheet.setClickable(true);  // keep taps inside the sheet from closing it
        LayoutParams sheetParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.BOTTOM);
        sheetParams.topMargin = dp(90);
        addView(sheet, sheetParams);

        View handle = new View(context);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.argb(0x80, 0xff, 0xff, 0xff));
        pill.setCornerRadius(dp(3));
        handle.setBackground(pill);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(48), dp(5));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        sheet.addView(handle, handleParams);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(context, "Videos", 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        allButton = button(context, "All");
        allButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setAll();
            }
        });
        shuffleButton = button(context, "Shuffle");
        shuffleButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                bumpAutoClose();
                shuffle = !shuffle;
                updateStatus();
                listener.onShuffleChanged(shuffle);
            }
        });
        header.addView(shuffleButton);
        LinearLayout.LayoutParams allParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        allParams.leftMargin = dp(10);
        header.addView(allButton, allParams);
        TextView done = button(context, "Done");
        done.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        doneParams.leftMargin = dp(10);
        header.addView(done, doneParams);
        sheet.addView(header);

        // A row of tag buttons, scrolling sideways when there are many.
        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(chips);
        chipScroller = scroller;
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = dp(10);
        sheet.addView(scroller, chipParams);

        TextView hint = text(context, "Tap a tag or videos to build the loop  ·  Hold a video to loop just it",
                14, Color.argb(0xb0, 0xff, 0xff, 0xff));
        hint.setPadding(0, dp(6), 0, dp(4));
        sheet.addView(hint);
        status = text(context, "", 15, ACCENT);
        status.setPadding(0, 0, 0, dp(10));
        sheet.addView(status);

        grid = new GridView(context);
        grid.setVerticalSpacing(dp(10));
        grid.setHorizontalSpacing(dp(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                toggle(shown.get(position).getName());
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                String name = shown.get(position).getName();
                all = false;
                selected.clear();
                selected.add(name);
                adapter.notifyDataSetChanged();
                listener.onSolo(name);
                close();
                return true;
            }
        });
        sheet.addView(grid, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        // A downward fling anywhere on the sheet closes it.
        final GestureDetector flings = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                if (dy > dp(80) && vy > 600 && Math.abs(dy) > Math.abs(e2.getX() - e1.getX())) {
                    close();
                    return true;
                }
                return false;
            }
        });
        OnTouchListener closeOnFling = new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                bumpAutoClose();
                // Only steal the gesture when the grid is scrolled to the top, so it can still scroll.
                boolean atTop = grid.getChildCount() == 0
                        || (grid.getFirstVisiblePosition() == 0 && grid.getChildAt(0).getTop() >= 0);
                return atTop && flings.onTouchEvent(e);
            }
        };
        sheet.setOnTouchListener(closeOnFling);
        grid.setOnTouchListener(closeOnFling);
    }

    public boolean isOpen() {
        return open;
    }

    /** Fills the panel: the files on disk, the current loop (picks, All or tags), and what's on screen right now. */
    public void setLibrary(List<File> files, List<String> selectedNames, boolean allMode, String playing,
                           Tags videoTags, java.util.Set<String> loopTags, boolean shuffled) {
        library = new ArrayList<>(files);
        selected.clear();
        selected.addAll(selectedNames);
        all = allMode;
        nowPlaying = playing;
        tags = videoTags;
        activeTags.clear();
        activeTags.addAll(loopTags);
        shuffle = shuffled;
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / density);
        grid.setNumColumns(Math.max(2, widthDp / 260));
        buildChips();
        filter();
        updateStatus();
    }

    /** Picks the tiles to show, and goes back to the top when that changes. */
    private void filter() {
        List<File> was = shown;
        if (tagMode()) {
            shown = new ArrayList<>();
            for (File f : library) {
                if (tags.matches(f.getName(), activeTags)) shown.add(f);
            }
        } else {
            shown = library;
        }
        adapter.notifyDataSetChanged();
        if (!shown.equals(was)) {
            // After the grid has laid out the new tiles, or it keeps its old scroll position.
            grid.post(new Runnable() {
                @Override
                public void run() {
                    grid.setSelection(0);
                }
            });
        }
    }

    private void buildChips() {
        chips.removeAllViews();
        List<String> present = tags.present(library);
        chipScroller.setVisibility(present.isEmpty() ? GONE : VISIBLE);
        for (final String tag : present) {
            TextView chip = button(getContext(), tag);
            chip.setTextSize(16);
            chip.setPadding(dp(16), dp(7), dp(16), dp(7));
            boolean on = Tags.indexOf(activeTags, tag) >= 0;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? ACCENT : Color.argb(0x30, 0xff, 0xff, 0xff));
            bg.setCornerRadius(dp(20));
            chip.setBackground(bg);
            chip.setTextColor(on ? Color.BLACK : Color.WHITE);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleTag(tag);
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            p.rightMargin = dp(8);
            chips.addView(chip, p);
        }
    }

    private void toggleTag(String tag) {
        bumpAutoClose();
        int at = Tags.indexOf(activeTags, tag);
        if (at >= 0) activeTags.remove(at);
        else activeTags.add(tag);
        selected.clear();
        all = activeTags.isEmpty();
        buildChips();
        filter();
        updateStatus();
        if (all) listener.onSelectionChanged(new ArrayList<String>(), true);
        else listener.onTagsChanged(new ArrayList<>(activeTags));
    }

    private boolean tagMode() {
        return !activeTags.isEmpty();
    }

    public void setNowPlaying(String playing) {
        nowPlaying = playing;
        if (open) adapter.notifyDataSetChanged();
    }

    public void open() {
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        sheet.setTranslationY(getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels);
        sheet.animate().translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator(2f)).start();
        setAlpha(0f);
        animate().alpha(1f).setDuration(160).start();
        // Tiles size themselves from the grid's width, which is only known after the first layout.
        grid.post(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });
        bumpAutoClose();
    }

    public void close() {
        if (!open) return;
        open = false;
        handler.removeCallbacks(autoClose);
        sheet.animate().translationY(sheet.getHeight()).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
        animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (!open) setVisibility(GONE);
            }
        }).start();
        listener.onClosed();
    }

    private void bumpAutoClose() {
        handler.removeCallbacks(autoClose);
        handler.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    private void toggle(String name) {
        bumpAutoClose();
        if (all || tagMode()) {
            // Leaving "All" or the tags: the first tap starts a hand-picked loop with just that video.
            all = false;
            activeTags.clear();
            buildChips();
            selected.clear();
            selected.add(name);
        } else if (selected.contains(name)) {
            selected.remove(name);
            if (selected.isEmpty()) all = true;
        } else {
            selected.add(name);
        }
        filter();
        updateStatus();
        listener.onSelectionChanged(new ArrayList<>(selected), all);
    }

    private void setAll() {
        bumpAutoClose();
        all = true;
        selected.clear();
        activeTags.clear();
        buildChips();
        filter();
        updateStatus();
        listener.onSelectionChanged(new ArrayList<String>(), true);
    }

    private void updateStatus() {
        String order = shuffle ? ", shuffled" : ", in order";
        if (tagMode()) {
            int n = tags.count(library, new java.util.LinkedHashSet<>(activeTags));
            status.setText(String.format(Locale.US, "Looping %d %s video%s%s", n, android.text.TextUtils.join(" + ", activeTags),
                    n == 1 ? "" : "s", n == 1 ? "" : order));
        } else if (all) {
            status.setText(String.format(Locale.US, "Looping all %d videos%s", library.size(), order));
        } else if (selected.size() == 1) {
            status.setText("Looping one video over and over");
        } else {
            status.setText(String.format(Locale.US, "Looping %d videos back to back%s", selected.size(), order));
        }
        allButton.setAlpha(all ? 0.4f : 1f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(shuffle ? ACCENT : Color.argb(0x40, 0xff, 0xff, 0xff));
        bg.setCornerRadius(dp(20));
        shuffleButton.setBackground(bg);
        shuffleButton.setTextColor(shuffle ? Color.BLACK : Color.WHITE);
        shuffleButton.setText(shuffle ? "⇄ Shuffle" : "Shuffle");
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(Context c, String s) {
        TextView b = text(c, s, 18, Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(0x40, 0xff, 0xff, 0xff));
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        b.setClickable(true);
        return b;
    }

    /** Pretty name for a file: "02-tiki_torch.mp4" becomes "tiki torch". */
    static String displayName(String fileName) {
        String s = fileName.replaceFirst("\\.[^.]+$", "").replaceFirst("^\\d+[-_ ]+", "");
        return s.replace('-', ' ').replace('_', ' ');
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Tile tile = convertView instanceof Tile ? (Tile) convertView : new Tile(getContext());
            File f = shown.get(position);
            int columns = Math.max(1, grid.getNumColumns());
            int width = (grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight() - dp(10) * (columns - 1)) / columns;
            boolean everyone = all || (tagMode() && tags.matches(f.getName(), activeTags));
            tile.bind(f, everyone ? -1 : selected.indexOf(f.getName()), everyone, f.getName().equals(nowPlaying), width);
            return tile;
        }
    }

    private class Tile extends FrameLayout {
        private final ImageView image;
        private final TextView label;
        private final TextView badge;
        private final TextView playing;
        private final GradientDrawable border = new GradientDrawable();
        private File file;

        Tile(Context c) {
            super(c);
            setPadding(dp(3), dp(3), dp(3), dp(3));
            border.setCornerRadius(dp(8));
            setBackground(border);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Color.rgb(0x22, 0x22, 0x22));
            addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            label = text(c, "", 15, Color.WHITE);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(dp(8), dp(18), dp(8), dp(6));
            label.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[] {Color.argb(0xd0, 0, 0, 0), Color.TRANSPARENT}));
            addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            badge = text(c, "", 16, Color.BLACK);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setGravity(Gravity.CENTER);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(ACCENT);
            badge.setBackground(dot);
            LayoutParams badgeParams = new LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.START);
            badgeParams.setMargins(dp(6), dp(6), 0, 0);
            addView(badge, badgeParams);
            playing = text(c, "▶ NOW", 12, Color.WHITE);
            playing.setTypeface(Typeface.DEFAULT_BOLD);
            playing.setPadding(dp(6), dp(2), dp(6), dp(2));
            playing.setBackgroundColor(Color.argb(0xc0, 0xb0, 0x20, 0x20));
            LayoutParams playingParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            playingParams.setMargins(0, dp(8), dp(8), 0);
            addView(playing, playingParams);
        }

        void bind(File f, int order, boolean allMode, boolean isPlaying, int width) {
            file = f;
            if (width > 0) setLayoutParams(new AbsListParams(width, Math.round(width * 1.25f)));
            label.setText(displayName(f.getName()));
            boolean inLoop = allMode || order >= 0;
            border.setColor(inLoop ? ACCENT : Color.TRANSPARENT);
            image.setAlpha(inLoop ? 1f : 0.5f);
            image.setColorFilter(inLoop ? null : GREYSCALE);
            badge.setVisibility(order >= 0 ? VISIBLE : GONE);
            badge.setText(String.valueOf(order + 1));
            playing.setVisibility(isPlaying ? VISIBLE : GONE);
            image.setImageBitmap(null);
            thumbnails.load(f, new Thumbnails.Callback() {
                @Override
                public void onThumbnail(File source, Bitmap bitmap) {
                    if (source.equals(file)) image.setImageBitmap(bitmap);
                }
            });
        }
    }

    private static class AbsListParams extends android.widget.AbsListView.LayoutParams {
        AbsListParams(int w, int h) {
            super(w, h);
        }
    }
}
