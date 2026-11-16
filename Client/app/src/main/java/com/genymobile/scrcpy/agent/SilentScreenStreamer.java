package com.genymobile.scrcpy.agent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.screencapture.app.FrameBridge;
import com.screencapture.app.ProjectionResultHolder;
import com.screencapture.app.net.StreamMetrics;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * MediaProjection (screen cast) → VirtualDisplay → JPEG over TCP.
 * Packet format: FrameBridge 'V' chunks with a per-JPEG frameId.
 */
public final class SilentScreenStreamer {

    /**
     * From rc_capture_…105049 on this relay:
     *   1228x690 q=70 full JPEG = 33–56KB → write 0.9s / 3s / 20s
     *   960x540 q=50 full JPEG = 27–32KB, still heavy as a keyframe
     *   768x432 tiles = 3–6KB, 4–7 frames/window, blocked=0%
     * Climbing 768→960→1228 (13:55:08–19) immediately re-blocked.
     * Text is readable at 960x540 q=50; never return to 1228.
     */
    // Ceiling the controller may climb to when the link has headroom. Raised from
    // 30: at 0.50 scale a frame is a quarter of the pixels, so a crisper JPEG still
    // costs far less than a full-size keyframe, and the floor below keeps congested
    // links behaving exactly as before.
    private static final int MAX_JPEG_QUALITY = 45;
    private static final int MIN_JPEG_QUALITY = 22;
    private static final int JPEG_QUALITY_STEP = 5;
    private static final float[] SCALE_STEPS = {0.50f};
    private static final int START_SCALE_INDEX = 0;
    /** Minimum gap between resolution changes so the stream never thrashes. */
    private static final long SCALE_COOLDOWN_MS = 3000;
    /**
     * Keep only the newest pending frame so the desktop never falls behind real
     * time; a deeper queue just replays stale screens later.
     */
    private static final int MAX_ENCODE_QUEUE = 1;
    /** The compositor needs a little more slack than the encode queue. */
    private static final int IMAGE_READER_MAX_IMAGES = 2;
    /** Enough slots to hold every bitmap in circulation so none is reallocated. */
    private static final int MAX_POOLED_BITMAPS = MAX_ENCODE_QUEUE + 2;
    /**
     * 33ms ≈ 30fps, reached only while the desktop's frame_rx feedback reports no
     * queueing and no loss (see onPathFeedback).
     */
    private static final long MIN_INTERVAL_MS = 33;
    /**
     * Static-screen keepalive (see startKeepalive): if no frame has been produced
     * for KEEPALIVE_IDLE_MS, force one fresh frame. Checked every KEEPALIVE_TICK_MS
     * while the desktop is watching, so a reattached desktop always gets a complete
     * frame within ~1s even on a frozen screen.
     */
    private static final long KEEPALIVE_IDLE_MS = 1000;
    private static final long KEEPALIVE_TICK_MS = 400;
    /**
     * Congested floor: 250ms (4fps). Pinning this to MIN_INTERVAL_MS left the
     * stream no way to slow down on a link that cannot carry 20fps, so the surplus
     * sat in the relay and router queues as delay. 500ms (2fps) is what froze 081729.
     */
    private static final long MAX_INTERVAL_MS = 250;

    /*
     * Path feedback controller (onPathFeedback). The desktop echoes frame_rx about
     * every 200ms with the slice loss it saw; matched against our capture time
     * that gives the full loop. Loop minus the lowest loop seen recently is queueing
     * delay — the thing the user perceives as lag — and it is measured end to end,
     * which write-blocking time on a UDP socket never was.
     */
    /** Feedback newer than this disables the write-blocking controller. */
    private static final long FEEDBACK_FRESH_MS = 3000;
    /** Queueing above this, or loss above LOSS_HIGH, means slow down. */
    private static final long QUEUE_HIGH_MS = 120;
    /** Queueing below this with loss below LOSS_LOW means there is headroom. */
    private static final long QUEUE_LOW_MS = 40;
    /** A loop this long is congestion whatever the baseline says. */
    private static final long LOOP_CEILING_MS = 500;
    private static final double LOSS_HIGH = 5.0;
    private static final double LOSS_LOW = 1.0;
    /** Baseline is the minimum loop over the current and previous window. */
    private static final long BASE_WINDOW_MS = 10000;
    /** Give a back-off this long to drain the queue before judging it again. */
    private static final long BACKOFF_HOLD_MS = 400;
    /** No speed-up for this long after a back-off, so the rate does not oscillate. */
    private static final long RECOVER_HOLD_MS = 1500;
    private static final long RECOVER_INTERVAL_STEP_MS = 4;
    /** Unchanged screen is resent at this gap so a still picture stays alive. */
    private static final long STILL_INTERVAL_MS = 300;
    private static final long INTERVAL_STEP_MS = 4;
    /** Adaptation window. Long enough that measured goodput is meaningful. */
    private static final long STAT_WINDOW_MS = 2000;
    /**
     * Ceiling on the paced byte rate. This is a cap, not a target: the effective
     * budget is whichever is lower, this or the measured capacity (see
     * closeStatWindow). It exists because a link fast enough to never push back
     * would otherwise let the stream spend bandwidth it gains nothing from.
     */
    private static final long TARGET_BPS = 450 * 1024;
    /**
     * Share of the window spent blocked inside write() that counts as a full link,
     * and the share that counts as clear headroom.
     *
     * Throughput alone cannot pace this stream, because how many bytes we sent is
     * set by how much the screen changed — a calm screen sending 30KB/s is not
     * evidence of a 30KB/s link. Time spent blocked is the signal that has no such
     * ambiguity: the kernel only makes a write wait once its send buffer is full,
     * which happens only when we are handing over bytes faster than the link
     * drains them. Pacing against a fixed target instead missed this completely on
     * a slow uplink — measured windows sent 38% of the 450KB/s target while single
     * writes blocked for 2.3s, so the controller read "plenty of headroom" and
     * raised quality on a link that was already seconds behind.
     */
    private static final long SATURATED_BLOCKED_PERCENT = 15;
    private static final long CLEAR_BLOCKED_PERCENT = 3;
    /** Spend only this much of measured capacity, so the queues stay drained. */
    private static final long BUDGET_PERCENT = 80;
    /**
     * Frames a window needs before it may raise quality or frame rate. One frame
     * that went out unblocked says nothing about capacity, and acting on it drove a
     * measured failure: on a motionless screen every window held a single refresh
     * frame, read as "well under budget", so quality climbed step by step until each
     * frame no longer fit the link — then the first real change blocked for 15s.
     * Growing the stream needs a window that actually carried a load.
     */
    private static final int MIN_FRAMES_TO_RECOVER = 3;
    /**
     * Longest a single frame may occupy the link. Past this it arrives describing a
     * screen that has already moved on, and it delays everything queued behind it.
     */
    private static final long MAX_FRAME_AIR_MS = 350;
    /**
     * The frame is cut into this grid and only tiles whose pixels changed are sent.
     * A full JPEG of the whole screen costs the same whether one menu opened or the
     * scene changed completely, which is what made 15fps unaffordable on a relay
     * path; typical use touches a small part of the screen, so sending just that
     * part cuts the bytes several-fold at unchanged quality. The grid is a
     * compromise: every tile carries its own JPEG headers (~600 bytes), so a fine
     * grid wastes bandwidth on a full redraw while a coarse one stops isolating the
     * change.
     */
    private static final int TILE_COLS = 8;
    private static final int TILE_ROWS = 4;
    private static final int TILE_COUNT = TILE_COLS * TILE_ROWS;
    /**
     * Full-frame refresh cadence. A viewer that attaches mid-stream is served by an
     * explicit keyframe, so this exists only to give the relay a safe point to shed
     * a backlog at: tile updates are deltas against the viewer's canvas, and one
     * dropped in transit would leave that region wrong until it happened to change
     * again.
     */
    private static final long KEYFRAME_INTERVAL_MS = 8000;
    /**
     * The same refresh, but for a screen that is not changing at all, where it backs
     * off from KEYFRAME_INTERVAL_MS up to this ceiling. Its purpose is to cover a
     * viewer whose screen_monitor_start was missed, which is a startup race — so it
     * needs to be prompt at first and costs nothing but bandwidth once the stream is
     * established. Repeating it at the base cadence forever is what let a motionless
     * screen consume most of a slow uplink.
     */
    private static final long STATIC_KEYFRAME_MAX_MS = 30000;
    /**
     * Above this many changed tiles the update is sent as one whole-screen JPEG.
     * Each tile carries its own JPEG headers, so past roughly this point the tiles
     * add up to more bytes than the single image they are cut from.
     */
    private static final int TILE_FULL_FRAME_TILES = 27;
    /** Windows in a row over/under budget before the resolution step moves. */
    private static final int SCALE_DOWN_WINDOWS = 2;
    /** 105049: scale-up 768→1228 re-opened 3s blocks. Never climb. */
    private static final int SCALE_UP_WINDOWS = 99;
    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR (hidden on some SDKs). */
    private static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4;

