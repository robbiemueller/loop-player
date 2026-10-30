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
 * Plays every video in /sdcard/Movies/loop/ in name order and loops forever (a single file loops seamlessly
 * inside the media player). Double-tap toggles the sound; holding a finger down for 3 seconds opens the menu.
 * From a computer: adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity with
 *   --ez reload true    rescan the folder
 *   --ez mute true      sound off (false: on)
 *   --ez fill true      crop to fill the screen (false: fit the whole video)
 *   --ez home true      use as the home screen (false: stop)
 */
public class PlayerActivity extends Activity {
    private static final long MENU_HOLD_MS = 3000;
    private static final int PERMISSION_REQUEST = 1;
    // View.SYSTEM_UI_FLAG_* values, written out so this builds against any SDK level.
    private static final int IMMERSIVE = 0x00000100 | 0x00000200 | 0x00000400 | 0x00000002 | 0x00000004 | 0x00001000;

    private final Handler handler = new Handler();
    private LoopVideoView video;
    private TextView notice;
    private MediaPlayer player;
    private List<File> playlist = new ArrayList<>();
    private int index;
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
        setContentView(root);

        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                setMuted(!muted);
                showNotice(muted ? "Sound off" : "Sound on");
                return true;
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
                mp.setLooping(playlist.size() == 1);
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

        handleIntent(getIntent());
        if (hasVideoPermission()) {
            loadAndPlay();
        } else {
            requestPermissions(new String[] {videoPermission()}, PERMISSION_REQUEST);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleIntent(intent) && hasVideoPermission()) loadAndPlay();
    }

    /** Applies extras sent from a computer; returns true when the playlist should be reloaded. */
    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        if (intent.hasExtra("mute")) setMuted(intent.getBooleanExtra("mute", false));
        if (intent.hasExtra("fill")) setFill(intent.getBooleanExtra("fill", false));
        if (intent.hasExtra("home")) setHome(intent.getBooleanExtra("home", false));
        return intent.getBooleanExtra("reload", false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadAndPlay();
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

    private void showMenu() {
        final String[] items = {
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
                            case 0: setMuted(!muted); break;
                            case 1: setFill(!fill); break;
                            case 2: setHome(!isHome()); break;
                            case 3:
                                if (hasVideoPermission()) loadAndPlay();
                                else requestPermissions(new String[] {videoPermission()}, PERMISSION_REQUEST);
                                break;
                            case 4: startActivity(new Intent(Settings.ACTION_SETTINGS)); break;
                            case 5: finish(); break;
                        }
                    }
                })
                .show();
    }

    private void loadAndPlay() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "loop");
        File[] files = dir.listFiles();
        playlist = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                String name = f.getName().toLowerCase(Locale.US);
                if (f.isFile() && (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm"))) {
                    playlist.add(f);
                }
            }
        }
        if (playlist.isEmpty()) {
            video.stopPlayback();
            showStickyNotice("No videos in\n" + dir.getPath());
            return;
        }
        notice.setVisibility(View.GONE);
        index = 0;
        playCurrent();
    }

    private void playNext() {
        if (playlist.isEmpty()) return;
        index = (index + 1) % playlist.size();
        playCurrent();
    }

    private void playCurrent() {
        video.setVideoURI(Uri.fromFile(playlist.get(index)));
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
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideNotice);
        handler.postDelayed(hideNotice, 1500);
    }

    private void showStickyNotice(String text) {
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideNotice);
    }
}
