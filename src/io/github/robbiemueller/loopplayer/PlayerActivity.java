package io.github.robbiemueller.loopplayer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Full-screen, control-free video loop for signage screens, old tablets and the like (Android 5.0+).
 *
 * Plays the videos in /sdcard/Movies/loop/ forever: all of them in name order (leading numbers sorted as numbers), the ones with chosen tags (see
 * {@link Tags}), or the ones picked in the swipe-up panel, in the order picked (a single video loops seamlessly inside
 * the media player). Shuffle plays the loop in a fresh random order each time round. Swipe left for the next video,
 * right for the one before. Double-tap toggles the sound; holding a finger down for 3 seconds opens the settings
 * menu, where the phone remote can be turned on.
 * From a computer: adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity with
 *   --ez reload true           rescan the folder
 *   --es play name.mp4         loop just that video
 *   --es playlist a.mp4,b.mp4  loop these, in this order
 *   --es tags Halloween,Music  loop every video with any of these tags
 *   --ez all true              loop everything in the folder
 *   --ez shuffle true          random order (false: in order)
 *   --ez next true             skip to the next video (previous true: back one)
 *   --ez mute true             sound off (false: on)
 *   --ez fill true             crop to fill the screen (false: fit the whole video)
 *   --ez home true             use as the home screen (false: stop)
 *   --ez remote false          phone remote off (it can only be turned on from the hold menu)
 *   --ez sleep true            screen dark and playback paused (false: wake)
 */
public class PlayerActivity extends Activity implements VideoPicker.Listener {
    private static final long MENU_HOLD_MS = 3000;
    private static final int PERMISSION_REQUEST = 1;
    private static final int WRITE_REQUEST = 2;
    private static final int HINT_LAUNCHES = 5;
    private static final int REMOTE_PORT = 8080;
    private static final long FREE_SPACE_MARGIN = 32L * 1024 * 1024;
    private static final String WRITE_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE";
    // View.SYSTEM_UI_FLAG_* values, written out so this builds against any SDK level.
    private static final int IMMERSIVE = 0x00000100 | 0x00000200 | 0x00000400 | 0x00000002 | 0x00000004 | 0x00001000;

    private final Handler handler = new Handler();
    private LoopVideoView video;
    private TextView notice;
    private VideoPicker picker;
    private View sleepCover;
    private MediaPlayer player;
    private Thumbnails thumbnails;
    private AudioManager audio;
    private volatile RemoteServer remote;
    private final Object remoteLock = new Object();
    private Schedule schedule;
    private List<File> library = new ArrayList<>();
    private List<File> playlist = new ArrayList<>();
    /** The playlist in the order it plays: as is, or shuffled once per time round. */
    private List<File> queue = new ArrayList<>();
    /** What played before, for swiping back; newest last. */
    private final List<File> history = new ArrayList<>();
    /** Videos played since each interlude last came up, and the video to carry on from after one. */
    private final Map<String, Integer> sinceInterlude = new HashMap<>();
    private File beforeInterlude;
    /** An interlude opened an app; when we're back in front, go on to the next video. */
    private boolean nextOnReturn;
    private File current;
    private boolean allMode;
    private List<String> selection = new ArrayList<>();
    private Tags tags = new Tags();
    /** Non-empty: loop every video with any of these tags (this wins over All and the picks). */
    private Set<String> activeTags = new LinkedHashSet<>();
    private boolean shuffle;
    private final Random random = new Random();
    private boolean muted;
    private boolean fill;
    private boolean asleep;
    private boolean inFront;
    private int brightness;
    private SharedPreferences prefs;
    private float downX, downY;