    private static final String H264_MIME = "video/avc";
    private static final int H264_FRAME_RATE = 8;
    /** Encoder thread period: capture/encode, enqueue, then sleep. */
    private static final int ENCODER_SLEEP_MS = 120;
    /** Keyframe every second so a dropped frame recovers within ~1s. */
    private static final int H264_IFRAME_INTERVAL_S = 1;
    private static final int H264_MIN_BITRATE = 80_000;
    private static final int H264_MAX_BITRATE = 220_000;
    /** If H.264 sends nothing within this window, fall back to JPEG. */
    private static final long H264_HEALTH_TIMEOUT_MS = 2500;
    /**
     * Master switch for the hardware H.264 path. Kept OFF: in practice — on the
     * LDPlayer software encoder and over a high-latency relay — H.264 both smeared
     * (broken inter-frame prediction the decoder concealed as blocky macroblocks)
     * and lagged. JPEG has no inter-frame dependency, so it can never smear, and
     * measured faster here. The per-frame JPEG pipeline below (with adaptive
     * resolution, quality and frame-diff skipping) is the proven default. The H.264
     * code is left intact so it can be re-enabled for hardware encoders + LAN.
     */
    private static final boolean H264_ENABLED = false;

    private static final SilentScreenStreamer INSTANCE = new SilentScreenStreamer();

    private final Object lock = new Object();
    private final ArrayBlockingQueue<PendingFrame> encodeQueue =
            new ArrayBlockingQueue<>(MAX_ENCODE_QUEUE);
    private final ArrayDeque<Bitmap> bitmapPool = new ArrayDeque<>();
    private final JpegBuffer jpegOut = new JpegBuffer(256 * 1024);
    private final JpegBuffer tileOut = new JpegBuffer(64 * 1024);
    private final Canvas cropCanvas = new Canvas();
    private final Canvas tileCanvas = new Canvas();
    private Bitmap paddedBitmap;
    /** One scratch bitmap per tile, sized to that tile, reused across frames. */
    private final Bitmap[] tileScratch = new Bitmap[TILE_COUNT];
    private int tileScratchWidth;
    private int tileScratchHeight;
    /** Content hash of each tile as last sent, so only changed tiles are encoded. */
    private final long[] sentTileHashes = new long[TILE_COUNT];
    private final long[] tileHashes = new long[TILE_COUNT];
    private long lastKeyframeAt;
    private volatile boolean forceKeyframe = true;
    private volatile boolean rushFrame = false;
    /** Cursor generation last copied into a frame, so a still screen still follows the pointer. */
    private int paintedCursorSeq;

