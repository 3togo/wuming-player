package com.wuming.android.tv.player.danmaku;

import com.wuming.android.tv.R;
import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.media3.ui.danmaku.DanmakuConfig;

import com.wuming.android.tv.bean.Danmaku;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.UiTaskScope;
import com.wuming.android.tv.utils.AppLog;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.io.IOException;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.ui.widget.DanmakuView;

public class DanPlayer implements DrawHandler.Callback, Player.Listener {

    private final DanmakuContext context;
    private DanmakuView view;
    private LoadJob job;
    private final UiTaskScope tasks = new UiTaskScope();
    private final Runnable startPrepared = this::startPrepared;
    private Player player;
    private Sync sync;
    private long audioOffsetMs;
    private long textOffsetMs;
    private boolean enabled = true;

    public DanPlayer(DanmakuView view) {
        context = DanmakuContext.create();
        view.setCallback(this);
        this.view = view;
        initContext();
    }

    private void initContext() {
        Map<Integer, Integer> lines = new HashMap<>();
        lines.put(BaseDanmaku.TYPE_FIX_TOP, 2);
        lines.put(BaseDanmaku.TYPE_SCROLL_RL, 2);
        lines.put(BaseDanmaku.TYPE_SCROLL_LR, 2);
        lines.put(BaseDanmaku.TYPE_FIX_BOTTOM, 2);
        context.setScaleTextSize(0.8f);
        context.setMaximumLines(lines);
        context.setScrollSpeedFactor(1.2f);
        context.setDanmakuTransparency(0.8f);
        context.setDanmakuMargin(ResUtil.getDimensionPixelSize(R.dimen.vs_8));
        context.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3);
    }

    public void attachPlayer(Player player) {
        detachPlayer();
        if (view == null || player == null) return;
        this.player = player;
        player.addListener(this);
        sync = new Sync(player);
        context.setDanmakuSync(sync);
    }

    public void detachPlayer() {
        if (sync != null) sync.close();
        sync = null;
        if (player != null) player.removeListener(this);
        player = null;
        context.setDanmakuSync(null);
    }

    public boolean ownsView(DanmakuView view) {
        return this.view == view;
    }

    private boolean isPrepared() {
        return view != null && view.isPrepared();
    }

    private void cancel() {
        tasks.remove(startPrepared);
        if (job != null) job.close();
        job = null;
    }

    private void play() {
        if (isPrepared()) view.resume();
    }

    private void pause() {
        if (isPrepared()) view.pause();
    }

    public void stop() {
        cancel();
        if (view != null) view.stop();
    }

    public void release() {
        cancel();
        tasks.close();
        detachPlayer();
        if (view != null) {
            view.setCallback(null);
            view.release();
            view = null;
        }
    }

    public void setDanmaku(Danmaku item) {
        cancel();
        if (view == null) return;
        view.stop();
        if (item == null || item.isEmpty()) return;
        job = new LoadJob(this, item);
        job.future = Task.submit(job);
    }

    private static final class LoadJob implements Runnable, AutoCloseable {
        private final WeakReference<DanPlayer> owner;
        private final Danmaku item;
        private final Loader loader = new Loader();
        private final UiTaskScope delivery = new UiTaskScope();
        private Future<?> future;
        private volatile boolean closed;

        LoadJob(DanPlayer owner, Danmaku item) {
            this.owner = new WeakReference<>(owner);
            this.item = item;
        }

        @Override public void run() {
            try {
                loader.load(item);
                delivery.post(() -> {
                    DanPlayer current = owner.get();
                    if (closed || current == null || current.job != this || current.view == null) {
                        close();
                        return;
                    }
                    current.view.prepare(new Parser().load(loader.getDataSource()), current.context);
                }, 0);
            } catch (IOException e) {
                if (!closed) AppLog.e("DanPlayer", e);
                close();
            }
        }

        @Override public void close() {
            closed = true;
            owner.clear();
            delivery.close();
            loader.close();
            if (future != null) future.cancel(true);
        }
    }

    public void setTextSize(float size) {
        context.setScaleTextSize(size);
    }

    public void addDanmaku(Danmaku item) {
        if (item == null || item.isEmpty() || view == null || !isPrepared()) return;
        AddJob job = new AddJob(this, item, context);
        job.future = Task.submit(job);
    }

    private static final class AddJob implements Runnable, AutoCloseable {
        private final WeakReference<DanPlayer> owner;
        private final Danmaku item;
        private final DanmakuContext context;
        private final Loader loader = new Loader();
        private final UiTaskScope delivery = new UiTaskScope();
        private Future<?> future;
        private volatile boolean closed;

        AddJob(DanPlayer owner, Danmaku item, DanmakuContext context) {
            this.owner = new WeakReference<>(owner);
            this.item = item;
            this.context = context;
        }

        @Override public void run() {
            try {
                loader.load(item);
                delivery.post(() -> {
                    DanPlayer current = owner.get();
                    if (closed || current == null || current.view == null || !current.isPrepared()) {
                        close();
                        return;
                    }
                    Parser parser = new Parser();
                    parser.setDisplayer(context.mDisplayer);
                    parser.load(loader.getDataSource());
                    master.flame.danmaku.danmaku.model.IDanmakus danmakus = parser.parse();
                    for (master.flame.danmaku.danmaku.model.BaseDanmaku dk : danmakus.getCollection()) {
                        current.view.addDanmaku(dk);
                    }
                }, 0);
            } catch (IOException e) {
                if (!closed) AppLog.e("DanPlayer", e);
                close();
            }
        }

        @Override public void close() {
            closed = true;
            owner.clear();
            delivery.close();
            loader.close();
            if (future != null) future.cancel(true);
        }
    }

    public void setConfig(DanmakuConfig config) {
        context.setScaleTextSize(config.textScale);
        context.setDanmakuTransparency(1f - config.transparency);
        context.setDanmakuStyle(getStyle(config.styleMode), getStyleParam(config));
        if (config.durationMs > 0) context.setScrollSpeedFactor(config.durationMs / 8000f);
        Map<Integer, Integer> lines = new HashMap<>();
        lines.put(BaseDanmaku.TYPE_SCROLL_RL, config.maxScrollLines > 0 ? config.maxScrollLines : 2);
        lines.put(BaseDanmaku.TYPE_SCROLL_LR, config.maxScrollLines > 0 ? config.maxScrollLines : 2);
        lines.put(BaseDanmaku.TYPE_FIX_TOP, config.maxTopLines > 0 ? config.maxTopLines : 2);
        lines.put(BaseDanmaku.TYPE_FIX_BOTTOM, config.maxBottomLines > 0 ? config.maxBottomLines : 2);
        context.setMaximumLines(lines);
    }

    public void setSubtitleAvoidance(boolean subtitleVisible) {
        Map<Integer, Integer> lines = new HashMap<>();
        lines.put(BaseDanmaku.TYPE_SCROLL_RL, 2);
        lines.put(BaseDanmaku.TYPE_SCROLL_LR, 2);
        lines.put(BaseDanmaku.TYPE_FIX_TOP, 2);
        lines.put(BaseDanmaku.TYPE_FIX_BOTTOM, subtitleVisible ? 0 : 2);
        context.setMaximumLines(lines);
    }

    private int getStyle(int styleMode) {
        switch (styleMode) {
            case DanmakuConfig.STYLE_SHADOW:
                return IDisplayer.DANMAKU_STYLE_SHADOW;
            case DanmakuConfig.STYLE_STROKE:
                return IDisplayer.DANMAKU_STYLE_STROKEN;
            case DanmakuConfig.STYLE_PROJECTION:
                return IDisplayer.DANMAKU_STYLE_PROJECTION;
            default:
                return IDisplayer.DANMAKU_STYLE_NONE;
        }
    }

    private float getStyleParam(DanmakuConfig config) {
        switch (config.styleMode) {
            case DanmakuConfig.STYLE_SHADOW:
                return config.shadowTransparency;
            case DanmakuConfig.STYLE_STROKE:
                return config.strokeWidthMultiplier * 25f;
            case DanmakuConfig.STYLE_PROJECTION:
                return config.projectionTransparency;
            default:
                return 0f;
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (view == null) return;
        if (enabled) view.show();
        else view.hide();
    }

    public long getAudioOffsetMs() {
        return audioOffsetMs;
    }

    public void setAudioOffsetMs(long offsetMs) {
        this.audioOffsetMs = offsetMs;
    }

    public long getTextOffsetMs() {
        return textOffsetMs;
    }

    public void setTextOffsetMs(long offsetMs) {
        this.textOffsetMs = offsetMs;
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (isPlaying) play();
        else pause();
    }

    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
        if (isPrepared()) view.seekTo(newPosition.positionMs);
    }

    @Override
    public void prepared() {
        tasks.post(startPrepared, 0);
    }

    private void startPrepared() {
        if (job == null || view == null || !isPrepared() || player == null) return;
        view.start(player.getCurrentPosition());
        if (!player.isPlaying()) view.pause();
        if (enabled) view.show();
        else view.hide();
    }

    @Override
    public void updateTimer(DanmakuTimer danmakuTimer) {
    }

    @Override
    public void danmakuShown(BaseDanmaku baseDanmaku) {
    }

    @Override
    public void drawingFinished() {
    }
}
