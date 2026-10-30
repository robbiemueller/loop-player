package io.github.robbiemueller.loopplayer;

import android.app.Activity;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Full-screen, control-free video loop for signage screens, old tablets and the like (Android 5.0+).
 *
 * Plays the videos in /sdcard/Movies/loop/ forever: all of them in name order, or the ones picked in the swipe-up
 * panel, in the order picked (a single video loops seamlessly inside the media player). Double-tap toggles the sound;
 * holding a finger down for 3 seconds opens the settings menu, where the phone remote can be turned on.
 * From a computer: adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity with
 *   --ez reload true           rescan the folder
 *   --es play name.mp4         loop just that video
 *   --es playlist a.mp4,b.mp4  loop these, in this order
 *   --ez all true              loop everything in the folder
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
    private File current;
    private boolean allMode;
    private List<String> selection = new ArrayList<>();
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
            public boolean onDoubleTap(MotionEvent e) {
                setMuted(!muted);
                showNotice(muted ? "Sound off" : "Sound on");
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                if (dy < -100 * density && vy < -600 && Math.abs(dy) > Math.abs(e2.getX() - e1.getX())) {
                    openPicker();
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
        if (intent.getBooleanExtra("all", false)) {
            saveSelection(new ArrayList<String>(), true);
            restart = true;
        }
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

    private File folder() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "loop");
    }

    private void scanLibrary() {
        File[] files = folder().listFiles();
        library = new ArrayList<>();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isFile() && isVideoName(f.getName())) library.add(f);
        }
    }

    private static boolean isVideoName(String name) {
        String n = name.toLowerCase(Locale.US);
        return !n.startsWith(".") && (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm"));
    }

    /** The loop: every video when in "all" mode (or when none of the picked ones exist), else the picked ones. */
    private List<File> buildPlaylist() {
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
        play(playlist.get(0));
    }

    private void playNext() {
        if (playlist.isEmpty()) return;
        int at = playlist.indexOf(current);
        play(playlist.get(at < 0 ? 0 : (at + 1) % playlist.size()));
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

    private void saveSelection(List<String> names, boolean all) {
        allMode = all;
        selection = names;
        prefs.edit().putBoolean("all", all).putString("selection", TextUtils.join("\n", names)).apply();
    }

    private void openPicker() {
        handler.removeCallbacks(openMenuAfterHold);
        scanLibrary();
        picker.setLibrary(library, effectiveSelection(), allMode || effectiveSelection().isEmpty(),
                current == null ? null : current.getName());
        picker.open();
    }

    /** The picks that still exist on disk, in order; none left means everything plays. */
    private List<String> effectiveSelection() {
        List<String> existing = new ArrayList<>();
        if (allMode) return existing;
        for (File f : buildPlaylist()) {
            if (selection.contains(f.getName())) existing.add(f.getName());
        }
        return existing;
    }

    @Override
    public void onSelectionChanged(List<String> selected, boolean all) {
        saveSelection(selected, all);
        playlist = buildPlaylist();
        if (playlist.isEmpty()) return;
        if (current == null || !playlist.contains(current)) {
            // What's on screen was taken out of the loop: switch to the first pick right away.
            play(playlist.get(0));
        } else {
            updateLooping();
        }
    }

    @Override
    public void onSolo(String name) {
        saveSelection(new ArrayList<>(Arrays.asList(name)), false);
        playlist = buildPlaylist();
        if (playlist.isEmpty()) return;
        if (playlist.get(0).equals(current)) {
            updateLooping();
        } else {
            play(playlist.get(0));
        }
        if (!asleep) showNotice("Looping " + VideoPicker.displayName(name));
    }

    @Override
    public void onClosed() {
        hideSystemUi();
    }

    /** After files were added or removed: rescan, and move on if what's on screen is gone. */
    private void libraryChanged() {
        scanLibrary();
        playlist = buildPlaylist();
        if (playlist.isEmpty()) {
            startPlaylist();
        } else if (current == null || !playlist.contains(current)) {
            play(playlist.get(0));
        } else {
            updateLooping();
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
                                .put("size", f.length()).put("modified", f.lastModified()));
                    }
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
                            .put("all", allMode || effectiveSelection().isEmpty())
                            .put("selection", new JSONArray(effectiveSelection()))
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
        Intent intent;
        if (packageName.equals(getPackageName())) {
            intent = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        } else {
            intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) throw new IllegalArgumentException("No app " + packageName + " to open");
        }
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
            labels.add("New PIN  (signs out every phone)");
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
