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
    private LinearLayout seekRow, menuRow, centerRow, sideBar;
    private TextView titleView, timeView, sysView, msgView, gestureView, resView;
    private Button playBtn, speedBtn, modeBtn, fsBtn, repeatBtn;
    private TextView unlockBtn;
    private View epPanel;
    private android.widget.GridLayout epGrid;
    private boolean repeatOne = false;
    private boolean manualOrient = false;
    private boolean fullscreen = false;
    private float downX, downY;
    private long downTime;
    private boolean seeking = false;
    private long seekBase = 0, seekTarget = 0;
    private SeekBar seekBar;
    private ProgressBar spinner;
    private final Handler ui = new Handler();
    private boolean controlsShown = true;
    private long lastTapUp = 0;
    private final Runnable pendingTapToggle = () -> { showControls(!controlsShown); pokeControls(); };
    private boolean boosting = false;
    private final Runnable boostStarter = () -> startBoost();
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
        if (epPanel != null && epPanel.getVisibility() == View.VISIBLE) {
            pokeControls();
            return;
        }
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
                        updateSys();
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
        top.setPadding(16, 8, 16, 8);
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        titleCol.addView(titleView);
        resView = new TextView(this);
        resView.setTextColor(0xFF999999);
        resView.setTextSize(11);
        resView.setText("--");
        titleCol.addView(resView);
        top.addView(titleCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        sysView = new TextView(this);
        sysView.setTextColor(0xFFDDDDDD);
        sysView.setTextSize(11);
        sysView.setGravity(Gravity.END);
        top.addView(sysView);
        topBar = top;
        root.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // Bottom controls, back to the structure that rendered fine up
        // to v1.18: one bottom-anchored vertical container holding a
        // seekbar-only row and a button row with a combined time label.
        // The OK-style split rows of v1.19/v1.20 rendered scattered on
        // the user's phone (rows at the top / middle, seekbar missing),
        // so that experiment is reverted; the new OK actions (refresh /
        // repeat / episode panel) stay as buttons in the button row.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xB3000000);
        bottom.setPadding(8, 4, 8, 4);
        seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setPadding(4, 0, 4, 0);
        bottom.addView(seekRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        seekBar = new SeekBar(this);
        // Thin OK-style seekbar. The stock MIUI seekbar renders as a
        // huge grey pill, so use a slim track (grey line, gold progress)
        // and a small white round thumb, all with explicit sizes.
        android.graphics.drawable.GradientDrawable trackBg =
                new android.graphics.drawable.GradientDrawable();
        trackBg.setColor(0x66FFFFFF);
        trackBg.setCornerRadius(4);
        trackBg.setSize(-1, 8);
        android.graphics.drawable.GradientDrawable trackProg =
                new android.graphics.drawable.GradientDrawable();
        trackProg.setColor(0xFFFFC107);
        trackProg.setCornerRadius(4);
        trackProg.setSize(-1, 8);
        android.graphics.drawable.ClipDrawable clip =
                new android.graphics.drawable.ClipDrawable(trackProg,
                        Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{trackBg, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        seekBar.setProgressDrawable(layers);
        android.graphics.drawable.GradientDrawable thumbD =
                new android.graphics.drawable.GradientDrawable();
        thumbD.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumbD.setColor(0xFFFFFFFF);
        thumbD.setSize(30, 30);
        seekBar.setThumb(thumbD);
        seekBar.setSplitTrack(false);
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
        seekRow.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 72));
        menuRow = new LinearLayout(this);
        menuRow.setOrientation(LinearLayout.HORIZONTAL);
        menuRow.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(menuRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        timeView = new TextView(this);
        timeView.setTextColor(0xFFDDDDDD);
        timeView.setTextSize(12);
        timeView.setText("00:00 / 00:00");
        menuRow.addView(timeView);
        speedBtn = mkBtn(speedText(SPEEDS[speedIdx]));
        speedBtn.setTextSize(13);
        speedBtn.setOnClickListener(v -> {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(this, v);
            pm.getMenu().setGroupCheckable(0, true, true);
            for (int i = 0; i < SPEEDS.length; i++) {
                pm.getMenu().add(0, i, i, speedLabel(SPEEDS[i])).setChecked(i == speedIdx);
            }
            pm.setOnMenuItemClickListener(item -> {
                speedIdx = item.getItemId();
                float s = SPEEDS[speedIdx];
                if (player != null) player.setPlaybackSpeed(s);
                speedBtn.setText(speedText(s));
                pokeControls();
                return true;
            });
            pm.show();
            pokeControls();
        });
        menuRow.addView(speedBtn);
        modeBtn = mkBtn(MODE_NAMES[resizeMode]);
        modeBtn.setTextSize(13);
        modeBtn.setOnClickListener(v -> {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(this, v);
            pm.getMenu().setGroupCheckable(0, true, true);
            for (int i = 0; i < MODE_NAMES.length; i++) {
                pm.getMenu().add(0, i, i, MODE_NAMES[i]).setChecked(i == resizeMode);
            }
            pm.setOnMenuItemClickListener(item -> {
                resizeMode = item.getItemId();
                modeBtn.setText(MODE_NAMES[resizeMode]);
                getSharedPreferences("yzv_player", MODE_PRIVATE).edit()
                        .putInt("resize_mode", resizeMode).apply();
                applyResize();
                pokeControls();
                return true;
            });
            pm.show();
            pokeControls();
        });
        menuRow.addView(modeBtn);
        Button refreshBtn = mkBtn("刷新");
        refreshBtn.setTextSize(13);
        refreshBtn.setOnClickListener(v -> { refreshPlayer(); pokeControls(); });
        menuRow.addView(refreshBtn);
        repeatBtn = mkBtn("循环");
        repeatBtn.setTextSize(13);
        repeatBtn.setOnClickListener(v -> {
            repeatOne = !repeatOne;
            if (player != null) player.setRepeatMode(repeatOne
                    ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            repeatBtn.setTextColor(repeatOne ? 0xFFFFD54F : 0xFFFFFFFF);
            Toast.makeText(this, repeatOne ? "单集循环开" : "单集循环关",
                    Toast.LENGTH_SHORT).show();
            pokeControls();
        });
        menuRow.addView(repeatBtn);
        fsBtn = mkBtn("全屏");
        fsBtn.setTextSize(13);
        fsBtn.setOnClickListener(v -> setFullscreen(!fullscreen));
        menuRow.addView(fsBtn);
        Button epBtn = mkBtn("选集");
        epBtn.setTextSize(13);
        epBtn.setOnClickListener(v -> toggleEpPanel());
        menuRow.addView(epBtn);
        bottomBar = bottom;
        root.addView(bottom, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // Center control cluster: prev / play-pause / next in one row.
        centerRow = new LinearLayout(this);
        centerRow.setOrientation(LinearLayout.HORIZONTAL);
        centerRow.setGravity(Gravity.CENTER_VERTICAL);
        Button prevBtn = mkBtn("⏮");
        prevBtn.setTextSize(22);
        prevBtn.setBackground(ovalBg());
        prevBtn.setOnClickListener(v -> {
            if (player != null && player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
            else Toast.makeText(this, "已经是第一集", Toast.LENGTH_SHORT).show();
            pokeControls();
        });
        centerRow.addView(prevBtn, new LinearLayout.LayoutParams(140, 140));
        playBtn = mkBtn("❚❚");
        playBtn.setTextSize(30);
        playBtn.setBackground(ovalBg());
        playBtn.setOnClickListener(v -> { togglePlay(); pokeControls(); });
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(200, 200);
        playLp.setMargins(44, 0, 44, 0);
        centerRow.addView(playBtn, playLp);
        Button nextBtn = mkBtn("⏭");
        nextBtn.setTextSize(22);
        nextBtn.setBackground(ovalBg());
        nextBtn.setOnClickListener(v -> {
            if (player != null && player.hasNextMediaItem()) player.seekToNextMediaItem();
            else Toast.makeText(this, "已经是最后一集", Toast.LENGTH_SHORT).show();
            pokeControls();
        });
        centerRow.addView(nextBtn, new LinearLayout.LayoutParams(140, 140));
        root.addView(centerRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // Right-edge vertical stack, OK-style: collapse, lock, rotate.
        sideBar = new LinearLayout(this);
        sideBar.setOrientation(LinearLayout.VERTICAL);
        Button collapseBtn = mkBtn("❮");
        collapseBtn.setTextSize(20);
        collapseBtn.setBackground(ovalBg());
        collapseBtn.setOnClickListener(v -> {
            if (fullscreen) setFullscreen(false);
            else finishWithResult();
        });
        sideBar.addView(collapseBtn, new LinearLayout.LayoutParams(120, 120));
        Button sideLockBtn = mkBtn("🔒");
        sideLockBtn.setTextSize(18);
        sideLockBtn.setBackground(ovalBg());
        sideLockBtn.setOnClickListener(v -> setLocked(true));
        LinearLayout.LayoutParams sideLockLp = new LinearLayout.LayoutParams(120, 120);
        sideLockLp.topMargin = 28;
        sideBar.addView(sideLockBtn, sideLockLp);
        Button rotBtn = mkBtn("🔄");
        rotBtn.setTextSize(18);
        rotBtn.setBackground(ovalBg());
        rotBtn.setOnClickListener(v -> rotateScreen());
        LinearLayout.LayoutParams rotLp = new LinearLayout.LayoutParams(120, 120);
        rotLp.topMargin = 28;
        sideBar.addView(rotBtn, rotLp);
        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        sideLp.rightMargin = 16;
        root.addView(sideBar, sideLp);

        // Episode picker panel, hidden until the 选集 menu item is tapped.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF2000000);
        panel.setPadding(24, 16, 24, 24);
        LinearLayout panelHead = new LinearLayout(this);
        panelHead.setOrientation(LinearLayout.HORIZONTAL);
        panelHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView panelTitle = new TextView(this);
        panelTitle.setText("选集");
        panelTitle.setTextColor(0xFFFFFFFF);
        panelTitle.setTextSize(15);
        panelHead.addView(panelTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button panelClose = mkBtn("✕");
        panelClose.setOnClickListener(v -> toggleEpPanel());
        panelHead.addView(panelClose);
        panel.addView(panelHead);
        android.widget.ScrollView epScroll = new android.widget.ScrollView(this);
        epGrid = new android.widget.GridLayout(this);
        epScroll.addView(epGrid, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(epScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        epPanel = panel;
        epPanel.setVisibility(View.GONE);
        root.addView(epPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 900, Gravity.BOTTOM));

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
                // Locked: no controls, but long-press boost still works.
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        ui.removeCallbacks(boostStarter);
                        ui.postDelayed(boostStarter, 450);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getX() - downX) > 24 || Math.abs(e.getY() - downY) > 24) {
                            ui.removeCallbacks(boostStarter);
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(boostStarter);
                        if (boosting) endBoost();
                        else if (e.getAction() == android.view.MotionEvent.ACTION_UP) flashUnlock();
                        return true;
                    default:
                        return true;
                }
            }
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downTime = System.currentTimeMillis();
                    seeking = false;
                    if (player != null) seekBase = player.getCurrentPosition();
                    ui.removeCallbacks(boostStarter);
                    ui.postDelayed(boostStarter, 450);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (player == null) return false;
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (Math.abs(dx) > 24 || Math.abs(dy) > 24) ui.removeCallbacks(boostStarter);
                    if (!seeking && Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        seeking = true;
                        if (boosting) endBoost();
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
                    ui.removeCallbacks(boostStarter);
                    if (boosting) {
                        endBoost();
                        pokeControls();
                        return true;
                    }
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
                        long now = System.currentTimeMillis();
                        if (now - lastTapUp < 350) {
                            // Double tap: play/pause.
                            ui.removeCallbacks(pendingTapToggle);
                            lastTapUp = 0;
                            togglePlay();
                        } else {
                            // Single tap toggles the controls, but wait a
                            // beat in case it turns into a double tap.
                            lastTapUp = now;
                            ui.removeCallbacks(pendingTapToggle);
                            ui.postDelayed(pendingTapToggle, 350);
                        }
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
                    resView.setText(size.width + "×" + size.height + tier);
                }
            }

            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                index = player.getCurrentMediaItemIndex();
                readyForIndex = -1;
                updateTitle();
                seekBar.setProgress(0);
                resView.setText("--");
                if (epPanel != null && epPanel.getVisibility() == View.VISIBLE) buildEpGrid();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                playBtn.setText(isPlaying ? "❚❚" : "▶");
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

        List<MediaItem> items = buildItems();
        if (!items.isEmpty()) {
            Integer startPos = positions.get(index);
            player.setMediaItems(items, index, startPos == null ? 0 : startPos);
            readyForIndex = index; // start position already applied
            player.prepare();
            player.play();
        }
        ui.postDelayed(tick, 500);
    }

    private android.graphics.drawable.GradientDrawable ovalBg() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(0x66000000);
        return d;
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

    private List<MediaItem> buildItems() {
        List<MediaItem> items = new ArrayList<>();
        for (String u : urls) {
            MediaItem.Builder b = new MediaItem.Builder().setUri(u);
            if (u != null && u.toLowerCase().contains("m3u8")) {
                b.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            items.add(b.build());
        }
        return items;
    }

    // Reload the current episode's stream in place (the OK player's 刷新).
    private void refreshPlayer() {
        if (player == null || urls.length == 0) return;
        long pos = player.getCurrentPosition();
        if (pos > 0) positions.put(index, (int) pos);
        List<MediaItem> items = buildItems();
        if (items.isEmpty()) return;
        player.setMediaItems(items, index, Math.max(0, pos));
        readyForIndex = index; // start position already applied
        player.prepare();
        player.play();
        Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show();
    }

    // Manual rotate from the right-edge button: enters fullscreen if
    // needed, then flips between portrait and landscape. The manual
    // choice wins over the aspect-based one until fullscreen exits.
    private void rotateScreen() {
        boolean landscape = root.getWidth() > root.getHeight();
        manualOrient = true;
        if (!fullscreen) setFullscreen(true);
        setRequestedOrientation(landscape
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        pokeControls();
    }

    private void toggleEpPanel() {
        if (epPanel == null) return;
        if (epPanel.getVisibility() == View.VISIBLE) {
            epPanel.setVisibility(View.GONE);
            pokeControls();
            return;
        }
        buildEpGrid();
        int h = Math.min(900, Math.max(400, root.getHeight() * 3 / 5));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) epPanel.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            epPanel.setLayoutParams(lp);
        }
        epPanel.setVisibility(View.VISIBLE);
        showControls(true);
        ui.removeCallbacks(hideControls);
    }

    private void buildEpGrid() {
        if (epGrid == null) return;
        epGrid.removeAllViews();
        int cols = Math.max(3, Math.min(6, root.getWidth() / 300));
        epGrid.setColumnCount(cols);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(names[i]);
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(8, 18, 8, 18);
            chip.setSingleLine(true);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(10);
            if (i == index) {
                bg.setColor(0xFF8A6D00);
                chip.setTextColor(0xFFFFD54F);
            } else {
                bg.setColor(0x2EFFFFFF);
                chip.setTextColor(0xFFFFFFFF);
            }
            chip.setBackground(bg);
            chip.setOnClickListener(v -> {
                epPanel.setVisibility(View.GONE);
                if (player != null && idx != index) player.seekToDefaultPosition(idx);
                pokeControls();
            });
            android.widget.GridLayout.LayoutParams glp = new android.widget.GridLayout.LayoutParams();
            glp.width = 0;
            glp.columnSpec = android.widget.GridLayout.spec(idx % cols, 1f);
            glp.rowSpec = android.widget.GridLayout.spec(idx / cols);
            glp.setMargins(6, 6, 6, 6);
            chip.setLayoutParams(glp);
            epGrid.addView(chip);
        }
    }

    private static String speedText(float s) {
        return String.format(java.util.Locale.US, "%.2f", s);
    }

    // Top-right system readout, OK-style: battery percent over the clock.
    private void updateSys() {
        if (sysView == null) return;
        String clock = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date());
        int pct = -1;
        try {
            android.content.Intent bi = registerReceiver(null, new android.content.IntentFilter(
                    android.content.Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int lv = bi.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int sc = bi.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                if (lv >= 0 && sc > 0) pct = Math.round(lv * 100f / sc);
            }
        } catch (Exception ignored) {
        }
        sysView.setText((pct >= 0 ? "🔋 " + pct + "%\n" : "") + clock);
    }

    private void showControls(boolean show) {
        if (locked) show = false;
        controlsShown = show;
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (centerRow != null) centerRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (sideBar != null) sideBar.setVisibility(show ? View.VISIBLE : View.GONE);
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

    // Long-press boost: hold the screen to fast-forward, release to
    // restore the user's chosen speed. Works while locked too.
    private void startBoost() {
        if (player == null || !player.isPlaying() || boosting) return;
        boosting = true;
        float cur = SPEEDS[speedIdx];
        float boost = cur >= 2.0f ? 3.0f : 2.0f;
        player.setPlaybackSpeed(boost);
        gestureView.setText("⏩ " + speedLabel(boost) + " 快进中");
        gestureView.setVisibility(View.VISIBLE);
    }

    private void endBoost() {
        if (!boosting) return;
        boosting = false;
        if (player != null) player.setPlaybackSpeed(SPEEDS[speedIdx]);
        if (!seeking) gestureView.setVisibility(View.GONE);
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
        if (!fullscreen || manualOrient) return;
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
        if (!fs) manualOrient = false;
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