    private HandlerThread thread;
    private Handler handler;
    private Thread encoder;
    private Context app;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private boolean displayWatching;
    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) {
                        return;
                    }
                    Handler h = handler;
                    if (h != null) {
                        h.post(SilentScreenStreamer.this::applyRotationLocked);
                    }
                }
            };
    private ImageReader imageReader;
    private MediaCodec videoEncoder;
    private Surface encoderInputSurface;
    private Thread h264Drain;
    private volatile boolean h264Active;
    private volatile boolean h264ProducedFrame;
    private byte[] h264Csd;
    private volatile boolean running;
    private long lastFrameAt;
    private int captureWidth = 2;
    private int captureHeight = 2;
    private int framesSeen;
    private int framesSent;
    private volatile long intervalMs = MIN_INTERVAL_MS;
    private volatile int jpegQuality = MAX_JPEG_QUALITY;
    private volatile int scaleIndex = 0;
    private volatile boolean scaleChangePending = false;
    private volatile long lastScaleChangeAt = 0;
    private int scaleDownStreak = 0;
    private int scaleUpStreak = 0;
    private int nativeWidth = 0;
    private int nativeHeight = 0;
    private long lastFrameHash = 0;
    private int captureDpi = 160;

    // Send accounting for the current adaptation window (encode thread only).
    private long statWindowAt;
    private long statBytes;
    private int statFrames;
    private long statCostSum;
    private long statCostMax;
    private int statTiles;
    private int statKeyframes;
    /** Measured link goodput in bytes/s (smoothed). 0 until the link pushes back. */
    private volatile long linkBps;
    /** Current backoff of the motionless-screen refresh (see STATIC_KEYFRAME_MAX_MS). */
    private long staticKeyframeIntervalMs = KEYFRAME_INTERVAL_MS;
    /** Size of the last whole-screen JPEG, used to size the warm-up burst. */
    private volatile int lastFullFrameBytes;
    /** True once a busy window has set the pacing, so a re-request keeps it. */
    private volatile boolean tuned;
    /** Consecutive unblocked windows — used to forget a stale capacity estimate. */
    private int unblockedStreak;

    // Static-screen keepalive: forces fresh frames to a watching desktop even when
    // the compositor produces nothing (idle TV). Runs for the whole streaming
    // session so it survives the reconnect race where a frame is written before
    // the desktop's relay attach is ready. Guard so only one loop is ever posted.
    private volatile boolean keepaliveRunning = false;

    // uptimeMillis through which a kick burst is already scheduled (see
    // kickFreshFrameBurst), so overlapping monitor-start paths cannot stack bursts.
    private long burstScheduledUntil = 0L;

    private SilentScreenStreamer() {
    }

    public static void start(Context context) {
        // Android 14 allows exactly one createVirtualDisplay per MediaProjection
        // token. Once capture is live we must never tear it down and recreate it —
        // that throws SecurityException and forces a fresh consent (slow, needs a
        // tap). If already capturing against a still-live projection, just resume.
        //
        // Exception: after a duplicate consent grant killed the cached MediaProjection
        // (or the system stopped it) we can be left with a stale VirtualDisplay while
        // ProjectionResultHolder.isReady() is false. Resuming that state forever
        // freezes the stream and trips the consent-clear loop — force a clean restart.
        if (INSTANCE.isCapturingLiveLocked()) {
            if (ProjectionResultHolder.isReady()) {
                INSTANCE.onMonitorStartLocked();
                return;
            }
            INSTANCE.stopLocked();
        }
        MediaProjection projection = ProjectionResultHolder.obtainProjection(context);
        if (projection != null) {
            INSTANCE.startLocked(context, projection);
            return;
        }
        if (ProjectionResultHolder.wasGranted(context)) {
            return;
        }
    }

    /** True while a VirtualDisplay is actively mirroring the screen. */
    public static boolean isCapturingLive() {
        return INSTANCE.isCapturingLiveLocked();
    }

    private boolean isCapturingLiveLocked() {
        synchronized (lock) {
            return running && virtualDisplay != null;
        }
    }

    public static void stop() {
        INSTANCE.stopLocked();
    }

    public static void noteRateLimited() {
        long cur = INSTANCE.intervalMs;
        INSTANCE.intervalMs = Math.min(MAX_INTERVAL_MS, cur + 40);
    }

    // Path feedback state. Written from the network threads, so guarded separately
    // from `lock`, which the capture and encode threads hold.
    private final Object feedbackLock = new Object();
    private volatile long pathFeedbackAt;
    private long baseLoopPrevMin = Long.MAX_VALUE;
    private long baseLoopCurMin = Long.MAX_VALUE;
    private long baseWindowAt;
    private long lastBackoffAt;

    /**
     * One frame_rx echo: [loopMs] from capture to the desktop's screen and back,
     * [lossPercent] of video slices the desktop never received since the previous
     * echo (negative if unknown).
     */
    public static void onPathFeedback(long loopMs, double lossPercent) {
        INSTANCE.pathFeedback(loopMs, lossPercent);
    }

    /**
     * Frames are going out but nothing is coming back: either every frame is losing
     * a slice or the queue is longer than the probe ring. Both mean slow down.
     */
    public static void onPathStall() {
        INSTANCE.pathFeedback(LOOP_CEILING_MS * 4, 100.0);
    }

    private void pathFeedback(long loopMs, double lossPercent) {
        if (loopMs < 0) {
            return;
        }
        synchronized (feedbackLock) {
            final long now = SystemClock.uptimeMillis();
            pathFeedbackAt = now;
            tuned = true;
            if (baseWindowAt == 0 || now - baseWindowAt >= BASE_WINDOW_MS) {
                baseLoopPrevMin = baseLoopCurMin;
                baseLoopCurMin = Long.MAX_VALUE;
                baseWindowAt = now;
            }
            if (loopMs < baseLoopCurMin) {
                baseLoopCurMin = loopMs;
            }
            final long base = Math.min(baseLoopPrevMin, baseLoopCurMin);
            final long queueMs = loopMs - base;
            final boolean congested = queueMs > QUEUE_HIGH_MS
                    || loopMs > LOOP_CEILING_MS
                    || lossPercent > LOSS_HIGH;
            if (congested) {
                if (now - lastBackoffAt < BACKOFF_HOLD_MS) {
                    return;
                }
                lastBackoffAt = now;
                // Rate first and multiplicatively: a standing queue only drains when
                // we send clearly less than the link carries, and frames are the
                // largest lever left. Quality goes down alongside it.
                intervalMs = Math.min(MAX_INTERVAL_MS, intervalMs * 3 / 2 + 10);
                if (jpegQuality > MIN_JPEG_QUALITY) {
                    jpegQuality = Math.max(MIN_JPEG_QUALITY, jpegQuality - JPEG_QUALITY_STEP);
                }
                return;
            }
            if (queueMs < QUEUE_LOW_MS && lossPercent < LOSS_LOW
                    && now - lastBackoffAt >= RECOVER_HOLD_MS) {
                if (intervalMs > MIN_INTERVAL_MS) {
                    intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs - RECOVER_INTERVAL_STEP_MS);
                } else if (jpegQuality < MAX_JPEG_QUALITY) {
                    jpegQuality = Math.min(MAX_JPEG_QUALITY, jpegQuality + 1);
                }
            }
        }
    }

    private boolean pathFeedbackFresh() {
        final long at = pathFeedbackAt;
        return at != 0 && SystemClock.uptimeMillis() - at < FEEDBACK_FRESH_MS;
    }

    /**
     * Account one sent frame and, when the window closes, size the stream to the
     * throughput actually measured.
     *
     * Timing a single write cannot pace this stream: the socket accepts a frame
     * into its send buffer long before those bytes reach the wire, so per-frame
     * timing only turns slow once the buffer is already full — and by then a
     * second's worth of stale frames is committed to the kernel and can no longer
     * be dropped. That is exactly the reported symptom: the tap has already run on
     * the phone, but its picture is stuck behind frames the link is still draining.
     * Averaged over a window, bytes accepted equal bytes delivered, which is the
     * real capacity to budget against.
     */
    private void noteSendCost(long elapsedMs, int bytes, int tiles, boolean keyframe) {
        statBytes += bytes;
        statFrames++;
        statTiles += tiles;
        if (keyframe) {
            statKeyframes++;
        }
        statCostSum += elapsedMs;
        if (elapsedMs > statCostMax) {
            statCostMax = elapsedMs;
        }
        final long now = SystemClock.uptimeMillis();
        if (statWindowAt == 0) {
            statWindowAt = now;
            return;
        }
        // Close early once half the window went into blocked writes. Waiting out the
        // full window before reacting adds its own delay to a stream that is already
        // behind, and a previous per-frame brake that shed on every slow write is not
        // an option: it drove quality down while the window controller drove it back
        // up, so quality oscillated instead of settling.
        if (now - statWindowAt >= STAT_WINDOW_MS || statCostSum >= STAT_WINDOW_MS / 2) {
            closeStatWindow(now);
        }
    }

    private void resetStatWindow() {
        statWindowAt = 0;
        statBytes = 0;
        statFrames = 0;
        statCostSum = 0;
        statCostMax = 0;
        statTiles = 0;
        statKeyframes = 0;
    }

    private void closeStatWindow(long now) {
        final long windowMs = Math.max(1L, now - statWindowAt);
        final int frames = statFrames;
        final long bytes = statBytes;
        final long costSum = statCostSum;
        resetStatWindow();
        statWindowAt = now;
        if (frames == 0) {
            return;
        }
        // On UDP this controller sees no blocking and reads every window as a clear
        // link, so recoverClearLink would undo each path-feedback back-off within
        // one window. It only stays in charge while no feedback is arriving (an
        // older desktop build).
        if (pathFeedbackFresh()) {
            return;
        }
        final long bps = bytes * 1000L / windowMs;
        final long avgFrame = bytes / frames;
        final long blockedPercent = costSum * 100 / windowMs;
        // Only a window that blocked measures the link. While the send buffer stays
        // full, bytes handed over equal bytes drained, so this window's throughput
        // *is* the capacity. A window that never blocked proves only a lower bound,
        // and must never establish the estimate: on a link far faster than anything
        // we send, every window looks like that, and treating "we sent 72KB/s"
        // as "the link holds 72KB/s" throttles a connection with ample headroom.
        // Hence an unblocked window may raise an existing estimate but never set one.
        if (blockedPercent >= SATURATED_BLOCKED_PERCENT) {
            linkBps = linkBps == 0 ? bps : (linkBps * 3 + bps) / 4;
        } else if (linkBps > 0 && bps > linkBps) {
            linkBps = bps;
        }
        // The floor only guards against a pathological estimate collapsing the
        // budget to nothing; it must stay well under the slowest link we expect to
        // serve, or it becomes a licence to overrun one. At 16KB/s it was exactly
        // that: a link measured at 1KB/s was still budgeted 16KB/s and duly buried.
        final long budgetBps = linkBps > 0
                ? Math.max(4 * 1024, Math.min(TARGET_BPS, linkBps * BUDGET_PERCENT / 100))
                : TARGET_BPS;
        final long overPercent = bps * 100 / budgetBps;
        // A 1-frame keepalive of a 40KB JPEG over a 7s idle window is not a
        // measurement of the link. Treating it as one locked a live session at
        // 2fps (needed = 40KB / 51KB/s → 500ms) for an hour after a single
        // 525ms stall, then dropped resolution because the theoretical air
        // time of that same idle keyframe exceeded MAX_FRAME_AIR_MS.
        final boolean measuredLoad = frames >= MIN_FRAMES_TO_RECOVER
                && windowMs <= STAT_WINDOW_MS * 2;
        if (blockedPercent >= SATURATED_BLOCKED_PERCENT) {
            tuned = true;
        }
        // Only a window that actually carried a stream of frames may set the
        // capture interval from arithmetic. Idle refresh frames must not.
        final boolean pacedByMeasurement = linkBps > 0 && avgFrame > 0 && measuredLoad;
        if (pacedByMeasurement) {
            final long needed = avgFrame * 1000L / budgetBps;
            final long target = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, needed));
            intervalMs = intervalMs + (target - intervalMs) / 2;
        }
        // Blocking outranks the byte comparison: it is the link telling us it is
        // full, whereas being under budget only means we did not happen to send
        // much. Recovering needs both to agree, and needs a window that measured a
        // real load: a window left open for minutes because nothing was moving
        // reports a near-zero byte rate, which is not headroom, and acting on it
        // walked quality back up on a link that could not carry it.
        if (blockedPercent >= SATURATED_BLOCKED_PERCENT) {
            unblockedStreak = 0;
            shedBytes(Math.max(overPercent, 100 + blockedPercent * 2), !pacedByMeasurement);
        } else if (overPercent > 115 && measuredLoad) {
            unblockedStreak = 0;
            shedBytes(overPercent, !pacedByMeasurement);
        } else if (blockedPercent <= CLEAR_BLOCKED_PERCENT) {
            recoverClearLink(measuredLoad, overPercent, pacedByMeasurement);
        } else {
            scaleDownStreak = 0;
            scaleUpStreak = 0;
        }
    }

    /**
     * The send buffer did not fill this window. Pull the capture interval back
     * toward the floor even on a 1-frame keepalive — otherwise one stall leaves
     * the stream at 2fps until the next blocked write, which may never come.
     * A stale linkBps is also raised and eventually forgotten, because an
     * unblocked window cannot send faster than the interval it is stuck at,
     * so it can never prove the estimate was too low by throughput alone.
     */
    private void recoverClearLink(boolean measuredLoad, long overPercent,
            boolean pacedByMeasurement) {
        unblockedStreak++;
        // A busy window already set the interval from measured frame size.
        // Only idle/light windows may pull it back — those are the ones that
        // used to lock 2fps from a single keepalive JPEG.
        if (intervalMs > MIN_INTERVAL_MS && !pacedByMeasurement) {
            intervalMs = Math.max(MIN_INTERVAL_MS, (intervalMs + MIN_INTERVAL_MS) / 2);
        }
        // Do not invent extra capacity here. The 105049 log climbed
        // cap 42→323KB/s on unblocked windows, scaled back up to 1228x690,
        // then blocked for 3–20s. Only a higher measured bps may raise cap.
        if (overPercent < 85 && measuredLoad) {
            addBytes(!pacedByMeasurement);
        } else {
            scaleDownStreak = 0;
            scaleUpStreak = 0;
        }
    }

    /**
     * Over budget: give up the cheapest thing first (JPEG quality), then frame
     * rate, and only drop resolution when neither was enough. Steps grow with the
     * overshoot — crawling one step per window takes ten seconds to react, and
     * every one of those seconds is added to the delay the user is already seeing.
     */
    private void shedBytes(long overPercent, boolean mayChangeRate) {
        scaleUpStreak = 0;
        final int steps = overPercent >= 250 ? 4 : (overPercent >= 150 ? 2 : 1);
        if (jpegQuality > MIN_JPEG_QUALITY) {
            jpegQuality = Math.max(MIN_JPEG_QUALITY, jpegQuality - JPEG_QUALITY_STEP * steps);
            scaleDownStreak = 0;
            return;
        }
        // Quality is spent, so drop pixels before frames. Stepping 0.64 down to 0.50
        // removes 39% of the bytes at once, where reaching the same saving through
        // the frame rate means crawling to a slideshow — and a slightly soft picture
        // that follows the finger is easier to work with than a sharp one that
        // arrives three times a second.
        if (!h264Active && !scaleChangePending
                && scaleIndex < SCALE_STEPS.length - 1
                && ++scaleDownStreak >= SCALE_DOWN_WINDOWS) {
            requestScaleChange(scaleIndex + 1);
            return;
        }
        if (mayChangeRate && intervalMs < MAX_INTERVAL_MS) {
            intervalMs = Math.min(MAX_INTERVAL_MS, intervalMs + INTERVAL_STEP_MS * 4 * steps);
        }
    }

    /**
     * Room to spare: recover frame rate first, then quality, resolution last.
     * mayChangeRate is false once capacity is measured, because the rate is then
     * computed from it and two controllers moving one value only fight each other.
     */
    private void addBytes(boolean mayChangeRate) {
        scaleDownStreak = 0;
        if (mayChangeRate && intervalMs > MIN_INTERVAL_MS) {
            intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs - INTERVAL_STEP_MS * 4);
            scaleUpStreak = 0;
            return;
        }
        if (jpegQuality < MAX_JPEG_QUALITY) {
            jpegQuality = Math.min(MAX_JPEG_QUALITY, jpegQuality + JPEG_QUALITY_STEP);
            scaleUpStreak = 0;
            return;
        }
        // Do not raise resolution. The 105049 session was smooth at 768x432
        // (3–6KB tiles) and stalled the moment it scaled back to 1228x690.
    }

    private void requestScaleChange(int newIndex) {
        long now = SystemClock.uptimeMillis();
        if (now - lastScaleChangeAt < SCALE_COOLDOWN_MS) {
            return;
        }
        scaleDownStreak = 0;
        scaleUpStreak = 0;
        Handler h = handler;
        if (h == null) {
            return;
        }
        scaleChangePending = true;
        final int target = Math.max(0, Math.min(SCALE_STEPS.length - 1, newIndex));
        h.post(() -> applyScaleChangeLocked(target));
    }

    /**
     * Recreate the capture at a new resolution in place: a fresh ImageReader is
     * wired to the existing VirtualDisplay via setSurface, so the desktop simply
     * starts receiving smaller (or larger) whole frames — no protocol change,
     * each frame already carries its own dimensions. Runs on the capture handler
     * thread, which serialises it against onImage.
     */
    private void applyScaleChangeLocked(int newIndex) {
        synchronized (lock) {
            try {
                if (!running || h264Active || virtualDisplay == null || imageReader == null
                        || handler == null || newIndex == scaleIndex) {
                    return;
                }
                int[] size = sizeForIndex(newIndex);
                int w = size[0];
                int h = size[1];
                ImageReader newReader = ImageReader.newInstance(
                        w, h, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
                newReader.setOnImageAvailableListener(this::onImage, handler);
                try {
                    virtualDisplay.resize(w, h, captureDpi);
                    virtualDisplay.setSurface(newReader.getSurface());
                } catch (Exception e) {
                    newReader.close();
                    return;
                }
                ImageReader old = imageReader;
                imageReader = newReader;
                old.setOnImageAvailableListener(null, null);
                old.close();
                scaleIndex = newIndex;
                captureWidth = w;
                captureHeight = h;
                lastFrameAt = 0;
                lastFrameHash = 0;
                forceKeyframe = true;
                // Keep the operating point. Resetting quality and rate to their
                // fastest here fought the very congestion that asked for fewer
                // pixels: quality was shed to the floor, the step down then handed
                // it all back, and the link was overrun again — so the stream
                // oscillated down through every resolution instead of settling.
                // Frame sizes did change by ~40%, so the measurement window has to
                // restart or the new resolution is judged by the old one's bytes.
                resetStatWindow();
                lastScaleChangeAt = SystemClock.uptimeMillis();
            } finally {
                scaleChangePending = false;
            }
        }
    }

    /**
     * The phone turned. The capture buffer was created at the old orientation, so
     * a landscape screen was still being sent as a portrait picture and clicks
     * no longer lined up with it.
     */
    private void applyRotationLocked() {
        synchronized (lock) {
            if (!running || h264Active || virtualDisplay == null || imageReader == null
                    || handler == null) {
                return;
            }
            int[] size = computeCaptureSize();
            int w = size[0];
            int h = size[1];
            if (w == captureWidth && h == captureHeight) {
                return;
            }
            ImageReader newReader;
            try {
                newReader = ImageReader.newInstance(
                        w, h, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
            } catch (Exception e) {
                return;
            }
            newReader.setOnImageAvailableListener(this::onImage, handler);
            try {
                virtualDisplay.resize(w, h, captureDpi);
                virtualDisplay.setSurface(newReader.getSurface());
            } catch (Exception e) {
                newReader.close();
                return;
            }
            ImageReader old = imageReader;
            imageReader = newReader;
            old.setOnImageAvailableListener(null, null);
            old.close();
            captureWidth = w;
            captureHeight = h;
            lastFrameAt = 0;
            lastFrameHash = 0;
            forceKeyframe = true;
            resetStatWindow();
        }
    }

    private void watchDisplay() {
        if (displayWatching) {
            return;
        }
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return;
        }
        dm.registerDisplayListener(displayListener, handler);
        displayWatching = true;
    }

    private void unwatchDisplay() {
        if (!displayWatching) {
            return;
        }
        displayWatching = false;
        try {
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                dm.unregisterDisplayListener(displayListener);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * After a remote Back/Home/touch the screen changes, but a high capture
     * interval would otherwise delay the first updated frame. One nudge is enough.
     */
    public static void nudge() {
        INSTANCE.nudgeLocked();
    }

    /**
     * Called when a desktop asks to start monitoring. If capture is already live,
     * warm up fresh frames so the window fills in immediately instead of waiting
     * for the next on-screen change. If capture is still coming up, the fresh
     * prepareDisplay path warms up on its own, so this is a safe no-op then.
     */
    public static void onMonitorStart() {
        INSTANCE.onMonitorStartLocked();
    }

    private void onMonitorStartLocked() {
        if (running && virtualDisplay != null) {
            // A monitor (re)open must feel as fast as a fresh capture, so start from
            // the fast baseline — but only while we still know nothing about the
            // link. The desktop's stall watchdog re-sends screen_monitor_start
            // repeatedly, and jumping back to full quality each time discarded a
            // measured operating point and refilled the send queue that was the
            // delay in the first place.
            if (!tuned) {
                intervalMs = MIN_INTERVAL_MS;
                jpegQuality = MAX_JPEG_QUALITY;
                scaleDownStreak = 0;
                scaleUpStreak = 0;
                resetStatWindow();
                if (scaleIndex != START_SCALE_INDEX && !scaleChangePending) {
                    lastScaleChangeAt = 0;
                    requestScaleChange(START_SCALE_INDEX);
                }
            }
            kickFreshFrameBurst();
        }
        // Arm the keepalive even if capture is not ready yet; prepareDisplay also
        // arms it on completion, so the first frame lands whichever finishes last.
        startKeepalive();
    }

    private void nudgeLocked() {
        if (!running || virtualDisplay == null) {
            return;
        }
        // Release the next frame immediately rather than waiting out the interval,
        // which is what makes a tap feel answered. The interval itself is left
        // alone: touches arrive continuously during use, and pulling it back on each
        // one cancelled the pacing as fast as it was applied.
        // No kickFreshFrame() here: the input itself changes the screen, which
        // produces the frame. Resizing the VirtualDisplay on every click, move and
        // key made the compositor reconfigure capture 60 times a second and was
        // the lag on Back/Home/Recents and on every tap.
        lastFrameAt = 0;
        rushFrame = true;
    }

    public static void pause() {
        // Kept for call-site compatibility; capture no longer pauses because the
        // desktop window freezes if the projection stops sending.
    }

    public static void resume() {
        // Kept for call-site compatibility (see pause()).
    }

    private void startLocked(Context context, MediaProjection mediaProjection) {
        synchronized (lock) {
            app = context.getApplicationContext();
            running = true;
            lastFrameAt = 0;
            framesSeen = 0;
            framesSent = 0;
            intervalMs = MIN_INTERVAL_MS;
            jpegQuality = MAX_JPEG_QUALITY;
            scaleIndex = START_SCALE_INDEX;
            scaleDownStreak = 0;
            scaleUpStreak = 0;
            scaleChangePending = false;
            lastScaleChangeAt = 0;
            lastFrameHash = 0;
            forceKeyframe = true;
            // A new capture session may be on a different network hours later, so
            // let it find its own pacing instead of inheriting the last one.
            tuned = false;
            unblockedStreak = 0;
            linkBps = 0;
            synchronized (feedbackLock) {
                pathFeedbackAt = 0;
                baseLoopPrevMin = Long.MAX_VALUE;
                baseLoopCurMin = Long.MAX_VALUE;
                baseWindowAt = 0;
                lastBackoffAt = 0;
            }
            lastFullFrameBytes = 0;
            staticKeyframeIntervalMs = KEYFRAME_INTERVAL_MS;
            resetStatWindow();
            if (encoder == null || !encoder.isAlive()) {
                encoder = new Thread(this::encodeLoop, "SilentEncode");
                encoder.setPriority(Thread.NORM_PRIORITY + 1);
                encoder.start();
            }
            if (virtualDisplay != null && projection == mediaProjection) {
                // Already mirroring, but the compositor only emits a frame when the
                // screen changes. A desktop attaching to a static screen would wait
                // forever, so force one fresh frame instead of returning silently.
                kickFreshFrameBurst();
                startKeepalive();
                return;
            }
            if (!prepareDisplay(mediaProjection)) {
                running = false;
            }
        }
    }

    private boolean prepareDisplay(MediaProjection mediaProjection) {
        releaseSurfacesLocked();
        projection = mediaProjection;
        int[] size = computeCaptureSize();
        captureWidth = size[0];
        captureHeight = size[1];
        DisplayMetrics metrics = app.getResources().getDisplayMetrics();
        captureDpi = Math.max(160, metrics.densityDpi);
        thread = new HandlerThread("SilentStreamer", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());

        // Optional path: hardware H.264 straight off the VirtualDisplay surface.
        // Far less CPU and bandwidth than per-frame JPEG, but only decodable on a
        // desktop that has a working H.264 decoder, so it is gated behind
        // H264_ENABLED and off by default. When disabled we go straight to the
        // proven JPEG/ImageReader pipeline below.
        if (H264_ENABLED && setupH264Locked()) {
            try {
                virtualDisplay = projection.createVirtualDisplay(
                        "RemoteAppCapture",
                        captureWidth,
                        captureHeight,
                        captureDpi,
                        VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        encoderInputSurface,
                        null,
                        handler);
                // Guarantee a picture: if the encoder never delivers a frame (some
                // boards silently fail to feed a surface-input encoder), drop to the
                // proven JPEG pipeline so the desktop is never stuck on a blank view.
                h264ProducedFrame = false;
                final MediaProjection watched = mediaProjection;
                handler.postDelayed(() -> checkH264Health(watched), H264_HEALTH_TIMEOUT_MS);
                watchDisplay();
                return true;
            } catch (Exception e) {
                teardownH264Locked();
            }
        }

        try {
            imageReader = ImageReader.newInstance(captureWidth, captureHeight,
                    PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
            imageReader.setOnImageAvailableListener(this::onImage, handler);
            virtualDisplay = projection.createVirtualDisplay(
                    "RemoteAppCapture",
                    captureWidth,
                    captureHeight,
                    captureDpi,
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler);
            // Fresh mirror: the first composition already produces a frame, but the
            // desktop window may still be attaching its receiver, so warm up a few
            // kicks to make sure that first picture is not missed on a static screen.
            kickFreshFrameBurst();
            startKeepalive();
            watchDisplay();
            return true;
        } catch (Exception e) {
            releaseSurfacesLocked();
            return false;
        }
    }

    /**
     * Configure a hardware AVC encoder that draws from its own input Surface.
     * Dimensions are aligned to 16 for broad encoder compatibility. Returns false
     * (and cleans up) on any failure so the caller can use the JPEG path.
     */
    private boolean setupH264Locked() {
        int w = captureWidth & ~15;
        int h = captureHeight & ~15;
        if (w < 16 || h < 16) {
            return false;
        }
        // Constrained Baseline has no B-frames, so decode order equals display
        // order — exactly what the desktop low-latency decoder needs. If a device
        // rejects the profile, retry once without it before giving up on H.264.
        if (startEncoderLocked(w, h, true)) {
            return true;
        }
        return startEncoderLocked(w, h, false);
    }

    private boolean startEncoderLocked(int w, int h, boolean forceBaseline) {
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(H264_MIME, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, computeBitrate(w, h));
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, H264_FRAME_RATE);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, H264_IFRAME_INTERVAL_S);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                fmt.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fmt.setInteger(MediaFormat.KEY_LATENCY, 0);
            }
            fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L);
            if (forceBaseline) {
                fmt.setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline);
            }
            videoEncoder = MediaCodec.createEncoderByType(H264_MIME);
            videoEncoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();
            captureWidth = w;
            captureHeight = h;
            h264Csd = null;
            h264Active = true;
            h264Drain = new Thread(this::h264DrainLoop, "SilentH264");
            h264Drain.start();
            return true;
        } catch (Throwable t) {
            teardownH264Locked();
            return false;
        }
    }

    private int computeBitrate(int w, int h) {
        long bits = (long) w * h;
        if (bits < H264_MIN_BITRATE) {
            return H264_MIN_BITRATE;
        }
        if (bits > H264_MAX_BITRATE) {
            return H264_MAX_BITRATE;
        }
        return (int) bits;
    }

    /**
     * Drain encoded Annex B buffers and forward them in order. Codec-config
     * (SPS/PPS) is cached and prepended to every keyframe so the desktop decoder
     * can start from any keyframe. Sending blocks, which naturally back-pressures
     * the encoder (surface frames coalesce) instead of ever dropping a buffer and
     * corrupting the stream.
     */
    private void h264DrainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (h264Active && running) {
            MediaCodec enc = videoEncoder;
            if (enc == null) {
                return;
            }
            int idx;
            try {
                idx = enc.dequeueOutputBuffer(info, 0);
            } catch (Throwable t) {
                return;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    MediaFormat of = enc.getOutputFormat();
                    h264Csd = concatCsd(of.getByteBuffer("csd-0"), of.getByteBuffer("csd-1"));
                } catch (Throwable ignored) {
                }
                if (!encoderSleep()) return;
                continue;
            }
            if (idx < 0) {
                if (!encoderSleep()) return;
                continue;
            }
            ByteBuffer out;
            try {
                out = enc.getOutputBuffer(idx);
            } catch (Throwable t) {
                return;
            }
            try {
                if (out != null && info.size > 0) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        byte[] csd = new byte[info.size];
                        out.get(csd);
                        h264Csd = csd;
                    } else {
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        byte[] nal = new byte[info.size];
                        out.get(nal);
                        nal = ensureAnnexB(nal);
                        byte[] payload = nal;
                        if (key && h264Csd != null) {
                            byte[] csd = ensureAnnexB(h264Csd);
                            payload = new byte[csd.length + nal.length];
                            System.arraycopy(csd, 0, payload, 0, csd.length);
                            System.arraycopy(nal, 0, payload, csd.length, nal.length);
                        }
                        FrameBridge.sendFrame(payload, captureWidth, captureHeight, payload.length,
                                SystemClock.uptimeMillis());
                        h264ProducedFrame = true;
                        framesSent++;
                    }
                }
            } catch (Throwable t) {
            } finally {
                try {
                    enc.releaseOutputBuffer(idx, false);
                } catch (Throwable ignored) {
                }
            }
            if (!encoderSleep()) return;
        }
    }

    private static boolean encoderSleep() {
        try {
            Thread.sleep(ENCODER_SLEEP_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** MediaCodec often emits length-prefixed NALs. The desktop only decodes Annex B. */
    private static byte[] ensureAnnexB(byte[] data) {
        if (data == null || data.length < 4) {
            return data;
        }
        if (data[0] == 0 && data[1] == 0 && (data[2] == 1 || (data[2] == 0 && data[3] == 1))) {
            return data;
        }
        if (data[0] == 1) {
            return avccToAnnexB(data);
        }
        int need = 0;
        int off = 0;
        while (off + 4 <= data.length) {
            int n = ((data[off] & 0xFF) << 24) | ((data[off + 1] & 0xFF) << 16)
                    | ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
            if (n <= 0 || off + 4 + n > data.length) {
                return data;
            }
            need += 4 + n;
            off += 4 + n;
        }
        if (off != data.length) {
            return data;
        }
        byte[] out = new byte[need];
        int w = 0;
        off = 0;
        while (off + 4 <= data.length) {
            int n = ((data[off] & 0xFF) << 24) | ((data[off + 1] & 0xFF) << 16)
                    | ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
            out[w] = 0;
            out[w + 1] = 0;
            out[w + 2] = 0;
            out[w + 3] = 1;
            System.arraycopy(data, off + 4, out, w + 4, n);
            w += 4 + n;
            off += 4 + n;
        }
        return out;
    }

    private static byte[] avccToAnnexB(byte[] avcc) {
        if (avcc.length < 8) {
            return avcc;
        }
        int off = 5;
        int spsCount = avcc[off++] & 0x1F;
        byte[][] nals = new byte[8][];
        int count = 0;
        int bytes = 0;
        for (int i = 0; i < spsCount && off + 2 <= avcc.length && count < nals.length; i++) {
            int n = ((avcc[off] & 0xFF) << 8) | (avcc[off + 1] & 0xFF);
            off += 2;
            if (n <= 0 || off + n > avcc.length) {
                return avcc;
            }
            nals[count] = new byte[n];
            System.arraycopy(avcc, off, nals[count], 0, n);
            bytes += 4 + n;
            count++;
            off += n;
        }
        if (off >= avcc.length) {
            return avcc;
        }
        int ppsCount = avcc[off++] & 0xFF;
        for (int i = 0; i < ppsCount && off + 2 <= avcc.length && count < nals.length; i++) {
            int n = ((avcc[off] & 0xFF) << 8) | (avcc[off + 1] & 0xFF);
            off += 2;
            if (n <= 0 || off + n > avcc.length) {
                return avcc;
            }
            nals[count] = new byte[n];
            System.arraycopy(avcc, off, nals[count], 0, n);
            bytes += 4 + n;
            count++;
            off += n;
        }
        byte[] out = new byte[bytes];
        int w = 0;
        for (int i = 0; i < count; i++) {
            out[w] = 0;
            out[w + 1] = 0;
            out[w + 2] = 0;
            out[w + 3] = 1;
            System.arraycopy(nals[i], 0, out, w + 4, nals[i].length);
            w += 4 + nals[i].length;
        }
        return out;
    }

    private static byte[] concatCsd(ByteBuffer sps, ByteBuffer pps) {
        int spsLen = sps == null ? 0 : sps.remaining();
        int ppsLen = pps == null ? 0 : pps.remaining();
        if (spsLen + ppsLen == 0) {
            return null;
        }
        byte[] csd = new byte[spsLen + ppsLen];
        if (spsLen > 0) {
            sps.get(csd, 0, spsLen);
        }
        if (ppsLen > 0) {
            pps.get(csd, spsLen, ppsLen);
        }
        return csd;
    }

    /**
     * Runs on the streamer thread. If the H.264 encoder produced no frame in the
     * health window, rebuild the capture on the JPEG path for this same session.
     */
    private void checkH264Health(MediaProjection watched) {
        synchronized (lock) {
            if (!running || !h264Active || projection != watched) {
                return;
            }
            if (h264ProducedFrame) {
                return;
            }
            switchToJpegLocked();
        }
    }

    /** Swap a stalled H.264 capture for the JPEG/ImageReader pipeline in place. */
    private void switchToJpegLocked() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        teardownH264Locked();
        MediaProjection proj = projection;
        Handler h = handler;
        if (proj == null || h == null) {
            return;
        }
        try {
            imageReader = ImageReader.newInstance(captureWidth, captureHeight,
                    PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES);
            imageReader.setOnImageAvailableListener(this::onImage, h);
            virtualDisplay = proj.createVirtualDisplay(
                    "RemoteAppCapture",
                    captureWidth,
                    captureHeight,
                    captureDpi,
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    h);
            lastFrameAt = 0;
            lastFrameHash = 0;
            forceKeyframe = true;
            intervalMs = MIN_INTERVAL_MS;
            jpegQuality = MAX_JPEG_QUALITY;
        } catch (Exception e) {
        }
    }

    private void teardownH264Locked() {
        h264Active = false;
        Thread drain = h264Drain;
        h264Drain = null;
        if (drain != null && drain != Thread.currentThread()) {
            drain.interrupt();
            try {
                drain.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
            } catch (Throwable ignored) {
            }
            try {
                videoEncoder.release();
            } catch (Throwable ignored) {
            }
            videoEncoder = null;
        }
        if (encoderInputSurface != null) {
            try {
                encoderInputSurface.release();
            } catch (Throwable ignored) {
            }
            encoderInputSurface = null;
        }
        h264Csd = null;
    }

    /**
     * One-shot nudge: briefly resize the VirtualDisplay so the compositor renders
     * a frame even when the screen is static. Used when a desktop attaches to an
     * already-running mirror; deliberately not a repeating watchdog.
     */
    /**
     * A desktop attaching to a static screen is the classic "blank until I click"
     * case. Two things caused it: (1) frame-diff skipping suppressed the current
     * frame because it equals the last one already sent, and (2) a single kick
     * could land before the desktop's monitor window finished attaching its frame
     * receiver. This resets the dedupe baseline so the current screen is re-sent,
     * pulls the interval to realtime, then fires a short bounded burst of kicks so
     * at least one fresh frame lands after the window is listening. It is not a
     * persistent watchdog — it stops after ~1.5s.
     */
    private void kickFreshFrameBurst() {
        lastFrameAt = 0;
        lastFrameHash = 0;
        forceKeyframe = true;
        staticKeyframeIntervalMs = KEYFRAME_INTERVAL_MS;
        if (!tuned) {
            // Only before the link has been measured. The desktop's stall watchdog
            // re-requests capture while congested, and resetting the rate on every
            // such request handed back the pacing that had just been shed — the
            // stream stalled, sped up, and stalled again instead of settling.
            intervalMs = MIN_INTERVAL_MS;
        }
        final Handler h = handler;
        if (h == null || virtualDisplay == null || !running) {
            kickFreshFrame();
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (now < burstScheduledUntil) {
            // A burst is already in flight. One screen_monitor_start reaches the
            // streamer twice (the service start plus onMonitorStart), and each burst
            // forces full frames with dedup off — re-arming doubles that cost on a
            // link that is usually already the bottleneck.
            return;
        }
        final long[] delays = {0L, 400L};
        // Unknown-link bursts of 6×40KB JPEGs are what filled the socket before
        // the first tap (105049: 918ms then 3s then 20s writes). One picture,
        // plus one retry, is enough for an attaching viewer.
        int count = 1;
        final long cap = linkBps;
        final int frameBytes = lastFullFrameBytes;
        if (cap > 0 && frameBytes > 0) {
            final long affordable = cap * delays[delays.length - 1] / 1000 / 2 / frameBytes;
            count = (int) Math.max(1, Math.min(delays.length, affordable));
        }
        burstScheduledUntil = now + delays[count - 1];
        for (int i = 0; i < count; i++) {
            h.postDelayed(this::kickFreshFrame, delays[i]);
        }
    }

    private void kickFreshFrame() {
        if (h264Active) {
            // Resizing the display would resize the encoder input surface and
            // corrupt the stream; ask the encoder for an immediate keyframe instead.
            MediaCodec enc = videoEncoder;
            if (enc != null) {
                try {
                    Bundle params = new Bundle();
                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    enc.setParameters(params);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        final Handler h = handler;
        if (h == null || virtualDisplay == null || !running) {
            return;
        }
        final int w = Math.max(2, captureWidth);
        final int hgt = Math.max(2, captureHeight);
        h.post(() -> {
            VirtualDisplay live = virtualDisplay;
            if (live == null || !running) {
                return;
            }
            try {
                live.resize(w + 2, hgt + 2, captureDpi);
                h.postDelayed(() -> {
                    VirtualDisplay again = virtualDisplay;
                    if (again != null && running) {
                        try {
                            again.resize(w, hgt, captureDpi);
                        } catch (Exception ignored) {
                        }
                    }
                }, 60);
            } catch (Exception e) {
            }
        });
    }

    /**
     * Permanent low-rate keepalive that guarantees a freshly attached desktop
     * always receives a complete frame within ~1s — even when the screen is static.
     *
     * The previous bounded warmup stopped as soon as one frame was written to the
     * relay socket. On reconnect that write can happen before the desktop's relay
     * attach is ready: the relay clears its per-agent queue and only then starts
     * piping, so a frame produced during the burst is discarded. On a static TV
     * screen no further frames are produced, leaving the reattached desktop on the
     * waiting screen until something on screen changed (the reported instability).
     *
     * Keeping a low-rate keepalive alive for the whole streaming session removes
     * that race: after the relay finishes attaching, the next keepalive frame
     * reaches it. It is a no-op while real motion is flowing (a frame was produced
     * within KEEPALIVE_IDLE_MS) and stops the instant the desktop stops watching,
     * so an idle screen costs at most ~1 forced JPEG per second.
     */
    private void startKeepalive() {
        if (keepaliveRunning) {
            return;
        }
        final Handler h = handler;
        if (h == null) {
            return;
        }
        keepaliveRunning = true;
        h.postDelayed(this::keepaliveTick, KEEPALIVE_TICK_MS);
    }

    private void keepaliveTick() {
        final Handler h = handler;
        if (h == null || !running || !FrameBridge.isStreaming()) {
            keepaliveRunning = false; // a later monitor-start re-arms it
            return;
        }
        if (virtualDisplay != null && !h264Active) {
            long idle = System.currentTimeMillis() - lastFrameAt;
            if (lastFrameAt == 0 || idle >= KEEPALIVE_IDLE_MS) {
                // Static screen (or just attached): the next captured frame would be
                // deduped as identical, so clear the dedupe baseline and force a
                // fresh one. It has to be a whole frame, because a viewer that just
                // attached has nothing for tile updates to be applied to. A viewer
                // opening its window announces itself with screen_monitor_start,
                // which forces one immediately, so this is only the fallback for
                // that announcement being missed — hence the backing-off interval,
                // which keeps the fallback prompt without charging a motionless
                // screen a full frame every few seconds forever.
                lastFrameHash = 0;
                if (SystemClock.uptimeMillis() - lastKeyframeAt >= staticKeyframeIntervalMs) {
                    forceKeyframe = true;
                    staticKeyframeIntervalMs = Math.min(STATIC_KEYFRAME_MAX_MS,
                            staticKeyframeIntervalMs * 2);
                    // Only on the backed-off schedule: kicking every tick resized the
                    // VirtualDisplay every 400ms for as long as the screen was still.
                    kickFreshFrame();
                }
            }
        }
        h.postDelayed(this::keepaliveTick, KEEPALIVE_TICK_MS);
    }

    private void stopLocked() {
        synchronized (lock) {
            running = false;
            releaseSurfacesLocked();
            drainEncodeQueueLocked();
            recycleBitmapPoolLocked();
        }
        Thread enc = encoder;
        if (enc != null) {
            enc.interrupt();
        }
    }

    private void onImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || !running) {
                return;
            }
            // Desktop not watching (monitor closed): keep the VirtualDisplay and
            // projection alive — Android 14 won't let us recreate them — but skip
            // the copy/encode so a paused stream costs almost no CPU or battery.
            if (!FrameBridge.isStreaming()) {
                return;
            }
            framesSeen++;
            long now = System.currentTimeMillis();
            RemoteCursor.Spot cursor = RemoteCursor.spot();
            boolean cursorDirty = cursor.seq != paintedCursorSeq;
            if (!rushFrame && !cursorDirty && lastFrameAt != 0 && now - lastFrameAt < intervalMs) {
                StreamMetrics.noteSkippedInterval();
                return;
            }
            // Hash each tile, not just the whole frame. The combined value still
            // skips a completely static screen for free, and the per-tile values
            // tell the encoder which regions actually have to be sent.
            // A moving pointer does not change those pixels (the overlay is left
            // out of the capture), so it has to count as a change on its own.
            long hashStartedAt = SystemClock.uptimeMillis();
            long frameHash = hashTiles(image, tileHashes);
            StreamMetrics.noteCaptured(SystemClock.uptimeMillis() - hashStartedAt);
            boolean unchanged = frameHash != 0 && frameHash == lastFrameHash && !forceKeyframe;
            if (!rushFrame && unchanged && !cursorDirty && lastFrameAt != 0
                    && now - lastFrameAt < STILL_INTERVAL_MS) {
                StreamMetrics.noteSkippedUnchanged();
                return;
            }
            Bitmap frame = copyImageToBitmap(image);
            if (frame == null) {
                return;
            }
            lastFrameAt = now;
            lastFrameHash = frameHash;
            paintedCursorSeq = cursor.seq;
            offerEncode(new PendingFrame(frame, tileHashes.clone(), SystemClock.uptimeMillis()));
        } catch (Exception e) {
            // A stop() racing an in-flight frame closes the reader/image under us.
            // That is expected teardown, not a fault.
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private void offerEncode(PendingFrame frame) {
        if (frame == null) {
            return;
        }
        synchronized (lock) {
            while (encodeQueue.size() >= MAX_ENCODE_QUEUE) {
                PendingFrame stale = encodeQueue.poll();
                if (stale == null) {
                    break;
                }
                // The skipped frame's tiles were never sent, so its hashes must not
                // count as delivered — the next frame has to resend those regions.
                StreamMetrics.noteSkippedQueue();
                releaseBitmap(stale.bitmap);
            }
            if (!encodeQueue.offer(frame)) {
                releaseBitmap(frame.bitmap);
            }
        }
    }

    private void encodeLoop() {
        while (true) {
            PendingFrame src;
            synchronized (lock) {
                src = encodeQueue.poll();
            }
            if (src == null) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running) {
                    return;
                }
                continue;
            }
            encodeAndSend(src);
        }
    }

    private void encodeAndSend(PendingFrame pending) {
        Bitmap bitmap = pending == null ? null : pending.bitmap;
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        try {
            // The previous frame has not left yet. Encoding this one would only
            // queue a picture behind it, and by the time the link got there the
            // screen would have moved on — so skip it entirely and save the encode
            // too. Nothing is recorded as sent, so whatever changed here is still
            // pending and the next frame that does go out carries it.
            if (FrameBridge.isSendBusy() && !rushFrame) {
                StreamMetrics.noteSkippedBusy();
                return;
            }
            rushFrame = false;
            final long now = SystemClock.uptimeMillis();
            final boolean sizeChanged = width != tileScratchWidth || height != tileScratchHeight;
            if (!ensureTileScratch(width, height)) {
                return;
            }
            // Decide the tile set before encoding anything, because the container
            // header carries the count.
            int changed = 0;
            for (int i = 0; i < TILE_COUNT; i++) {
                if (pending.hashes[i] != sentTileHashes[i]) {
                    changed++;
                }
            }
            // Past roughly this many tiles, the per-tile JPEG headers cost more than
            // they save and one whole-screen JPEG is smaller than the tiles that
            // would make it up. Measured: 32 tiles came to 110KB where the same
            // screen as a single JPEG was 94KB, so sending tiles for a full redraw
            // was spending more bandwidth than not tiling at all.
            // Nothing on screen moved, so the viewer's canvas already matches and
            // there is nothing to say. Only an explicit refresh request (a viewer
            // that may have just attached and has no canvas yet) justifies sending
            // an identical screen. Letting the periodic cadence do it instead was
            // this stream's single largest expense on a slow uplink: a motionless
            // screen re-sent a whole 30-49KB JPEG every 3s, which on a measured
            // 20KB/s link is most of the capacity spent conveying no information —
            // and it was in front of the frames that did carry the user's taps.
            if (changed > 0) {
                // The screen is alive again, so the fallback refresh goes back to
                // being prompt for the next viewer that attaches during this motion.
                staticKeyframeIntervalMs = KEYFRAME_INTERVAL_MS;
            }
            // A full JPEG is for viewers that have no canvas, or for a screen
            // that changed so much tiles would cost more. A 3s cadence on a
            // motionless screen is not that: it was this stream's largest
            // expense on a slow uplink, and each idle 40KB keyframe then
            // re-paced the capture interval to 500ms.
            final boolean periodicKey = changed > 0
                    && now - lastKeyframeAt >= KEYFRAME_INTERVAL_MS;
            // One small JPEG. Tile encodes and a second compress pass were the
            // 0.8–1s of delay between the phone screen and the desktop.
            final boolean full = true;
            int quality = jpegQuality;
            final long encodeStartedAt = SystemClock.uptimeMillis();
            if (full) {
                RemoteCursor.drawOn(bitmap);
                jpegOut.reset();
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut)) {
                    return;
                }
                // Whatever we hand the socket is committed: the kernel will spend as
                // long as it takes to drain it and nothing downstream can drop it. So
                // a frame bigger than the link passes in MAX_FRAME_AIR_MS is not a
                // sharper picture, it is a stall — measured at 32s for one 31KB frame
                // on a 1KB/s uplink, by which time it described a screen half a minute
                // gone. The window controller cannot prevent this on its own; it reacts
                // after the fact, and these links swing between 1KB/s and 98KB/s within
                // a minute. Re-encode smaller instead, and if even the quality floor
                // does not fit, ask for fewer pixels.
                if (false && linkBps > 0) {
                    final int budget = (int) Math.max(2048, linkBps * MAX_FRAME_AIR_MS / 1000);
                    while (jpegOut.size() > budget && quality > MIN_JPEG_QUALITY) {
                        quality = Math.max(MIN_JPEG_QUALITY, quality - JPEG_QUALITY_STEP * 3);
                        jpegOut.reset();
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut)) {
                            return;
                        }
                    }
                    // A 46KB frame on a stale 63KB/s estimate is 730ms of paper
                    // air time, but the measured write was 1ms. Dropping pixels
                    // on the estimate alone is what walked 1228x690 → 768x432
                    // after the link had already cleared.
                    if (jpegOut.size() > budget && FrameBridge.lastSendCostMs() >= 200
                            && !h264Active && !scaleChangePending
                            && scaleIndex < SCALE_STEPS.length - 1) {
                        requestScaleChange(scaleIndex + 1);
                    }
                }
            } else if (!buildTileFrame(bitmap, pending.hashes, quality)) {
                return;
            }
            final int tilesSent = full ? TILE_COUNT : changed;
            final boolean key = full;
            int length = jpegOut.size();
            StreamMetrics.noteEncoded(SystemClock.uptimeMillis() - encodeStartedAt, length);
            StreamMetrics.noteOperatingPoint(
                    intervalMs, quality, scaleIndex, linkBps, width, height);
            boolean sent = FrameBridge.sendFrame(
                    jpegOut.buffer(), width, height, length, pending.capturedAt);
            // Capture to hand-off, which is the part of the delay this process owns:
            // the compositor's frame has been waiting this long before a single byte
            // of it left for the desktop.
            StreamMetrics.noteHandedOff(sent, SystemClock.uptimeMillis() - pending.capturedAt);
            if (sent) {
                // Only a delivered tile counts as the viewer's current picture. On a
                // failed write nothing is recorded, so the next frame resends the
                // regions this one was carrying rather than leaving them stale.
                for (int i = 0; i < TILE_COUNT; i++) {
                    if (key || pending.hashes[i] != sentTileHashes[i]) {
                        sentTileHashes[i] = pending.hashes[i];
                    }
                }
                if (key) {
                    lastKeyframeAt = now;
                    forceKeyframe = false;
                    lastFullFrameBytes = length;
                }
                // The sender reports the previous frame's time on the link, since
                // this one has only just been handed over. Averaged over a window
                // that one-frame lag makes no difference, and it is now a clean
                // measurement of the link rather than of our own stall.
                noteSendCost(FrameBridge.lastSendCostMs(), length, tilesSent, key);
                framesSent++;
            } else {
                // A dropped/reconnecting socket makes every frame fail. The
                // reconnected viewer starts from a blank canvas, so the next frame
                // must be a keyframe.
                forceKeyframe = true;
            }
        } catch (Exception e) {
        } finally {
            releaseBitmap(bitmap);
        }
    }

    /** Left edge of a tile column. Both ends derive rects this way, so they agree. */
    private static int tileX(int col, int width) {
        return col * width / TILE_COLS;
    }

    private static int tileY(int row, int height) {
        return row * height / TILE_ROWS;
    }

    private boolean ensureTileScratch(int width, int height) {
        if (width == tileScratchWidth && height == tileScratchHeight
                && tileScratch[0] != null && !tileScratch[0].isRecycled()) {
            return true;
        }
        recycleTileScratch();
        try {
            for (int row = 0; row < TILE_ROWS; row++) {
                int y0 = tileY(row, height);
                int th = tileY(row + 1, height) - y0;
                for (int col = 0; col < TILE_COLS; col++) {
                    int x0 = tileX(col, width);
                    int tw = tileX(col + 1, width) - x0;
                    if (tw < 1 || th < 1) {
                        recycleTileScratch();
                        return false;
                    }
                    tileScratch[row * TILE_COLS + col] =
                            Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                }
            }
        } catch (Throwable t) {
            recycleTileScratch();
            return false;
        }
        tileScratchWidth = width;
        tileScratchHeight = height;
        // A new geometry invalidates every recorded tile, so nothing may be treated
        // as already delivered.
        java.util.Arrays.fill(sentTileHashes, 0L);
        return true;
    }

    private void recycleTileScratch() {
        for (int i = 0; i < TILE_COUNT; i++) {
            Bitmap b = tileScratch[i];
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
            tileScratch[i] = null;
        }
        tileScratchWidth = 0;
        tileScratchHeight = 0;
    }

    /**
     * Lays out one tile-update frame in jpegOut:
     *
     *   magic 'RCT1' | flags:1 | cols:1 | rows:1 | tileCount:1
     *   then per tile:  index:1 | jpegLength:4 BE | jpeg bytes
     *
     * The magic is neither FF D8 nor an Annex B start code, so the relay and the
     * desktop keep telling plain JPEG, H.264 and this format apart by content as
     * they already did, with no change to the packet header. These frames are
     * always partial updates; a whole screen goes out as a plain JPEG instead,
     * which is both smaller and something a viewer can adopt with no history.
     */
    private boolean buildTileFrame(Bitmap src, long[] hashes, int quality) {
        final int width = src.getWidth();
        final int height = src.getHeight();
        int count = 0;
        for (int i = 0; i < TILE_COUNT; i++) {
            if (hashes[i] != sentTileHashes[i]) {
                count++;
            }
        }
        if (count == 0) {
            return false;
        }
        jpegOut.reset();
        jpegOut.write('R');
        jpegOut.write('C');
        jpegOut.write('T');
        jpegOut.write('1');
        jpegOut.write(0);
        jpegOut.write(TILE_COLS);
        jpegOut.write(TILE_ROWS);
        jpegOut.write(count);
        for (int row = 0; row < TILE_ROWS; row++) {
            int y0 = tileY(row, height);
            for (int col = 0; col < TILE_COLS; col++) {
                final int index = row * TILE_COLS + col;
                if (hashes[index] == sentTileHashes[index]) {
                    continue;
                }
                Bitmap tile = tileScratch[index];
                if (tile == null || tile.isRecycled()) {
                    return false;
                }
                int x0 = tileX(col, width);
                tileCanvas.setBitmap(tile);
                tileCanvas.drawBitmap(src, -x0, -y0, null);
                tileCanvas.setBitmap(null);
                tileOut.reset();
                if (!tile.compress(Bitmap.CompressFormat.JPEG, quality, tileOut)) {
                    return false;
                }
                final int len = tileOut.size();
                jpegOut.write(index);
                jpegOut.write((len >>> 24) & 0xFF);
                jpegOut.write((len >>> 16) & 0xFF);
                jpegOut.write((len >>> 8) & 0xFF);
                jpegOut.write(len & 0xFF);
                jpegOut.write(tileOut.buffer(), 0, len);
            }
        }
        return true;
    }

    /**
     * ImageReader hands out RGBA_8888, which is the in-memory layout of
     * Bitmap.Config.ARGB_8888, so the pixels move with copyPixelsFromBuffer
     * instead of a per-pixel Java loop. Rows may carry stride padding; when they
     * do, the wider scratch bitmap is blitted once to crop it away.
     */
    /**
     * Content hash of every tile of the raw capture buffer, read straight from the
     * capture plane so change detection costs no JPEG encode and no pixel copy.
     * Returns the combined hash of the whole frame, or 0 on error — which never
     * equals a real (non-zero) hash, so an error simply forces the frame to be sent.
     */
    private int[] hashRow;

    private long hashTiles(Image image, long[] out) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) {
                return 0L;
            }
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            final int pixelStride = Math.max(1, plane.getPixelStride());
            final int rowStride = Math.max(pixelStride, plane.getRowStride());
            final int width = image.getWidth();
            final int height = image.getHeight();
            buffer.rewind();
            java.nio.IntBuffer pixels = buffer.asIntBuffer();
            final int rowPixels = rowStride / 4;
            if (rowPixels < width || (long) rowPixels * height > pixels.remaining()) {
                buffer.rewind();
                return 0L;
            }
            // Bulk row copies: a per-pixel IntBuffer.get() on a direct buffer cost
            // tens of ms per frame on the capture thread.
            if (hashRow == null || hashRow.length < width) {
                hashRow = new int[width];
            }
            final int[] rowBuf = hashRow;
            final long[] tileHash = new long[TILE_COLS];
            long combined = 1125899906842597L;
            for (int row = 0; row < TILE_ROWS; row++) {
                final int y0 = row * height / TILE_ROWS;
                final int y1 = (row + 1) * height / TILE_ROWS;
                java.util.Arrays.fill(tileHash, 1125899906842597L);
                for (int y = y0; y < y1; y++) {
                    pixels.position(y * rowPixels);
                    pixels.get(rowBuf, 0, width);
                    for (int col = 0; col < TILE_COLS; col++) {
                        final int x0 = col * width / TILE_COLS;
                        final int x1 = (col + 1) * width / TILE_COLS;
                        long h = tileHash[col];
                        for (int x = x0; x < x1; x++) {
                            h = h * 31 + rowBuf[x];
                        }
                        tileHash[col] = h;
                    }
                }
                for (int col = 0; col < TILE_COLS; col++) {
                    long h = tileHash[col];
                    if (h == 0) {
                        h = 1L;
                    }
                    out[row * TILE_COLS + col] = h;
                    combined = combined * 31 + h;
                }
            }
            buffer.rewind();
            return combined == 0 ? 1L : combined;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private Bitmap copyImageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            return null;
        }
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = Math.max(1, plane.getPixelStride());
        int rowStride = Math.max(pixelStride, plane.getRowStride());
        int width = image.getWidth();
        int height = image.getHeight();
        int bufferWidth = Math.max(width, rowStride / pixelStride);
        buffer.rewind();

        Bitmap target = obtainBitmap(width, height);
        if (target == null) {
            return null;
        }
        try {
            if (bufferWidth == width) {
                if (buffer.remaining() < target.getByteCount()) {
                    releaseBitmap(target);
                    return null;
                }
                target.copyPixelsFromBuffer(buffer);
                return target;
            }
            if (!ensurePaddedBitmap(bufferWidth, height)
                    || buffer.remaining() < paddedBitmap.getByteCount()) {
                releaseBitmap(target);
                return null;
            }
            paddedBitmap.copyPixelsFromBuffer(buffer);
            cropCanvas.setBitmap(target);
            cropCanvas.drawBitmap(paddedBitmap, 0, 0, null);
            cropCanvas.setBitmap(null);
            return target;
        } catch (Exception e) {
            releaseBitmap(target);
            return null;
        }
    }

    private boolean ensurePaddedBitmap(int width, int height) {
        if (paddedBitmap != null
                && !paddedBitmap.isRecycled()
                && paddedBitmap.getWidth() == width
                && paddedBitmap.getHeight() == height) {
            return true;
        }
        if (paddedBitmap != null) {
            paddedBitmap.recycle();
        }
        paddedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        return paddedBitmap != null;
    }

    private Bitmap obtainBitmap(int width, int height) {
        synchronized (lock) {
            while (!bitmapPool.isEmpty()) {
                Bitmap pooled = bitmapPool.poll();
                if (pooled == null) {
                    break;
                }
                if (!pooled.isRecycled()
                        && pooled.getWidth() == width
                        && pooled.getHeight() == height) {
                    return pooled;
                }
                pooled.recycle();
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    private void releaseBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        synchronized (lock) {
            if (bitmapPool.size() < MAX_POOLED_BITMAPS) {
                bitmapPool.offer(bitmap);
                return;
            }
        }
        bitmap.recycle();
    }

    private void drainEncodeQueueLocked() {
        while (true) {
            PendingFrame stale = encodeQueue.poll();
            if (stale == null) {
                return;
            }
            if (stale.bitmap != null && !stale.bitmap.isRecycled()) {
                stale.bitmap.recycle();
            }
        }
    }

    private void recycleBitmapPoolLocked() {
        while (!bitmapPool.isEmpty()) {
            Bitmap pooled = bitmapPool.poll();
            if (pooled != null && !pooled.isRecycled()) {
                pooled.recycle();
            }
        }
    }

    /** Exposes the backing array so a finished JPEG is sent without another copy. */
    /**
     * A captured frame together with the per-tile hashes measured on the capture
     * thread. The hashes have to travel with the bitmap: the encode thread decides
     * which tiles to send, and recomputing them there would mean reading every
     * pixel back out of the bitmap.
     */
    private static final class PendingFrame {
        final Bitmap bitmap;
        final long[] hashes;
        /** uptimeMillis the compositor handed this frame over, for stage timing. */
        final long capturedAt;

        PendingFrame(Bitmap bitmap, long[] hashes, long capturedAt) {
            this.bitmap = bitmap;
            this.hashes = hashes;
            this.capturedAt = capturedAt;
        }
    }

    private static final class JpegBuffer extends ByteArrayOutputStream {
        JpegBuffer(int size) {
            super(size);
        }

        byte[] buffer() {
            return buf;
        }
    }

    private int[] computeCaptureSize() {
        int width = 1080;
        int height = 1920;
        try {
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                if (metrics.widthPixels > 1 && metrics.heightPixels > 1) {
                    width = metrics.widthPixels;
                    height = metrics.heightPixels;
                }
            }
        } catch (Throwable ignored) {
        }
        nativeWidth = width;
        nativeHeight = height;
        return sizeForIndex(scaleIndex);
    }

    private int[] sizeForIndex(int index) {
        float s = SCALE_STEPS[Math.max(0, Math.min(SCALE_STEPS.length - 1, index))];
        int nw = Math.max(2, ((int) (nativeWidth * s)) & ~1);
        int nh = Math.max(2, ((int) (nativeHeight * s)) & ~1);
        return new int[]{nw, nh};
    }

    private void releaseSurfacesLocked() {
        unwatchDisplay();
        // Release the display first so no more frames are pushed into either the
        // ImageReader or the encoder input surface, then tear down the consumers.
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        teardownH264Locked();
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        projection = null;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        cropCanvas.setBitmap(null);
        tileCanvas.setBitmap(null);
        recycleTileScratch();
        if (paddedBitmap != null) {
            paddedBitmap.recycle();
            paddedBitmap = null;
        }
    }
}
