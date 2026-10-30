package io.github.robbiemueller.loopplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen, control-free video loop for signage screens, old tablets and the like (Android 5.0+).
 *
 * Plays the videos in /sdcard/Movies/loop/ forever: all of them in name order, or the ones picked in the swipe-up
 * panel, in the order picked (a single video loops seamlessly inside the media player). Double-tap toggles the sound;
 * holding a finger down for 3 seconds opens the settings menu.
 * From a computer: adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity with
 *   --ez reload true           rescan the folder
 *   --es play name.mp4         loop just that video
 *   --es playlist a.mp4,b.mp4  loop these, in this order
 *   --ez all true              loop everything in the folder
 *   --ez mute true             sound off (false: on)
 *   --ez fill true             crop to fill the screen (false: fit the whole video)
 *   --ez home true             use as the home screen (false: stop)
 */
public class PlayerActivity extends Activity implements VideoPicker.Listener {
    private static final long MENU_HOLD_MS = 3000;
    private static final int PERMISSION_REQUEST = 1;
    private static final int HINT_LAUNCHES = 5;
    // View.SYSTEM_UI_FLAG_* values, written out so this builds against any SDK level.
    private static final int IMMERSIVE = 0x00000100 | 0x00000200 | 0x00000400 | 0x00000002 | 0x00000004 | 0x00001000;

    private final Handler handler = new Handler();
    private LoopVideoView video;
    private TextView notice;
    private VideoPicker picker;
    private MediaPlayer player;
    private List<File> library = new ArrayList<>();
    private List<File> playlist = new ArrayList<>();
    private File current;
    private boolean allMode;
    private List<String> selection = new ArrayList<>();
    private boolean muted;
    private boolean fill;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        muted = prefs.getBoolean("muted", false);
        fill = prefs.getBoolean("fill", false);
        allMode = prefs.getBoolean("all", true);
        String saved = prefs.getString("selection", "");
        if (!saved.isEmpty()) selection = new ArrayList<>(Arrays.asList(saved.split("\n")));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new LoopVideoView(this);
        video.setFill(fill);
        root.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        notice = new TextView(this);
        notice.setTextColor(Color.WHITE);
        notice.setBackgroundColor(Color.argb(0xc0, 0, 0, 0));
        notice.setTextSize(22);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(36, 20, 36, 20);
        notice.setVisibility(View.GONE);
        root.addView(notice, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        picker = new VideoPicker(this, this);
        root.addView(picker, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
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
                video.start();
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

        boolean restart = handleIntent(getIntent());
        if (hasVideoPermission()) {
            startPlaylist();
            int launches = prefs.getInt("launches", 0);
            if (launches < HINT_LAUNCHES && !restart) {
                prefs.edit().putInt("launches", launches + 1).apply();
                showNotice("Swipe up to choose videos", 2500);
            }
        } else {
            requestPermissions(new String[] {videoPermission()}, PERMISSION_REQUEST);
        }
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
        hideSystemUi();
        if (!video.isPlaying() && !playlist.isEmpty()) video.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
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
            String name = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm"))) {
                library.add(f);
            }
        }
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
        // Only picks that still exist on disk; none left means everything plays.
        List<String> existing = new ArrayList<>();
        for (File f : buildPlaylist()) {
            if (selection.contains(f.getName())) existing.add(f.getName());
        }
        boolean all = allMode || existing.isEmpty();
        picker.setLibrary(library, all ? new ArrayList<String>() : existing, all,
                current == null ? null : current.getName());
        picker.open();
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
        showNotice("Looping " + VideoPicker.displayName(name));
    }

    @Override
    public void onClosed() {
        hideSystemUi();
    }

    // ---- Settings ----

    private void showMenu() {
        final String[] items = {
            "Choose videos",
            muted ? "Sound: off  (tap for on)" : "Sound: on  (tap for off)",
            fill ? "Picture: fill the screen  (tap to fit)" : "Picture: fit the whole video  (tap to fill)",
            isHome() ? "Home screen: on  (tap for off)" : "Home screen: off  (tap for on)",
            "Reload videos",
            "Android settings",
            "Exit player",
        };
        new AlertDialog.Builder(this)
                .setTitle("Loop Player")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0: openPicker(); break;
                            case 1: setMuted(!muted); break;
                            case 2: setFill(!fill); break;
                            case 3: setHome(!isHome()); break;
                            case 4:
                                if (hasVideoPermission()) startPlaylist();
                                else requestPermissions(new String[] {videoPermission()}, PERMISSION_REQUEST);
                                break;
                            case 5: startActivity(new Intent(Settings.ACTION_SETTINGS)); break;
                            case 6: finish(); break;
                        }
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
