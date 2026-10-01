package com.genymobile.scrcpy.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;

import com.screencapture.app.screencapture.HiddenScreenshots;

/**
 * Injects taps, live drags, and a few keys when ADB is not available.
 */
public class AgentInputService extends AccessibilityService {

    private static volatile AgentInputService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.Executor captureExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // While armed, auto-confirm the system screen-cast (MediaProjection) dialog so
    // capture resumes after reboot without the user tapping "Start now".
    private static volatile long consentAutoAcceptUntil = 0L;
    private long lastConsentClickAt = 0L;
    // The dialog is often static (no further accessibility events after it draws),
    // so an event-only auto-accept can miss the click on a fresh install and the
    // screen never appears until the user opens the app by hand. Poll while armed.
    private static final long CONSENT_POLL_MS = 250L;
    private final Runnable consentPoll = new Runnable() {
        @Override
        public void run() {
            if (consentAutoAcceptUntil == 0L) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now > consentAutoAcceptUntil) {
                consentAutoAcceptUntil = 0L;
                return;
            }
            if (tryAcceptConsentDialog()) {
                lastConsentClickAt = now;
                return; // success cleared the arm window; stop polling
            }
            handler.postDelayed(this, CONSENT_POLL_MS);
        }
    };

    // Navigation keys (Back/Home/Recents) pressed before this service is bound
    // would otherwise be lost on devices where injection/shell are unavailable.
    // Queue them briefly and replay through the global action once we connect.
    private static final int MAX_PENDING_NAV = 8;
    private static final long NAV_REPLAY_MAX_AGE_MS = 2500L;
    private static final java.util.concurrent.ConcurrentLinkedQueue<long[]> pendingNav =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    // Invoked on the main thread whenever the service (re)connects. The app wires
    // this to re-run startup so consent can be requested from a privileged
    // foreground context after a reboot, once accessibility is actually bound.
    private static volatile Runnable connectedListener;

    // Continuous (streamed) touch gesture state. The desktop sends a live stream
    // of down/move/up. Instead of buffering the whole path and replaying a single
    // stroke on "up" (which can never long-press, so drag-and-drop failed and
    // swipes were imprecise), we chain StrokeDescription.continueStroke segments:
    // each segment is dispatched from the previous one's onCompleted, so the
    // gesture follows the finger in real time and a held pointer keeps the stroke
    // alive — exactly what Android needs to recognise a long-press drag.
    private static final long GESTURE_SEGMENT_MS = 55L;
    /**
     * Hard cap on one held gesture. The stroke chain only ends when the desktop's
     * "up" arrives, so a lost or never-sent "up" (window closed mid-drag, stream
     * dropped) used to keep the pointer pressed forever. A stuck-down pointer makes
     * the device ignore every later tap, which is invisible in the log. Release the
     * gesture after this long so input always recovers on its own.
     */
    private static final long GESTURE_MAX_HOLD_MS = 15_000L;
    /**
     * How long a dispatched segment may go without reporting completion before the
     * chain is treated as dead. dispatchGesture() reports only through its callback
     * and silently refuses a dispatch while another gesture is in flight, so a
     * refused or dropped segment left the chain open forever with nothing in the log.
     * Generous against main-thread jank (a segment itself is GESTURE_SEGMENT_MS).
     */
    private static final long GESTURE_STALL_MS = 600L;
    /**
     * Finger movement below this (px) is still a tap, not a drag. Continuous
     * willContinue strokes with ±1px jitter are unreliable as clicks on Android 14
     * (vivo/qcom): the system never fires a click, the desktop shows a frozen
     * first frame, and only later — after Recents/Home or a real swipe — frames
     * suddenly catch up. Defer the stream until we know it is a drag.
     */
    private static final float TAP_SLOP_PX = 24f;
    /** Shortest one-shot gesture that still registers as a click. */
    private static final long TAP_GESTURE_MS = 50L;
    /**
     * Cap on a press-in-place gesture. Long enough for any long-press to register,
     * bounded so a lost "up" cannot ask the system to hold for minutes.
     */
    private static final long MAX_TAP_HOLD_MS = 1500L;
    private GestureDescription.StrokeDescription activeStroke;
    private boolean gestureActive;
    private boolean gestureFinishing;
    private long gestureStartedAt;
    /** uptimeMillis of the segment currently awaiting its callback; 0 when none. */
    private long segmentDispatchedAt;
    /** Playout length of that segment, so the watchdog does not fire mid-segment. */
    private long segmentDurationMs = GESTURE_SEGMENT_MS;
    private float gestureCurX;
    private float gestureCurY;
    private float gestureTargetX;
    private float gestureTargetY;
    private boolean gestureJitter;
    // A press that arrived while the previous chain was still open. Only one gesture
    // may be in flight, so it waits here instead of being dropped.
    private boolean pendingDown;
    private boolean pendingUp;
    private float pendingDownX;
    private float pendingDownY;
    /** Down received; waiting to decide tap vs drag (see TAP_HOLD_MS / TAP_SLOP_PX). */
    private boolean pressPending;
    private float pressX;
    private float pressY;
    private long pressAt;
    private final Runnable gestureWatchdog = this::onGestureWatchdog;

    // Every gesture coordinate must sit inside the display. StrokeDescription
    // throws IllegalArgumentException("Path bounds must not be negative") on a
    // negative point, and the system silently rejects a stroke that runs past the
    // display edge. Unclamped remote coordinates therefore crashed the whole app
    // on a wheel-scroll near the top edge, and made vertical drags (which reach
    // y<0 far sooner than horizontal ones reach x<0 on a wide screen) die in
    // continueStroke, which looked like "vertical swipe does not work".
    private int displayW;
    private int displayH;
    private boolean displayProbed;

    private void ensureDisplaySize() {
        if (displayW > 0 && displayH > 0) {
            return;
        }
        if (displayProbed) {
            return;
        }
        displayProbed = true;
        refreshDisplaySize();
    }

    private void refreshDisplaySize() {
        // Must match AgentInput.screenSize(), which maps the desktop's normalized
        // coordinates into pixels. A different authority here could clamp away a
        // perfectly valid point and silently break drags.
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
                    displayW = b.width();
                    displayH = b.height();
                } else {
                    android.graphics.Point p = new android.graphics.Point();
                    wm.getDefaultDisplay().getRealSize(p);
                    displayW = p.x;
                    displayH = p.y;
                }
            }
        } catch (Throwable t) {
        }
        if (displayW <= 0 || displayH <= 0) {
            try {
                android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                displayW = m.widthPixels;
                displayH = m.heightPixels;
            } catch (Throwable ignored) {
            }
        }
    }

    private float clampX(float x) {
        ensureDisplaySize();
        float max = displayW > 0 ? displayW - 1f : Float.MAX_VALUE;
        return x < 0f ? 0f : (x > max ? max : x);
    }

    private float clampY(float y) {
        ensureDisplaySize();
        float max = displayH > 0 ? displayH - 1f : Float.MAX_VALUE;
        return y < 0f ? 0f : (y > max ? max : y);
    }

    public static AgentInputService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        refreshDisplaySize();
        SilentScreenStreamer.resume();
        onConnectedCommon();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) {
            instance = null;
        }
        // Any in-flight stroke dies with the binding; clear the state so a rebind
        // does not try to continue a stroke the system has already dropped.
        handler.removeCallbacks(gestureWatchdog);
        clearGestureState();
        pendingDown = false;
        pendingUp = false;
        pressPending = false;
        AgentInput.onAccessibilityLost();
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        instance = this;
        SilentScreenStreamer.resume();
        onConnectedCommon();
    }

    /**
     * Shared bind/rebind handling: replay any nav keys that arrived before the
     * service was ready, then notify the app so it can request screen-cast
     * consent from this (now privileged) foreground context.
     */
    private void onConnectedCommon() {
        handler.post(this::drainPendingNav);
        Runnable listener = connectedListener;
        if (listener != null) {
            handler.post(listener);
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scrollTargetAt = 0L;
        }
        if (consentAutoAcceptUntil == 0L) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now > consentAutoAcceptUntil) {
            consentAutoAcceptUntil = 0L;
            return;
        }
        if (now - lastConsentClickAt < 200L) {
            return;
        }
        if (tryAcceptConsentDialog()) {
            lastConsentClickAt = now;
        }
    }

    @Override
    public void onInterrupt() {
        // not used
    }

    /**
     * Arm auto-confirmation of the system screen-cast dialog for a short window.
     * Call right before launching {@code createScreenCaptureIntent()} so the
     * accessibility service taps "Start now" without the user, letting capture
     * resume in the background after a reboot / process restart.
     */
    public static void armConsentAutoAccept() {
        consentAutoAcceptUntil = SystemClock.uptimeMillis() + 20000L;
        AgentInputService self = instance;
        if (self != null) {
            self.handler.removeCallbacks(self.consentPoll);
            self.handler.post(self.consentPoll);
        }
    }

    public static void disarmConsentAutoAccept() {
        consentAutoAcceptUntil = 0L;
        AgentInputService self = instance;
        if (self != null) {
            self.handler.removeCallbacks(self.consentPoll);
        }
    }

    /** Wire a callback invoked (on the main thread) whenever a11y (re)binds. */
    public static void setConnectedListener(Runnable r) {
        connectedListener = r;
    }

    /**
     * Remember a navigation key that could not be delivered because this service
     * was not bound yet. It is replayed via the global action on connect.
     */
    public static void enqueueNav(int keyCode) {
        pendingNav.add(new long[]{keyCode, SystemClock.uptimeMillis()});
        while (pendingNav.size() > MAX_PENDING_NAV) {
            pendingNav.poll();
        }
    }

    private void drainPendingNav() {
        long now = SystemClock.uptimeMillis();
        long[] item;
        while ((item = pendingNav.poll()) != null) {
            if (now - item[1] > NAV_REPLAY_MAX_AGE_MS) {
                continue;
            }
            globalKey((int) item[0]);
        }
    }

    /**
     * Start an activity using the accessibility service's own context. An enabled
     * accessibility service is a privileged foreground context, so this launches
     * the screen-cast consent screen from the background after a reboot — where a
     * plain Service's startActivity is silently blocked on Android 12+. Returns
     * false if the service is not connected yet, so the caller can fall back.
     */
    public static boolean launchActivity(Intent intent) {
        AgentInputService self = instance;
        if (self == null || intent == null) {
            return false;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            self.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean tryAcceptConsentDialog() {
        boolean clicked = false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                clicked = clickConsentButton(root);
                root.recycle();
            }
        } catch (Throwable t) {
        }
        if (!clicked && Build.VERSION.SDK_INT >= 21) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo w : windows) {
                        if (w == null) {
                            continue;
                        }
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r == null) {
                            continue;
                        }
                        boolean ok = clickConsentButton(r);
                        r.recycle();
                        if (ok) {
                            clicked = true;
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
            }
        }
        if (clicked) {
            consentAutoAcceptUntil = 0L;
        }
        return clicked;
    }

    private boolean clickConsentButton(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        if (clickById(root, "android:id/button1")) {
            return true;
        }
        if (clickById(root, "com.android.systemui:id/button1")) {
            return true;
        }
        return clickByPositiveText(root);
    }

    private boolean clickById(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> nodes = null;
        try {
            nodes = root.findAccessibilityNodeInfosByViewId(id);
        } catch (Throwable ignored) {
        }
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        boolean ok = false;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) {
                continue;
            }
            if (!ok) {
                ok = performClickCompat(n);
            }
            n.recycle();
        }
        return ok;
    }

    private boolean clickByPositiveText(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (isPositiveText(node.getText()) || isPositiveText(node.getContentDescription())) {
            if (performClickCompat(node)) {
                return true;
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; ++i) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            boolean ok = clickByPositiveText(child);
            child.recycle();
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private boolean performClickCompat(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = node;
        for (int depth = 0; cursor != null && depth < 6; ++depth) {
            if (cursor.isClickable()) {
                boolean ok = cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (cursor != node) {
                    cursor.recycle();
                }
                return ok;
            }
            AccessibilityNodeInfo parent = cursor.getParent();
            if (cursor != node) {
                cursor.recycle();
            }
            cursor = parent;
        }
        if (cursor != null && cursor != node) {
            cursor.recycle();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static boolean isPositiveText(CharSequence cs) {
        if (cs == null) {
            return false;
        }
        String s = cs.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return false;
        }
        return s.equals("start now") || s.equals("start")
                || s.equals("지금 시작") || s.equals("시작") || s.equals("허용")
                || s.equals("확인") || s.equals("开始") || s.equals("立即开始")
                || s.equals("開始") || s.contains("start now");
    }

    public interface BitmapCallback {
        void accept(Bitmap bitmap);
    }

    /**
     * Capture the default display without MediaProjection. Result may be null.
     * takeScreenshot is invoked on this thread (RemoteApp) and the HardwareBuffer
     * is copied with a direct executor so the buffer is not closed first.
     */
    public void captureDisplay(BitmapCallback callback) {
        if (callback == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            captureExecutor.execute(() -> callback.accept(HiddenScreenshots.capture(this)));
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, command -> command.run(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            Bitmap bitmap = bitmapFromResult(result);
                            if (bitmap == null) {
                                bitmap = HiddenScreenshots.capture(AgentInputService.this);
                            }
                            callback.accept(bitmap);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                                SilentScreenStreamer.noteRateLimited();
                                callback.accept(null);
                                return;
                            }
                            callback.accept(HiddenScreenshots.capture(AgentInputService.this));
                        }
                    });
        } catch (Throwable e) {
            captureExecutor.execute(() ->
                    callback.accept(HiddenScreenshots.capture(AgentInputService.this)));
        }
    }

    private static Bitmap bitmapFromResult(ScreenshotResult result) {
        if (result == null) {
            return null;
        }
        HardwareBuffer buffer = result.getHardwareBuffer();
        if (buffer == null) {
            return null;
        }
        Bitmap copy = null;
        try {
            ColorSpace colorSpace = result.getColorSpace();
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hardware == null && colorSpace != null) {
                hardware = Bitmap.wrapHardwareBuffer(buffer,
                        ColorSpace.get(ColorSpace.Named.SRGB));
            }
            if (hardware != null) {
                copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                if (copy == null) {
                    copy = Bitmap.createBitmap(hardware.getWidth(), hardware.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    new Canvas(copy).drawBitmap(hardware, 0, 0, null);
                }
                if (copy != hardware) {
                    hardware.recycle();
                }
            }
        } catch (Exception e) {
        } finally {
            buffer.close();
        }
        return copy;
    }

    public void touch(String action, float x, float y) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        if (Looper.myLooper() == handler.getLooper()) {
            // Remote input is already delivered on this looper, and deliberately put at
            // the front of its queue. Posting again would move it to the back, behind
            // everything else pending, which is the opposite of what the caller wants.
            handleTouch(action, x, y);
            return;
        }
        handler.post(() -> handleTouch(action, x, y));
    }

    /** Finger travel per wheel notch. */
    private static final float SCROLL_PX_PER_TICK = 200f;
    /** Move phase of one scroll stroke. */
    private static final long SCROLL_MOVE_MS = 40L;
    /**
     * Still phase after the move. Releasing while the finger is moving makes the
     * list fling past the requested distance; holding it makes the release
     * velocity zero, so the page stops exactly where the wheel asked.
     */
    private static final long SCROLL_HOLD_MS = 30L;
    private static final long SCROLL_STALL_MS = 400L;
    private static final long SCROLL_TARGET_TTL_MS = 1500L;

    private float scrollTicks;
    private float scrollAtX;
    private float scrollAtY;
    private boolean scrollBusy;
    private long scrollStartedAt;
    private AccessibilityNodeInfo scrollTarget;
    private final android.graphics.Rect scrollTargetBounds = new android.graphics.Rect();
    private long scrollTargetAt;
    private final Runnable scrollWatchdog = () -> {
        if (scrollBusy && SystemClock.uptimeMillis() - scrollStartedAt >= SCROLL_STALL_MS) {
            scrollBusy = false;
            pumpScroll();
        }
    };

    /** Positive {@code vertical} is wheel up: the page moves toward its top. */
    public void scroll(float x, float y, float vertical) {
        if (vertical == 0f || Build.VERSION.SDK_INT < 26) {
            return;
        }
        Runnable r = () -> {
            scrollAtX = x;
            scrollAtY = y;
            scrollTicks += vertical;
            pumpScroll();
        };
        if (Looper.myLooper() == handler.getLooper()) {
            r.run();
        } else {
            handler.post(r);
        }
    }

    /**
     * Notches that arrive while a stroke is playing are summed and sent as one
     * stroke when it ends. Dispatching each notch at once cancelled the stroke
     * still in flight, so most of the wheel was lost and the page stuttered.
     */
    private void pumpScroll() {
        if (scrollBusy || scrollTicks == 0f || gestureActive || pressPending) {
            return;
        }
        ensureDisplaySize();
        if (displayW < 3 || displayH < 3) {
            scrollTicks = 0f;
            return;
        }
        final float maxY = displayH - 2f;
        final float maxDist = Math.max(40f, displayH * 0.6f);
        final boolean towardTop = scrollTicks > 0f;
        float dist = Math.abs(scrollTicks) * SCROLL_PX_PER_TICK;
        float usedTicks = scrollTicks;
        if (dist > maxDist) {
            usedTicks = scrollTicks * (maxDist / dist);
            dist = maxDist;
        }
        final float sx = Math.max(1f, Math.min(displayW - 2f, scrollAtX));
        final float py = Math.max(1f, Math.min(maxY, scrollAtY));
        if (!canScrollContent((int) sx, (int) py, towardTop)) {
            scrollTicks = 0f;
            return;
        }
        // Keep the stroke on the display (negative points throw) by sliding its
        // start rather than shortening it, so every notch scrolls the same amount.
        float sy = py;
        float ey;
        if (towardTop) {
            if (maxY - sy < dist) {
                sy = Math.max(1f, maxY - dist);
            }
            ey = Math.min(maxY, sy + dist);
        } else {
            if (sy - 1f < dist) {
                sy = Math.min(maxY, 1f + dist);
            }
            ey = Math.max(1f, sy - dist);
        }
        if (Math.abs(ey - sy) < 2f) {
            scrollTicks = 0f;
            return;
        }
        final GestureDescription.StrokeDescription move;
        try {
            Path path = new Path();
            path.moveTo(sx, sy);
            path.lineTo(sx, ey);
            move = new GestureDescription.StrokeDescription(path, 0, SCROLL_MOVE_MS, true);
        } catch (Throwable t) {
            scrollTicks = 0f;
            return;
        }
        final float endY = ey;
        scrollBusy = true;
        scrollStartedAt = SystemClock.uptimeMillis();
        boolean ok = dispatchScroll(move, () -> {
            try {
                Path hold = new Path();
                hold.moveTo(sx, endY);
                hold.lineTo(sx + (sx < displayW - 3f ? 1f : -1f), endY);
                if (dispatchScroll(move.continueStroke(hold, 0, SCROLL_HOLD_MS, false), null)) {
                    return;
                }
            } catch (Throwable t) {
            }
            finishScroll();
        });
        if (!ok) {
            scrollBusy = false;
            handler.postDelayed(this::pumpScroll, 16L);
            return;
        }
        scrollTicks -= usedTicks;
        if (Math.abs(scrollTicks) < 0.01f) {
            scrollTicks = 0f;
        }
        handler.removeCallbacks(scrollWatchdog);
        handler.postDelayed(scrollWatchdog, SCROLL_STALL_MS);
    }

    private void finishScroll() {
        scrollBusy = false;
        handler.removeCallbacks(scrollWatchdog);
        pumpScroll();
    }

    private boolean dispatchScroll(GestureDescription.StrokeDescription stroke, Runnable next) {
        try {
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    if (next != null) {
                        next.run();
                    } else {
                        finishScroll();
                    }
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    finishScroll();
                }
            }, handler);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * False only when the list under the point reports it is already at the edge in
     * that direction, so the wheel stops there instead of rubber-banding. The target
     * is looked up once and reused: walking the tree on every notch is binder IPC
     * that held up this thread for tens of ms.
     */
    private boolean canScrollContent(int x, int y, boolean towardTop) {
        final long now = SystemClock.uptimeMillis();
        AccessibilityNodeInfo node = scrollTarget;
        boolean reuse = node != null && now - scrollTargetAt < SCROLL_TARGET_TTL_MS
                && scrollTargetBounds.contains(x, y);
        if (reuse) {
            try {
                reuse = node.refresh();
            } catch (Throwable t) {
                reuse = false;
            }
        }
        if (!reuse) {
            scrollTarget = null;
            node = null;
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                node = findScrollable(root, x, y, 0);
            } catch (Throwable t) {
                node = null;
            }
            if (node == null) {
                return true;
            }
            node.getBoundsInScreen(scrollTargetBounds);
            scrollTarget = node;
            scrollTargetAt = now;
        }
        return directionOpen(node, towardTop);
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int x, int y,
            int depth) {
        if (node == null || depth > 48) {
            return null;
        }
        android.graphics.Rect r = new android.graphics.Rect();
        node.getBoundsInScreen(r);
        if (!r.contains(x, y)) {
            return null;
        }
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(i), x, y, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return node.isScrollable() ? node : null;
    }

    private static boolean directionOpen(AccessibilityNodeInfo node, boolean towardTop) {
        boolean saw = false;
        boolean open = false;
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null) {
            return true;
        }
        for (AccessibilityNodeInfo.AccessibilityAction a : actions) {
            final int id = a.getId();
            final boolean up = id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    || (Build.VERSION.SDK_INT >= 23
                    && id == android.R.id.accessibilityActionScrollUp);
            final boolean down = id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    || (Build.VERSION.SDK_INT >= 23
                    && id == android.R.id.accessibilityActionScrollDown);
            if (up || down) {
                saw = true;
            }
            if (towardTop && up) {
                open = true;
            }
            if (!towardTop && down) {
                open = true;
            }
        }
        return !saw || open;
    }

    public boolean handleKey(int keyCode) {
        if (globalKey(keyCode)) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            return deleteText();
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return imeEnter();
        }
        return false;
    }

    boolean globalKey(int keyCode) {
        int action;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            action = GLOBAL_ACTION_BACK;
        } else if (keyCode == KeyEvent.KEYCODE_HOME) {
            action = GLOBAL_ACTION_HOME;
        } else if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            action = GLOBAL_ACTION_RECENTS;
        } else {
            return false;
        }
        boolean ok = false;
        try {
            ok = performGlobalAction(action);
        } catch (Throwable t) {
        }
        return ok;
    }

    private boolean imeEnter() {
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) {
            return false;
        }
        boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!ok && Build.VERSION.SDK_INT >= 30) {
            ok = focus.performAction(android.R.id.accessibilityActionImeEnter);
        }
        focus.recycle();
        return ok;
    }

    public boolean typeText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || !focus.isEditable()) {
            if (focus != null) {
                focus.recycle();
            }
            return false;
        }
        CharSequence existing = focus.getText();
        String next = (existing == null ? "" : existing.toString()) + text;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
        boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        focus.recycle();
        return ok;
    }

    boolean deleteText() {
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || !focus.isEditable()) {
            if (focus != null) {
                focus.recycle();
            }
            return false;
        }
        CharSequence existing = focus.getText();
        if (existing == null || existing.length() == 0) {
            focus.recycle();
            return false;
        }
        String next = existing.subSequence(0, existing.length() - 1).toString();
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
        boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        focus.recycle();
        return ok;
    }

    private void handleTouch(String action, float x, float y) {
        if ("down".equals(action)) {
            if (gestureActive) {
                // Closing the open stroke and opening a new gesture in the same instant
                // makes the second dispatchGesture refuse (only one gesture may be in
                // flight), which silently swallowed clicks while the user kept pressing.
                // Ask the open chain to finish and start this press once it has closed.
                pendingDown = true;
                pendingUp = false;
                pendingDownX = x;
                pendingDownY = y;
                gestureFinishing = true;
                return;
            }
            // Do not open a continued stroke, and do not start one just because time
            // passed either. A willContinue chain is unreliable as a click on several
            // ROMs, and promoting on a timer sent almost every click down it: a press
            // held longer than TAP_HOLD_MS became a stroke, and a human clicking over
            // a laggy link holds 200-600ms. Measured on an Android 12 box, 49 of 50
            // taps were promoted and silently did nothing, while the few that stayed
            // under the timer worked. Only actual movement proves a drag, so wait for
            // a move beyond the slop, or for the up that makes this a tap.
            pressPending = true;
            pressX = x;
            pressY = y;
            pressAt = SystemClock.uptimeMillis();
            pendingDown = false;
            pendingUp = false;
            return;
        }
        if ("move".equals(action)) {
            if (pressPending) {
                if (Math.abs(x - pressX) >= TAP_SLOP_PX
                        || Math.abs(y - pressY) >= TAP_SLOP_PX) {
                    promotePressToStream();
                    if (gestureActive) {
                        gestureTargetX = x;
                        gestureTargetY = y;
                    }
                }
                return;
            }
            if (pendingDown) {
                pendingDownX = x;
                pendingDownY = y;
            } else if (gestureActive) {
                gestureTargetX = x;
                gestureTargetY = y;
            }
            return;
        }
        if (!"up".equals(action)) {
            return;
        }
        if (pressPending) {
            // Never moved, so this was a press in place however long it was held: one
            // completed one-shot gesture of that duration. This is the reliable path on
            // every version tested, and giving it the real hold time is what makes a
            // long-press register as a long-press instead of needing a stroke chain.
            pressPending = false;
            final long held = SystemClock.uptimeMillis() - pressAt;
            tap(x, y, held);
            return;
        }
        if (pendingDown) {
            // Released before its gesture could even open, so it is a tap: let the
            // queued gesture close itself as soon as it starts.
            pendingUp = true;
            return;
        }
        if (!gestureActive) {
            // A stray "up" with no active stroke (e.g. after a cancel): treat as a
            // simple tap so the click is not lost.
            tap(x, y);
            return;
        }
        gestureTargetX = x;
        gestureTargetY = y;
        gestureFinishing = true;
    }

    /**
     * The deferred press became a long-press or drag: open the continued stroke
     * from the original down point so long-press / drag-and-drop still work.
     */
    private void promotePressToStream() {
        if (!pressPending || gestureActive) {
            return;
        }
        pressPending = false;
        // Carry the time already spent pressed into the opening segment. Drag-and-drop
        // needs the system to see a long-press *before* the movement; opening the
        // stroke as if the press had just happened turns the same input into a fling.
        beginGesture(pressX, pressY, SystemClock.uptimeMillis() - pressAt);
    }

    /** Open the press that had to wait for the previous chain to close. */
    private void startPendingGesture() {
        if (!pendingDown || gestureActive) {
            return;
        }
        pendingDown = false;
        final boolean releasedAlready = pendingUp;
        pendingUp = false;
        if (releasedAlready) {
            // It never got a chance to move: one-shot tap, not a continued stroke.
            tap(pendingDownX, pendingDownY);
            return;
        }
        beginGesture(pendingDownX, pendingDownY);
    }

    /**
     * Recover a chain that stopped reporting. dispatchGesture() only ever reports
     * through its callback, so a refused or dropped segment used to leave the pointer
     * down with gestureActive stuck true — and because the hold cap was checked inside
     * that same callback, it could never fire either. Every later tap was then ignored
     * by the system with nothing in the log. Drive recovery from a timer instead.
     */
    private void armGestureWatchdog() {
        handler.removeCallbacks(gestureWatchdog);
        // The segment cannot report before it has played out, so the allowance has to
        // include its own length — the opening segment of a drag carries the hold that
        // preceded it and can be far longer than a keep-alive one.
        handler.postDelayed(gestureWatchdog, segmentDurationMs + GESTURE_STALL_MS);
    }

    private void onGestureWatchdog() {
        if (!gestureActive) {
            startPendingGesture();
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (segmentDispatchedAt != 0L
                && now - segmentDispatchedAt >= segmentDurationMs + GESTURE_STALL_MS) {
            resetGesture();
            startPendingGesture();
            return;
        }
        if (gestureStartedAt != 0L && now - gestureStartedAt > GESTURE_MAX_HOLD_MS) {
            gestureFinishing = true;
        }
        armGestureWatchdog();
    }

    private void beginGesture(float x, float y) {
        beginGesture(x, y, 0L);
    }

    private void beginGesture(float x, float y, long initialHoldMs) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        // If a previous gesture is somehow still open, drop it cleanly first.
        resetGesture();
        float sx = clampX(x);
        float sy = clampY(y);
        gestureCurX = sx;
        gestureCurY = sy;
        gestureTargetX = sx;
        gestureTargetY = sy;
        gestureFinishing = false;
        gestureJitter = false;
        Path path = new Path();
        buildSegment(path, sx, sy, sx, sy);
        final long openMs = Math.max(GESTURE_SEGMENT_MS,
                Math.min(MAX_TAP_HOLD_MS, initialHoldMs));
        try {
            activeStroke = new GestureDescription.StrokeDescription(path, 0, openMs, true);
        } catch (Throwable t) {
            resetGesture();
            return;
        }
        gestureActive = true;
        gestureStartedAt = SystemClock.uptimeMillis();
        segmentDurationMs = openMs;
        if (!dispatchStreamed(activeStroke)) {
            // Refused, so nothing is open on the system side: drop the bookkeeping
            // without injecting a closing segment and let the next press try again.
            clearGestureState();
            return;
        }
        armGestureWatchdog();
    }

    /**
     * Called on the handler thread when the current segment finishes. Advances the
     * stroke toward the latest requested point, keeping it alive (long-press hold
     * for drag-and-drop) when the pointer is stationary, and ends it on "up".
     */
    private void onSegmentCompleted() {
        segmentDispatchedAt = 0L;
        if (!gestureActive || activeStroke == null) {
            // The chain has closed (this was its final segment), so a press that
            // arrived mid-gesture can now open safely.
            handler.removeCallbacks(gestureWatchdog);
            startPendingGesture();
            pumpScroll();
            return;
        }
        if (!gestureFinishing && gestureStartedAt != 0L
                && SystemClock.uptimeMillis() - gestureStartedAt > GESTURE_MAX_HOLD_MS) {
            // No "up" ever arrived. Close the gesture so the pointer is released and
            // later taps are not swallowed by a pointer the system still thinks is down.
            gestureFinishing = true;
        }
        boolean willContinue = !gestureFinishing;
        Path path = new Path();
        buildSegment(path, gestureCurX, gestureCurY, gestureTargetX, gestureTargetY);
        GestureDescription.StrokeDescription next;
        try {
            next = activeStroke.continueStroke(path, 0, GESTURE_SEGMENT_MS, willContinue);
        } catch (Throwable t) {
            resetGesture();
            return;
        }
        activeStroke = next;
        segmentDurationMs = GESTURE_SEGMENT_MS;
        if (gestureFinishing) {
            // This final segment ends the gesture (willContinue=false).
            gestureActive = false;
            gestureStartedAt = 0L;
            final boolean sent = dispatchStreamed(next);
            activeStroke = null;
            if (!sent) {
                handler.removeCallbacks(gestureWatchdog);
                startPendingGesture();
                return;
            }
            armGestureWatchdog();
            return;
        }
        if (!dispatchStreamed(next)) {
            clearGestureState();
            handler.removeCallbacks(gestureWatchdog);
            startPendingGesture();
            return;
        }
        armGestureWatchdog();
    }

    /**
     * Appends one gesture segment from (sx,sy) toward (tx,ty) and updates
     * {@link #gestureCurX}/{@link #gestureCurY} to the point the stroke actually
     * ends at. A continued stroke must start exactly where the previous one ended,
     * and a stroke must have non-zero length, so a stationary keep-alive nudges
     * the endpoint by ±1px alternately — enough to stay valid without letting a
     * long press drift away from its target.
     */
    private void buildSegment(Path path, float sx, float sy, float tx, float ty) {
        sx = clampX(sx);
        sy = clampY(sy);
        tx = clampX(tx);
        ty = clampY(ty);
        path.moveTo(sx, sy);
        float ex;
        float ey;
        if (Math.abs(tx - sx) < 0.5f && Math.abs(ty - sy) < 0.5f) {
            // Nudge into whichever side has room: a blind -1 at x==0 produced a
            // negative point, which continueStroke rejects and the drag was lost.
            float nudged = tx + (gestureJitter ? 1f : -1f);
            if (nudged < 0f || (displayW > 0 && nudged > displayW - 1f)) {
                nudged = tx - (gestureJitter ? 1f : -1f);
            }
            ex = clampX(nudged);
            ey = ty;
            gestureJitter = !gestureJitter;
        } else {
            ex = tx;
            ey = ty;
        }
        path.lineTo(ex, ey);
        gestureCurX = ex;
        gestureCurY = ey;
    }

    /**
     * End the current gesture cleanly.
     *
     * A stroke dispatched with willContinue=true keeps the injected pointer down
     * until a segment closes it. Simply forgetting the stroke left the system with a
     * pointer stuck down, and from then on every remote tap and swipe was silently
     * ignored — the reported "mouse stops working" with nothing in the log. So if a
     * continuing stroke is still open, close it with a final segment first.
     */
    private void resetGesture() {
        GestureDescription.StrokeDescription open = activeStroke;
        boolean wasActive = gestureActive;
        handler.removeCallbacks(gestureWatchdog);
        pressPending = false;
        clearGestureState();
        if (!wasActive || open == null || Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            Path path = new Path();
            buildSegment(path, gestureCurX, gestureCurY, gestureCurX, gestureCurY);
            dispatchStreamed(open.continueStroke(path, 0, GESTURE_SEGMENT_MS, false));
        } catch (Throwable t) {
        }
    }

    /** Drop the gesture bookkeeping without touching the system (safe on unbind). */
    private void clearGestureState() {
        gestureActive = false;
        gestureFinishing = false;
        activeStroke = null;
        gestureStartedAt = 0L;
        segmentDispatchedAt = 0L;
    }

    /**
     * @return true when the system accepted the segment, meaning a callback will
     *         follow. A false return is not an error the caller may ignore: no
     *         callback ever comes, so the chain must be abandoned by the caller.
     */
    private boolean dispatchStreamed(GestureDescription.StrokeDescription stroke) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        AccessibilityService.GestureResultCallback callback =
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        onSegmentCompleted();
                    }

                    @Override
                    public void onCancelled(GestureDescription g) {
                        // The system tore the gesture (e.g. a competing injector or
                        // main-thread jank). It is already cleaned up on their side,
                        // so only drop our bookkeeping and serve any queued press.
                        segmentDispatchedAt = 0L;
                        clearGestureState();
                        handler.removeCallbacks(gestureWatchdog);
                        startPendingGesture();
                    }
                };
        try {
            if (!dispatchGesture(gesture, callback, handler)) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        segmentDispatchedAt = SystemClock.uptimeMillis();
        return true;
    }

    private void tap(float x, float y) {
        tap(x, y, TAP_GESTURE_MS);
    }

    /**
     * One completed gesture holding at a point for {@code holdMs}. A short hold is a
     * click and a long one is a long-press, so this single call covers both without a
     * continued stroke — which is what the system fails to recognise as a press on
     * some ROMs.
     */
    private void tap(float x, float y, long holdMs) {
        Path path = new Path();
        line(path, clampX(x), clampY(y), clampX(x), clampY(y));
        final long dur = Math.max(TAP_GESTURE_MS, Math.min(MAX_TAP_HOLD_MS, holdMs));
        dispatch(new GestureDescription.StrokeDescription(path, 0, dur));
    }

    private void swipe(float x1, float y1, float x2, float y2) {
        Path path = new Path();
        path.moveTo(clampX(x1), clampY(y1));
        path.lineTo(clampX(x2), clampY(y2));
        dispatch(new GestureDescription.StrokeDescription(path, 0, 180));
    }

    private boolean dispatch(GestureDescription.StrokeDescription next) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        try {
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(next)
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            // Remote input must never be able to take the process down.
            return false;
        }
    }

    private static void line(Path path, float x1, float y1, float x2, float y2) {
        path.moveTo(x1, y1);
        if (Math.abs(x2 - x1) < 0.5f && Math.abs(y2 - y1) < 0.5f) {
            path.lineTo(x1 + 1f, y1);
        } else {
            path.lineTo(x2, y2);
        }
    }

    /** Keep the whole gesture pipeline inside the display after a rotation. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshDisplaySize();
    }
}
