package com.youzai.video;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// Fullscreen player on ExoPlayer (media3), custom minimal controls.
// Payload in:  {title, names:[], urls:[], index, positions:{idx:ms}}
// Result out:  {index, positions:{idx:ms}}  (raw JSON forwarded to the page)
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private FrameLayout root;
    private SurfaceView surface;
    private View topBar, bottomBar;
    private TextView titleView, timeView, msgView, gestureView, resView;
    private Button playBtn, speedBtn, modeBtn, fsBtn;
    private TextView unlockBtn;
    private boolean fullscreen = false;
    private float downX, downY;
    private long downTime;
    private boolean seeking = false;
    private long seekBase = 0, seekTarget = 0;
    private SeekBar seekBar;
    private ProgressBar spinner;
    private final Handler ui = new Handler();
    private boolean controlsShown = true;
    private boolean scrubbing = false;

    // resize modes: 0 fit, 1 fill(crop), 2 stretch, 3 force 16:9, 4 force 4:3
    private static final String[] MODE_NAMES = {"适应", "铺满", "拉伸", "16:9", "4:3"};
    private int resizeMode = 0;
    private float videoAspect = 0f;
    private boolean locked = false;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    private int speedIdx = 2;

    private String title = "";
    private String[] names = new String[0];
    private String[] urls = new String[0];
    private int index = 0;
    private int readyForIndex = -1;
    private final Map<Integer, Integer> positions = new HashMap<>();

    private final Runnable hideControls = () -> {
        if (player != null && player.isPlaying()) showControls(false);
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                if (player != null && !scrubbing) {
                    long dur = player.getDuration();
                    long pos = player.getCurrentPosition();
                    if (dur > 0) {
                        seekBar.setMax((int) Math.min(dur, Integer.MAX_VALUE));
                        seekBar.setProgress((int) Math.min(pos, Integer.MAX_VALUE));
                        timeView.setText(fmt((int) pos) + " / " + fmt((int) dur));
                        if (pos > 0) positions.put(index, (int) pos);
                    }
                }
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, 500);
        }
    };

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec)
                : String.format("%02d:%02d", m, sec);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        parsePayload();
        resizeMode = getSharedPreferences("yzv_player", MODE_PRIVATE).getInt("resize_mode", 0);
        if (resizeMode < 0 || resizeMode >= MODE_NAMES.length) resizeMode = 0;

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if ((r - l) != (orr - ol) || (b - t) != (ob - ot)) applyResize();
        });

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(120, 120, Gravity.CENTER));

        msgView = new TextView(this);
        msgView.setTextColor(0xFFFFFFFF);
        msgView.setTextSize(15);
        msgView.setGravity(Gravity.CENTER);
        msgView.setVisibility(View.GONE);
        root.addView(msgView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xB3000000);
        top.setPadding(8, 8, 16, 8);
        Button back = mkBtn("←");
        back.setOnClickListener(v -> finishWithResult());
        top.addView(back);
        titleView = new TextView(this);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        top.addView(titleView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        fsBtn = mkBtn("全屏");
        fsBtn.setTextSize(14);
        fsBtn.setOnClickListener(v -> setFullscreen(!fullscreen));
        top.addView(fsBtn);
        Button lockBtn = mkBtn("🔒");
        lockBtn.setOnClickListener(v -> setLocked(true));
        top.addView(lockBtn);
        topBar = top;
        root.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(0xB3000000);
        bottom.setPadding(8, 8, 8, 8);
        Button prev = mkBtn("⏮");
        prev.setOnClickListener(v -> {
            if (player != null && player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
            else Toast.makeText(this, "已经是第一集", Toast.LENGTH_SHORT).show();
            pokeControls();
        });
        bottom.addView(prev);
        playBtn = mkBtn("⏸");
        playBtn.setOnClickListener(v -> togglePlay());
        bottom.addView(playBtn);
        Button next = mkBtn("⏭");
        next.setOnClickListener(v -> {
            if (player != null && player.hasNextMediaItem()) player.seekToNextMediaItem();
            else Toast.makeText(this, "已经是最后一集", Toast.LENGTH_SHORT).show();
            pokeControls();
        });
        bottom.addView(next);
        timeView = new TextView(this);
        timeView.setTextColor(0xFFDDDDDD);
        timeView.setTextSize(12);
        timeView.setText("00:00 / 00:00");
        bottom.addView(timeView);
        resView = new TextView(this);
        resView.setTextColor(0xFF999999);
        resView.setTextSize(11);
        resView.setText(" -- ");
        bottom.addView(resView);
        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    long dur = player.getDuration();
                    timeView.setText(fmt(progress) + " / " + fmt((int) Math.max(dur, 0)));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                scrubbing = false;
                if (player != null) {
                    player.seekTo(sb.getProgress());
                    positions.put(index, sb.getProgress());
                }
                pokeControls();
            }
        });
        bottom.addView(seekBar, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        speedBtn = mkBtn("1.0x");
        speedBtn.setTextSize(14);
        speedBtn.setOnClickListener(v -> {
            speedIdx = (speedIdx + 1) % SPEEDS.length;
            float s = SPEEDS[speedIdx];
            if (player != null) player.setPlaybackSpeed(s);
            speedBtn.setText(speedLabel(s));
            pokeControls();
        });
        bottom.addView(speedBtn);
        modeBtn = mkBtn(MODE_NAMES[resizeMode]);
        modeBtn.setTextSize(14);
        modeBtn.setOnClickListener(v -> {
            resizeMode = (resizeMode + 1) % MODE_NAMES.length;
            modeBtn.setText(MODE_NAMES[resizeMode]);
            getSharedPreferences("yzv_player", MODE_PRIVATE).edit()
                    .putInt("resize_mode", resizeMode).apply();
            applyResize();
            Toast.makeText(this, "画面比例：" + MODE_NAMES[resizeMode], Toast.LENGTH_SHORT).show();
            pokeControls();
        });
        bottom.addView(modeBtn);
        bottomBar = bottom;
        root.addView(bottom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        gestureView = new TextView(this);
        gestureView.setTextColor(0xFFFFFFFF);
        gestureView.setTextSize(16);
        gestureView.setGravity(Gravity.CENTER);
        gestureView.setBackgroundColor(0x99000000);
        gestureView.setPadding(28, 16, 28, 16);
        gestureView.setVisibility(View.GONE);
        root.addView(gestureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        unlockBtn = new TextView(this);
        unlockBtn.setText("🔓");
        unlockBtn.setTextSize(22);
        unlockBtn.setGravity(Gravity.CENTER);
        unlockBtn.setBackgroundColor(0x66000000);
        unlockBtn.setPadding(18, 14, 18, 14);
        unlockBtn.setVisibility(View.GONE);
        unlockBtn.setOnClickListener(v -> setLocked(false));
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        ulp.rightMargin = 20;
        root.addView(unlockBtn, ulp);

        root.setOnTouchListener((v, e) -> {
            if (locked) {
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) flashUnlock();
                return true;
            }
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downTime = System.currentTimeMillis();
                    seeking = false;
                    if (player != null) seekBase = player.getCurrentPosition();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (player == null) return false;
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (!seeking && Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        seeking = true;
                    }
                    if (seeking) {
                        long dur = player.getDuration();
                        if (dur > 0) {
                            long delta = (long) (dx * (dur / 3.0) / Math.max(1, root.getWidth()));
                            seekTarget = Math.max(0, Math.min(dur, seekBase + delta));
                            gestureView.setText((seekTarget >= seekBase ? "⏩ 快进到 " : "⏪ 后退到 ")
                                    + fmt((int) seekTarget) + " / " + fmt((int) dur));
                            gestureView.setVisibility(View.VISIBLE);
                        }
                        return true;
                    }
                    return false;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (seeking) {
                        seeking = false;
                        gestureView.setVisibility(View.GONE);
                        if (player != null) {
                            player.seekTo(seekTarget);
                            positions.put(index, (int) seekTarget);
                        }
                        pokeControls();
                        return true;
                    }
                    if (e.getAction() == android.view.MotionEvent.ACTION_UP
                            && System.currentTimeMillis() - downTime < 300
                            && Math.abs(e.getX() - downX) < 16 && Math.abs(e.getY() - downY) < 16) {
                        showControls(!controlsShown);
                        pokeControls();
                        return true;
                    }
                    return false;
                }
                default:
                    return false;
            }
        });
        setContentView(root);
        setFullscreen(false);
        updateTitle();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(new DefaultDataSource.Factory(this, http));

        player = new ExoPlayer.Builder(this).setMediaSourceFactory(msf).build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    spinner.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_READY) {
                    spinner.setVisibility(View.GONE);
                    msgView.setVisibility(View.GONE);
                    if (readyForIndex != index) {
                        readyForIndex = index;
                        Integer saved = positions.get(index);
                        if (saved != null && saved > 0) {
                            long dur = player.getDuration();
                            if (dur <= 0 || saved < dur - 3000) player.seekTo(saved);
                        }
                    }
                } else if (state == Player.STATE_ENDED) {
                    spinner.setVisibility(View.GONE);
                    positions.put(index, 0);
                    playBtn.setText("▶");
                    showControls(true);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (size.height > 0 && size.width > 0) {
                    videoAspect = size.width * size.pixelWidthHeightRatio / size.height;
                    applyResize();
                    if (fullscreen) applyFsOrientation();
                    int m = Math.min(size.width, size.height);
                    String tier = m >= 2000 ? " · 4K" : m >= 1000 ? " · 1080P"
                            : m >= 700 ? " · 720P" : m >= 400 ? " · 480P" : "";
                    resView.setText(" " + size.width + "×" + size.height + tier + " ");
                }
            }

            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                index = player.getCurrentMediaItemIndex();
                readyForIndex = -1;
                updateTitle();
                seekBar.setProgress(0);
                resView.setText(" -- ");
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                playBtn.setText(isPlaying ? "⏸" : "▶");
                if (isPlaying) pokeControls();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                spinner.setVisibility(View.GONE);
                msgView.setText("这一集播放失败，可以返回换一条线路试试");
                msgView.setVisibility(View.VISIBLE);
                showControls(true);
            }
        });

        List<MediaItem> items = new ArrayList<>();
        for (String u : urls) {
            MediaItem.Builder b = new MediaItem.Builder().setUri(u);
            if (u != null && u.toLowerCase().contains("m3u8")) {
                b.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            items.add(b.build());
        }
        if (!items.isEmpty()) {
            Integer startPos = positions.get(index);
            player.setMediaItems(items, index, startPos == null ? 0 : startPos);
            readyForIndex = index; // start position already applied
            player.prepare();
            player.play();
        }
        ui.postDelayed(tick, 500);
    }

    private Button mkBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0x00000000);
        b.setMinWidth(0);
        b.setPadding(14, 6, 14, 6);
        return b;
    }

    private void parsePayload() {
        try {
            JSONObject p = new JSONObject(getIntent().getStringExtra("payload"));
            title = p.optString("title", "");
            JSONArray n = p.optJSONArray("names");
            JSONArray u = p.optJSONArray("urls");
            if (n != null && u != null) {
                int len = Math.min(n.length(), u.length());
                names = new String[len];
                urls = new String[len];
                for (int i = 0; i < len; i++) {
                    names[i] = n.optString(i, "第" + (i + 1) + "集");
                    urls[i] = u.optString(i, "");
                }
            }
            index = Math.max(0, Math.min(p.optInt("index", 0), Math.max(0, urls.length - 1)));
            JSONObject pos = p.optJSONObject("positions");
            if (pos != null) {
                Iterator<String> keys = pos.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    positions.put(Integer.parseInt(k), pos.optInt(k, 0));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void updateTitle() {
        titleView.setText(title + (names.length > 0 && index < names.length ? " · " + names[index] : ""));
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            player.seekTo(0);
            player.play();
        } else if (player.isPlaying()) {
            positions.put(index, (int) player.getCurrentPosition());
            player.pause();
            showControls(true);
        } else {
            player.play();
            pokeControls();
        }
    }

    private void showControls(boolean show) {
        if (locked) show = false;
        controlsShown = show;
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) immersive();
    }

    private static String speedLabel(float s) {
        String t = String.valueOf(s);
        if (t.endsWith(".0")) t = t.substring(0, t.length() - 1) + ".0";
        return t + "x";
    }

    // Size the video surface inside the root frame according to the mode.
    private void applyResize() {
        if (root == null || surface == null) return;
        int W = root.getWidth(), H = root.getHeight();
        if (W <= 0 || H <= 0) return;
        float a = videoAspect > 0 ? videoAspect : (float) W / H;
        if (resizeMode == 3) a = 16f / 9f;
        else if (resizeMode == 4) a = 4f / 3f;
        int w, h;
        if (resizeMode == 2) {           // stretch: ignore aspect
            w = W; h = H;
        } else if (resizeMode == 1) {    // fill: cover, crop the overflow
            if ((float) W / H > a) { w = W; h = Math.round(W / a); }
            else { h = H; w = Math.round(H * a); }
        } else {                          // fit (and forced-aspect modes)
            if ((float) W / H > a) { h = H; w = Math.round(H * a); }
            else { w = W; h = Math.round(W / a); }
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        if (!lp.equals(surface.getLayoutParams())) surface.setLayoutParams(lp);
    }

    private void setLocked(boolean lock) {
        locked = lock;
        if (lock) {
            showControls(false);
            ui.removeCallbacks(hideControls);
            unlockBtn.setVisibility(View.VISIBLE);
            unlockBtn.setAlpha(0.55f);
            Toast.makeText(this, "屏幕已锁定，点右侧小锁解锁", Toast.LENGTH_SHORT).show();
        } else {
            unlockBtn.setVisibility(View.GONE);
            showControls(true);
            pokeControls();
        }
    }

    private void flashUnlock() {
        unlockBtn.setVisibility(View.VISIBLE);
        unlockBtn.setAlpha(1f);
        ui.postDelayed(() -> { if (locked) unlockBtn.setAlpha(0.55f); }, 1200);
    }

    private void pokeControls() {
        ui.removeCallbacks(hideControls);
        if (controlsShown) ui.postDelayed(hideControls, 3500);
    }

    private void immersive() {
        if (!fullscreen) return;
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // Fullscreen follows the video's own shape: portrait videos (short
    // dramas) fullscreen in portrait, landscape videos in landscape.
    private void applyFsOrientation() {
        if (!fullscreen) return;
        if (videoAspect > 0 && videoAspect < 1f) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    // The player starts as a floating small window over the page; the
    // fullscreen button expands it (and switches to landscape) on demand.
    private void setFullscreen(boolean fs) {
        fullscreen = fs;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.gravity = Gravity.CENTER;
        if (fs) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.dimAmount = 0f;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            applyFsOrientation();
            immersive();
        } else {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = Math.round(Math.min(dm.widthPixels, dm.heightPixels) * 0.96f);
            lp.width = w;
            lp.height = Math.round(w * 9f / 16f);
            lp.dimAmount = 0.55f;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        getWindow().setAttributes(lp);
        setFinishOnTouchOutside(!fs);
        if (fsBtn != null) fsBtn.setText(fs ? "小屏" : "全屏");
        if (root != null) root.post(this::applyResize);
    }

    @Override
    public void onBackPressed() {
        finishWithResult();
    }

    @Override
    protected void onPause() {
        try {
            if (player != null && player.isPlaying()) {
                positions.put(index, (int) player.getCurrentPosition());
                player.pause();
            }
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hideControls);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private void finishWithResult() {
        try {
            if (player != null && player.getCurrentPosition() > 0) {
                positions.put(index, (int) player.getCurrentPosition());
            }
        } catch (Exception ignored) {
        }
        try {
            JSONObject out = new JSONObject();
            out.put("index", index);
            JSONObject pos = new JSONObject();
            for (Map.Entry<Integer, Integer> e : positions.entrySet()) {
                pos.put(String.valueOf(e.getKey()), e.getValue());
            }
            out.put("positions", pos);
            Intent data = new Intent();
            data.putExtra("result", out.toString());
            setResult(RESULT_OK, data);
        } catch (Exception ignored) {
        }
        finish();
    }
}