    private final Runnable openMenuAfterHold = new Runnable() {
        @Override
        public void run() {
            showMenu();
        }
    };
    private final Runnable hideNotice = new Runnable() {
        @Override
        public void run() {
            notice.setVisibility(View.GONE);
        }
    };
    /** Checks the schedule at the start of every minute. */
    private final Runnable scheduleTick = new Runnable() {
        @Override
        public void run() {
            checkSchedule();
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        muted = prefs.getBoolean("muted", false);
        fill = prefs.getBoolean("fill", false);
        allMode = prefs.getBoolean("all", true);
        asleep = prefs.getBoolean("asleep", false);
        brightness = prefs.getInt("brightness", Schedule.AUTO_BRIGHTNESS);
        schedule = Schedule.load(prefs.getString("schedule", ""));
        String saved = prefs.getString("selection", "");
        if (!saved.isEmpty()) selection = new ArrayList<>(Arrays.asList(saved.split("\n")));
        String savedTags = prefs.getString("tags", "");
        if (!savedTags.isEmpty()) activeTags = new LinkedHashSet<>(Arrays.asList(savedTags.split("\n")));
        shuffle = prefs.getBoolean("shuffle", false);
        thumbnails = new Thumbnails(new File(getCacheDir(), "thumbs"));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new LoopVideoView(this);
        video.setFill(fill);
        root.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        picker = new VideoPicker(this, this, thumbnails);
        root.addView(picker, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // Asleep: a black cover over everything; a tap on it wakes the screen until the schedule says otherwise.
        sleepCover = new View(this);
        sleepCover.setBackgroundColor(Color.BLACK);
        sleepCover.setVisibility(View.GONE);
        sleepCover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAsleep(false);
            }
        });
        root.addView(sleepCover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        notice = new TextView(this);
        notice.setTextColor(Color.WHITE);
        notice.setBackgroundColor(Color.argb(0xc0, 0, 0, 0));
        notice.setTextSize(22);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(36, 20, 36, 20);
        notice.setVisibility(View.GONE);
        root.addView(notice, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        final float density = getResources().getDisplayMetrics().density;
        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return openFromInterlude();
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                setMuted(!muted);
                showNotice(muted ? "Sound off" : "Sound on");
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (dy < -100 * density && vy < -600 && Math.abs(dy) > Math.abs(dx)) {
                    openPicker();
                    return true;
                }
                if (dy > 100 * density && vy > 600 && Math.abs(dy) > Math.abs(dx)) {
                    goHome();
                    return true;
                }
                // Sideways: left for the next video, right for the one before.
                if (Math.abs(dx) > 80 * density && Math.abs(vx) > 500 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    skip(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                gestures.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        handler.postDelayed(openMenuAfterHold, MENU_HOLD_MS);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) {
                            handler.removeCallbacks(openMenuAfterHold);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(openMenuAfterHold);
                        break;
                }
                return true;
            }
        });

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                player = mp;
                updateLooping();
                video.setVideoDimensions(mp.getVideoWidth(), mp.getVideoHeight());
                applyVolume();
                if (!asleep) video.start();
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                playNext();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                // Skip a file the decoder can't handle instead of showing a dialog.
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        playNext();
                    }
                }, 1000);
                return true;
            }
        });

        if (asleep) sleepCover.setVisibility(View.VISIBLE);
        applyBrightness();
        boolean restart = handleIntent(getIntent());
        if (hasVideoPermission()) {
            startPlaylist();
            int launches = prefs.getInt("launches", 0);
            if (launches < HINT_LAUNCHES && !restart && !asleep) {
                prefs.edit().putInt("launches", launches + 1).apply();
                showNotice("Swipe up to choose videos", 2500);
            }
        } else {
            requestPermissions(new String[] {videoPermission()}, PERMISSION_REQUEST);
        }
        if (prefs.getBoolean("remote", false)) startRemote(false);
        handler.post(scheduleTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(scheduleTick);
        stopRemote();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleIntent(intent) && hasVideoPermission()) startPlaylist();
    }

    /** Applies extras sent from a computer; returns true when the playlist should start over. */
    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        boolean restart = intent.getBooleanExtra("reload", false);
        if (intent.hasExtra("mute")) setMuted(intent.getBooleanExtra("mute", false));
        if (intent.hasExtra("fill")) setFill(intent.getBooleanExtra("fill", false));
        if (intent.hasExtra("home")) setHome(intent.getBooleanExtra("home", false));
        // Any app can send this activity an intent, so intents may only turn the remote off, never on.
        if (intent.hasExtra("remote") && !intent.getBooleanExtra("remote", true)) setRemote(false);
        if (intent.hasExtra("sleep")) setAsleep(intent.getBooleanExtra("sleep", false));
        if (intent.hasExtra("shuffle")) setShuffle(intent.getBooleanExtra("shuffle", false));
        if (intent.getBooleanExtra("all", false)) {
            saveSelection(new ArrayList<String>(), true);
            restart = true;
        }
        String tagList = intent.getStringExtra("tags");
        if (!TextUtils.isEmpty(tagList)) {
            saveTags(new LinkedHashSet<>(Arrays.asList(tagList.split(","))));
            restart = true;
        }
        if (!restart && intent.getBooleanExtra("next", false)) skip(1);
        if (!restart && intent.getBooleanExtra("previous", false)) skip(-1);
        String play = intent.getStringExtra("play");
        if (!TextUtils.isEmpty(play)) {
            saveSelection(new ArrayList<>(Arrays.asList(play)), false);
            restart = true;
        }
        String list = intent.getStringExtra("playlist");
        if (!TextUtils.isEmpty(list)) {
            saveSelection(new ArrayList<>(Arrays.asList(list.split(","))), false);
            restart = true;
        }
        return restart;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startPlaylist();
        } else {
            showStickyNotice("Loop Player needs access to videos.\nHold 3 s for the menu.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        hideSystemUi();
        if (nextOnReturn) {
            // Back from the app an interlude opened: on to the next video rather than the interlude again.
            nextOnReturn = false;
            if (isInterlude(current)) playNext();
        }
        if (!asleep && !video.isPlaying() && !playlist.isEmpty()) video.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inFront = false;
        handler.removeCallbacks(openMenuAfterHold);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (picker.isOpen()) {
            picker.close();
        } else {
            super.onBackPressed();
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
    }

    private String videoPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_VIDEO"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    private boolean hasVideoPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(videoPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 6 to 10 need the storage write permission for uploads and deletes from the phone remote. */
    private boolean needsWritePermission() {
        return Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 29
                && checkSelfPermission(WRITE_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    // ---- The library and the loop ----

    /** Name order, but a leading number counts as a number, so 100-x comes after 99-x rather than after 10-x. */
    static final Comparator<File> BY_NUMBER = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            long na = leadingNumber(a.getName()), nb = leadingNumber(b.getName());
            if (na != nb) return na < 0 ? 1 : nb < 0 ? -1 : Long.compare(na, nb);
            return a.getName().compareTo(b.getName());
        }
    };

    private static long leadingNumber(String name) {
        int i = 0;
        while (i < name.length() && i < 18 && Character.isDigit(name.charAt(i))) i++;
        return i == 0 ? -1 : Long.parseLong(name.substring(0, i));
    }

    private File folder() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "loop");
    }

    private void scanLibrary() {
        File[] files = folder().listFiles();
        library = new ArrayList<>();
        tags = Tags.load(new File(folder(), Tags.FILE_NAME));
        if (files == null) return;
        Arrays.sort(files, BY_NUMBER);
        for (File f : files) {
            // Interludes stay out of the library: they come up on their own every so often.
            if (f.isFile() && isVideoName(f.getName()) && tags.interlude(f.getName()) == null) library.add(f);
        }
    }

    private boolean isInterlude(File f) {
        return f != null && tags.interlude(f.getName()) != null;
    }

    /** Counts one more video played and returns an interlude whose turn has come, starting its count again. */
    private File dueInterlude() {
        File due = null;
        for (Tags.Interlude i : tags.interludes()) {
            File f = new File(folder(), i.video);
            if (!f.isFile()) continue;
            Integer n = sinceInterlude.get(i.video);
            int count = (n == null ? 0 : n) + 1;
            if (due == null && count >= i.every) {
                due = f;
                count = 0;
            }
            sinceInterlude.put(i.video, count);
        }
        return due;
    }

    /** Swipe down: the device's home screen, when that's another app (a launcher with more on the device). */
    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (r == null || r.activityInfo == null || getPackageName().equals(r.activityInfo.packageName)) return;
        startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** A tap during an interlude that names an app opens that app; when it closes, the loop carries on. */
    private boolean openFromInterlude() {
        Tags.Interlude i = current == null ? null : tags.interlude(current.getName());
        if (i == null || i.open.isEmpty()) return false;
        // "com.example.app" opens the app; "com.example.app/.SomeActivity" opens that screen of it.
        ComponentName screen = i.open.contains("/") ? ComponentName.unflattenFromString(i.open) : null;
        Intent app = screen != null ? new Intent(Intent.ACTION_MAIN).setComponent(screen)
                : getPackageManager().getLaunchIntentForPackage(i.open);
        if (app == null || getPackageManager().resolveActivity(app, 0) == null) {
            showNotice("Not installed: " + i.open);
            return true;
        }
        nextOnReturn = true;
        startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    /** Tag mode is on and at least one video on disk has one of the tags. */
    private boolean tagMode() {
        return !activeTags.isEmpty() && tags.count(library, activeTags) > 0;
    }

    private static boolean isVideoName(String name) {
        String n = name.toLowerCase(Locale.US);
        return !n.startsWith(".") && (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm"));
    }

    /** The loop: the tagged videos in tag mode; every video in "all" mode (or when none of the picked ones exist);
     *  else the picked ones. */
    private List<File> buildPlaylist() {
        if (tagMode()) {
            List<File> tagged = new ArrayList<>();
            for (File f : library) {
                if (tags.matches(f.getName(), activeTags)) tagged.add(f);
            }
            return tagged;
        }
        if (allMode) return new ArrayList<>(library);
        List<File> picked = new ArrayList<>();
        for (String name : selection) {
            for (File f : library) {
                if (f.getName().equals(name)) picked.add(f);
            }
        }
        return picked.isEmpty() ? new ArrayList<>(library) : picked;
    }

    private void startPlaylist() {
        scanLibrary();
        playlist = buildPlaylist();
        if (playlist.isEmpty()) {
            video.stopPlayback();
            current = null;
            showStickyNotice("No videos in\n" + folder().getPath());
            return;
        }
        notice.setVisibility(View.GONE);
        queue = makeQueue(null);
        history.clear();
        play(queue.get(0));
    }

    /** The playlist in playing order. Shuffled, with {@code first} (if it's in the loop) kept at the front so the
     *  video on screen carries on and the rest follow in a new random order. */
    private List<File> makeQueue(File first) {
        List<File> q = new ArrayList<>(playlist);
        if (shuffle) {
            Collections.shuffle(q, random);
            if (first != null && q.remove(first)) q.add(0, first);
        }
        return q;
    }

    /** After the loop changed: a new queue, and switch videos only if the one on screen left the loop. */
    private void loopChanged() {
        playlist = buildPlaylist();
        if (playlist.isEmpty()) return;
        boolean keep = current != null && playlist.contains(current);
        queue = makeQueue(keep ? current : null);
        if (keep) {
            updateLooping();
        } else {
            play(queue.get(0));
        }
    }

    private void playNext() {
        if (queue.isEmpty()) return;
        // After an interlude, carry on from the video that played before it.
        boolean fromInterlude = isInterlude(current);
        File last = fromInterlude ? beforeInterlude : current;
        if (!fromInterlude) {
            remember(current);
            File due = dueInterlude();
            if (due != null) {
                beforeInterlude = current;
                play(due);
                return;
            }
        }
        int at = queue.indexOf(last);
        if (at + 1 >= queue.size() && shuffle && queue.size() > 1) {
            // Round again in a new order, without repeating the video that just played.
            queue = makeQueue(null);
            if (queue.get(0).equals(last)) Collections.swap(queue, 0, 1 + random.nextInt(queue.size() - 1));
            play(queue.get(0));
            return;
        }
        play(queue.get(at < 0 ? 0 : (at + 1) % queue.size()));
    }

    /** Swipes and the remote: +1 skips ahead, -1 goes back to what played before. */
    private void skip(int dir) {
        if (queue.isEmpty() || asleep) return;
        if (dir > 0) {
            playNext();
        } else {
            File back = null;
            while (back == null && !history.isEmpty()) {
                File f = history.remove(history.size() - 1);
                if (f.isFile() && !f.equals(current)) back = f;
            }
            if (back == null) {
                // Nothing played before this: step back through the queue instead.
                int at = queue.indexOf(current);
                back = queue.get(at <= 0 ? queue.size() - 1 : at - 1);
            }
            play(back);
            updateLooping();
        }
        if (current != null) showNotice((dir > 0 ? "▶▶  " : "◀◀  ") + VideoPicker.displayName(current.getName()));
    }

    private void remember(File f) {
        if (f == null) return;
        history.add(f);
        if (history.size() > 50) history.remove(0);
    }

    private void play(File f) {
        current = f;
        picker.setNowPlaying(f.getName());
        video.setVideoURI(Uri.fromFile(f));
    }

    /** One video loops inside the media player (seamless); several play back to back via onCompletion. */
    private void updateLooping() {
        if (player == null) return;
        try {
            player.setLooping(playlist.size() == 1 && playlist.get(0).equals(current));
        } catch (IllegalStateException ignored) {
            // Between files; the next onPrepared sets it.
        }
    }

    /** All or hand-picked videos; this leaves tag mode. */
    private void saveSelection(List<String> names, boolean all) {
        allMode = all;
        selection = names;
        activeTags = new LinkedHashSet<>();
        prefs.edit().putBoolean("all", all).putString("selection", TextUtils.join("\n", names))
                .putString("tags", "").apply();
    }

    /** Tag mode: every video with any of these tags. An empty set goes back to All. */
    private void saveTags(Set<String> wanted) {
        Set<String> clean = new LinkedHashSet<>();
        for (String t : wanted) {
            if (!t.trim().isEmpty()) clean.add(t.trim());
        }
        if (clean.isEmpty()) {
            saveSelection(new ArrayList<String>(), true);
            return;
        }
        activeTags = clean;
        allMode = false;
        selection = new ArrayList<>();
        prefs.edit().putBoolean("all", false).putString("selection", "")
                .putString("tags", TextUtils.join("\n", clean)).apply();
    }

    private void setShuffle(boolean value) {
        shuffle = value;
        prefs.edit().putBoolean("shuffle", shuffle).apply();
        queue = makeQueue(current);
    }

    private void openPicker() {
        handler.removeCallbacks(openMenuAfterHold);
        scanLibrary();
        boolean byTag = tagMode();
        picker.setLibrary(library, effectiveSelection(), !byTag && (allMode || effectiveSelection().isEmpty()),
                current == null ? null : current.getName(), tags, byTag ? activeTags : new LinkedHashSet<String>(), shuffle);
        picker.open();
    }

    /** The picks that still exist on disk, in order; none left means everything plays. */
    private List<String> effectiveSelection() {
        List<String> existing = new ArrayList<>();
        if (allMode || tagMode()) return existing;
        for (File f : buildPlaylist()) {
            if (selection.contains(f.getName())) existing.add(f.getName());
        }
        return existing;
    }

    @Override
    public void onSelectionChanged(List<String> selected, boolean all) {
        saveSelection(selected, all);
        // What's on screen keeps playing if it's still in the loop; if not, the loop starts right away.
        loopChanged();
    }

    @Override
    public void onTagsChanged(List<String> wanted) {
        saveTags(new LinkedHashSet<>(wanted));
        loopChanged();
    }

    @Override
    public void onShuffleChanged(boolean value) {
        setShuffle(value);
        if (!asleep) showNotice(value ? "Shuffle: on" : "In order");
    }

    @Override
    public void onSolo(String name) {
        saveSelection(new ArrayList<>(Arrays.asList(name)), false);
        loopChanged();
        if (!asleep) showNotice("Looping " + VideoPicker.displayName(name));
    }

    @Override
    public void onClosed() {
        hideSystemUi();
    }

    /** After files were added or removed: rescan, and move on if what's on screen is gone. */
    private void libraryChanged() {
        scanLibrary();
        if (buildPlaylist().isEmpty()) {
            startPlaylist();
        } else {
            loopChanged();
        }
        if (picker.isOpen()) openPicker();
    }

    // ---- Sleep, brightness and the schedule ----

    /** Asleep: the screen goes black at its lowest backlight and playback pauses. A tap, the remote or the schedule
     *  wakes it. */
    private void setAsleep(boolean value) {
        if (value == asleep) return;
        asleep = value;
        prefs.edit().putBoolean("asleep", asleep).apply();
        if (asleep) {
            if (picker.isOpen()) picker.close();
            notice.setVisibility(View.GONE);
            sleepCover.setVisibility(View.VISIBLE);
            video.pause();
        } else {
            sleepCover.setVisibility(View.GONE);
            if (current == null || !playlist.contains(current)) startPlaylist();
            else video.start();
        }
        applyBrightness();
    }

    /** Percent 1..100, or Schedule.AUTO_BRIGHTNESS for the system setting. */
    private void setBrightness(int value) {
        brightness = value == Schedule.AUTO_BRIGHTNESS ? value : Math.max(1, Math.min(100, value));
        prefs.edit().putInt("brightness", brightness).apply();
        applyBrightness();
    }

    private void applyBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = asleep ? 0f : brightness < 0 ? -1f : brightness / 100f;
        getWindow().setAttributes(lp);
    }

    private void setSchedule(Schedule value) {
        schedule = value;
        try {
            prefs.edit().putString("schedule", value.toJson().toString()).apply();
        } catch (Exception ignored) {
            // toJson only fails on a broken rule, which parse() already rejected.
        }
        // A new schedule takes effect from its next time on, not by jumping to whatever it says right now.
        markScheduleDone();
    }

    /** Applies the rule in force now, once: each rule fires on the day it comes round, then leaves things alone. */
    private void checkSchedule() {
        String key = scheduleKey();
        if (key == null || key.equals(prefs.getString("schedule_done", ""))) return;
        prefs.edit().putString("schedule_done", key).apply();
        applyRule(schedule.activeAt(minuteOfDay(Calendar.getInstance())));
    }

    private void markScheduleDone() {
        String key = scheduleKey();
        prefs.edit().putString("schedule_done", key == null ? "" : key).apply();
    }

    /** Identifies the latest firing of the rule in force now: its date, time and contents. */
    private String scheduleKey() {
        if (!schedule.enabled || schedule.rules.isEmpty()) return null;
        Calendar now = Calendar.getInstance();
        Schedule.Rule rule = schedule.activeAt(minuteOfDay(now));
        if (rule.minute > minuteOfDay(now)) now.add(Calendar.DAY_OF_YEAR, -1);  // it last fired yesterday
        try {
            return now.get(Calendar.YEAR) + "-" + now.get(Calendar.DAY_OF_YEAR) + " " + rule.toJson();
        } catch (Exception e) {
            return null;
        }
    }

    private static int minuteOfDay(Calendar c) {
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private void applyRule(Schedule.Rule rule) {
        if (rule.loop.equals("all")) {
            onSelectionChanged(new ArrayList<String>(), true);
        } else if (rule.loop.equals("pick")) {
            onSelectionChanged(new ArrayList<>(rule.names), false);
        }
        if (!rule.sound.equals("keep")) setMuted(rule.sound.equals("off"));
        if (rule.brightness != Schedule.KEEP_BRIGHTNESS) setBrightness(rule.brightness);
        if (!rule.screen.equals("keep")) setAsleep(rule.screen.equals("off"));
    }

    // ---- Phone remote ----

    private void setRemote(boolean on) {
        prefs.edit().putBoolean("remote", on).apply();
        if (on) {
            startRemote(true);
            if (needsWritePermission()) requestPermissions(new String[] {WRITE_STORAGE}, WRITE_REQUEST);
        } else {
            stopRemote();
            showNotice("Phone remote: off");
        }
    }

    private void startRemote(boolean announce) {
        if (remote != null) {
            if (announce) showNotice(remoteAddress() + "\nPIN " + remotePin(), 15000);
            return;
        }
        String saved = prefs.getString("remote_sessions", "");
        Set<String> sessions = new LinkedHashSet<>();
        if (!saved.isEmpty()) sessions.addAll(Arrays.asList(saved.split("\n")));
        RemoteApi api = new RemoteApi();
        remote = new RemoteServer(REMOTE_PORT, remotePin(), sessions, api);
        api.server = remote;
        try {
            remote.start();
        } catch (IOException e) {
            remote = null;
            showNotice("The phone remote couldn't start:\n" + e.getMessage(), 5000);
            return;
        }
        if (announce) showNotice("Phone remote on\n" + remoteAddress() + "\nPIN " + remotePin(), 15000);
    }

    private void stopRemote() {
        if (remote != null) remote.stop();
        remote = null;
    }

    /** Six random digits, made once and kept. */
    private String remotePin() {
        String pin = prefs.getString("remote_pin", null);
        if (pin == null) {
            pin = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
            prefs.edit().putString("remote_pin", pin).apply();
        }
        return pin;
    }

    /** A fresh PIN, and every phone has to sign in again with it. */
    private void newRemotePin() {
        synchronized (remoteLock) {
            // Stop the old server first, so a sign-in still in flight on it can't save its session afterwards.
            stopRemote();
            prefs.edit().remove("remote_pin").remove("remote_sessions").apply();
        }
        startRemote(true);
    }

    private static String remoteAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return "http://" + a.getHostAddress() + ":" + REMOTE_PORT;
                    }
                }
            }
        } catch (Exception ignored) {
            // No network yet.
        }
        return "http://<this device's address>:" + REMOTE_PORT;
    }

    /** Runs on the main thread and waits for the answer; the remote's calls come in on its own threads. */
    private <T> T onMain(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        handler.post(task);
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // Hand back the real error (a bad name, an unknown action) rather than its wrapper.
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    private File videoFile(String name) {
        if (name == null || name.length() > 150 || name.contains("/") || name.contains("\\") || name.contains("\0")
                || !isVideoName(name)) {
            throw new IllegalArgumentException("Video names must end in .mp4, .mkv or .webm and can't contain slashes");
        }
        return new File(folder(), name);
    }

    private class RemoteApi implements RemoteServer.Api {
        RemoteServer server;

        @Override
        public byte[] page() throws IOException {
            InputStream in = getAssets().open("remote.html");
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        }

        @Override
        public JSONObject state() throws Exception {
            return onMain(new Callable<JSONObject>() {
                @Override
                public JSONObject call() throws Exception {
                    scanLibrary();
                    JSONArray videos = new JSONArray();
                    for (File f : library) {
                        videos.put(new JSONObject().put("name", f.getName()).put("title", VideoPicker.displayName(f.getName()))
                                .put("size", f.length()).put("modified", f.lastModified())
                                .put("tags", new JSONArray(tags.of(f.getName()))));
                    }
                    boolean byTag = tagMode();
                    JSONArray apps = new JSONArray();
                    Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    for (ResolveInfo r : getPackageManager().queryIntentActivities(launcher, 0)) {
                        if (r.activityInfo.packageName.equals(getPackageName())) continue;
                        apps.put(new JSONObject().put("label", r.loadLabel(getPackageManager()).toString())
                                .put("package", r.activityInfo.packageName));
                    }
                    Calendar now = Calendar.getInstance();
                    return new JSONObject()
                            .put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName)
                            .put("clock", String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)))
                            .put("inFront", inFront)
                            .put("asleep", asleep)
                            .put("playing", current == null ? JSONObject.NULL : current.getName())
                            .put("all", !byTag && (allMode || effectiveSelection().isEmpty()))
                            .put("selection", new JSONArray(effectiveSelection()))
                            .put("tags", new JSONArray(tags.present(library)))
                            .put("activeTags", new JSONArray(byTag ? new ArrayList<>(activeTags) : new ArrayList<String>()))
                            .put("shuffle", shuffle)
                            .put("videos", videos)
                            .put("muted", muted)
                            .put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                            .put("maxVolume", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                            .put("brightness", brightness)
                            .put("fill", fill)
                            .put("canWrite", !needsWritePermission())
                            .put("freeBytes", folder().getUsableSpace())
                            .put("schedule", schedule.toJson())
                            .put("apps", apps);
                }
            });
        }

        @Override
        public void action(final String name, final JSONObject body) throws Exception {
            onMain(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    switch (name) {
                        case "loop":
                            if (body.optBoolean("all", false)) {
                                onSelectionChanged(new ArrayList<String>(), true);
                            } else if (body.has("tags")) {
                                JSONArray wanted = body.getJSONArray("tags");
                                List<String> list = new ArrayList<>();
                                for (int i = 0; i < wanted.length(); i++) list.add(wanted.getString(i));
                                onTagsChanged(list);
                            } else if (body.has("solo")) {
                                videoFile(body.getString("solo"));
                                onSolo(body.getString("solo"));
                            } else {
                                JSONArray names = body.getJSONArray("names");
                                List<String> list = new ArrayList<>();
                                for (int i = 0; i < names.length(); i++) list.add(names.getString(i));
                                onSelectionChanged(list, list.isEmpty());
                            }
                            if (picker.isOpen()) openPicker();
                            break;
                        case "shuffle":
                            setShuffle(body.getBoolean("on"));
                            if (picker.isOpen()) openPicker();
                            break;
                        case "skip":
                            skip(body.optInt("dir", 1) < 0 ? -1 : 1);
                            break;
                        case "tag": {
                            String video = body.getString("name");
                            videoFile(video);
                            JSONArray list = body.getJSONArray("tags");
                            List<String> names = new ArrayList<>();
                            for (int i = 0; i < list.length(); i++) names.add(list.getString(i));
                            scanLibrary();
                            tags.set(video, names);
                            try {
                                tags.save(new File(folder(), Tags.FILE_NAME));
                            } catch (IOException e) {
                                if (needsWritePermission()) {
                                    throw new IllegalArgumentException("Loop Player isn't allowed to save files: allow storage access on the device");
                                }
                                throw e;
                            }
                            libraryChanged();
                            break;
                        }
                        case "sound":
                            if (body.has("muted")) setMuted(body.getBoolean("muted"));
                            if (body.has("volume")) {
                                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                                        Math.max(0, Math.min(max, body.getInt("volume"))), 0);
                            }
                            break;
                        case "screen":
                            if (body.has("brightness")) setBrightness(body.getInt("brightness"));
                            if (body.has("fill")) setFill(body.getBoolean("fill"));
                            if (body.has("asleep")) setAsleep(body.getBoolean("asleep"));
                            break;
                        case "schedule":
                            setSchedule(Schedule.parse(body.toString()));
                            break;
                        case "reload":
                            if (hasVideoPermission()) startPlaylist();
                            break;
                        case "open":
                            openApp(body.getString("package"));
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown action " + name);
                    }
                    return null;
                }
            });
        }

        @Override
        public File thumbnail(String name) {
            File f = videoFile(name);
            return f.isFile() ? thumbnails.jpeg(f) : null;
        }

        @Override
        public void upload(String name, InputStream body, long length) throws Exception {
            File dest = videoFile(name);
            File dir = folder();
            dir.mkdirs();
            if (dir.getUsableSpace() < length + FREE_SPACE_MARGIN) {
                throw new IllegalArgumentException("Not enough free space on this device");
            }
            File part = new File(dir, "." + name + ".partial");
            try {
                OutputStream out = new FileOutputStream(part);
                try {
                    byte[] buf = new byte[65536];
                    long left = length;
                    while (left > 0) {
                        int n = body.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("The upload was cut off");
                        out.write(buf, 0, n);
                        left -= n;
                    }
                } finally {
                    out.close();
                }
                if (dest.exists() && !dest.delete()) throw new IOException("Couldn't replace " + name);
                if (!part.renameTo(dest)) throw new IOException("Couldn't save " + name);
            } catch (IOException e) {
                part.delete();
                if (needsWritePermission()) {
                    throw new IllegalArgumentException("Loop Player isn't allowed to save files: allow storage access on the device");
                }
                throw e;
            }
            onMain(new Callable<Void>() {
                @Override
                public Void call() {
                    libraryChanged();
                    return null;
                }
            });
        }

        @Override
        public void sessionsChanged(List<String> tokens) {
            synchronized (remoteLock) {
                if (server != remote) return;  // a server that New PIN or turning the remote off already stopped
                prefs.edit().putString("remote_sessions", TextUtils.join("\n", tokens)).apply();
            }
        }

        @Override
        public void delete(String name) throws Exception {
            File f = videoFile(name);
            if (!f.isFile()) throw new IllegalArgumentException("No video called " + name);
            if (!f.delete()) throw new IllegalArgumentException("This device won't let Loop Player delete " + name);
            onMain(new Callable<Void>() {
                @Override
                public Void call() {
                    libraryChanged();
                    return null;
                }
            });
        }
    }

    /** Brings another app to the screen, or this one back (Android 10+ may block this while the player isn't in
     *  front, unless the player is the home screen). */
    private void openApp(String packageName) {
        if (packageName.equals(getPackageName())) {
            // Bringing our own task back from behind another app: starting our activity again gets quietly ignored
            // while we're in the background, but moving the task to the front works.
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).moveTaskToFront(getTaskId(), 0);
            return;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) throw new IllegalArgumentException("No app " + packageName + " to open");
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---- Settings ----

    private void showMenu() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final boolean remoteOn = remote != null;
        labels.add("Choose videos");
        actions.add(new Runnable() { public void run() { openPicker(); } });
        labels.add(remoteOn ? "Phone remote: on  (tap for off)\n" + remoteAddress() + "   PIN " + remotePin()
                            : "Phone remote: off  (tap for on)");
        actions.add(new Runnable() { public void run() { setRemote(!remoteOn); } });
        if (remoteOn) {
            labels.add("New PIN  (signs out every phone, clears lockouts)");
            actions.add(new Runnable() { public void run() { newRemotePin(); } });
        }
        labels.add(muted ? "Sound: off  (tap for on)" : "Sound: on  (tap for off)");
        actions.add(new Runnable() { public void run() { setMuted(!muted); } });
        labels.add(fill ? "Picture: fill the screen  (tap to fit)" : "Picture: fit the whole video  (tap to fill)");
        actions.add(new Runnable() { public void run() { setFill(!fill); } });
        labels.add(isHome() ? "Home screen: on  (tap for off)" : "Home screen: off  (tap for on)");
        actions.add(new Runnable() { public void run() { setHome(!isHome()); } });
        labels.add("Sleep  (tap the screen to wake)");
        actions.add(new Runnable() { public void run() { setAsleep(true); } });
        labels.add("Reload videos");
        actions.add(new Runnable() {
            public void run() {
                if (hasVideoPermission()) startPlaylist();
                else requestPermissions(new String[] {videoPermission()}, PERMISSION_REQUEST);
            }
        });
        labels.add("Android settings");
        actions.add(new Runnable() { public void run() { startActivity(new Intent(Settings.ACTION_SETTINGS)); } });
        labels.add("Exit player");
        actions.add(new Runnable() { public void run() { finish(); } });
        new AlertDialog.Builder(this)
                .setTitle("Loop Player")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    private void setMuted(boolean value) {
        muted = value;
        prefs.edit().putBoolean("muted", muted).apply();
        applyVolume();
    }

    private void setFill(boolean value) {
        fill = value;
        prefs.edit().putBoolean("fill", fill).apply();
        video.setFill(fill);
    }

    private ComponentName homeAlias() {
        return new ComponentName(this, getPackageName() + ".HomeAlias");
    }

    private boolean isHome() {
        return getPackageManager().getComponentEnabledSetting(homeAlias())
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    /** Offers the player as the launcher, so it opens at boot and on Home; Android asks which launcher to keep. */
    private void setHome(boolean on) {
        getPackageManager().setComponentEnabledSetting(homeAlias(),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        if (on) {
            showNotice("Choose Loop Player, then \"Always\"");
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            showNotice("Home screen: off");
        }
    }

    private void applyVolume() {
        if (player == null) return;
        try {
            float v = muted ? 0f : 1f;
            player.setVolume(v, v);
        } catch (IllegalStateException ignored) {
            // The player was released between files; the next onPrepared applies it.
        }
    }

    private void showNotice(String text) {
        showNotice(text, 1500);
    }

    private void showNotice(String text, long ms) {
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideNotice);
        handler.postDelayed(hideNotice, ms);
    }

    private void showStickyNotice(String text) {
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideNotice);
    }
}
