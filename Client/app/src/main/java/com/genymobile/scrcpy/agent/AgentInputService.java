package com.genymobile.scrcpy.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
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
    /** Wait before offering a refused tap to the system again. */
    private static final long TAP_RETRY_GAP_MS = 40L;
    /** Gives a second tap time to land after the click before it is still playing. */
    private static final int TAP_RETRY_MAX = 16;
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
        // coordinates into pixels. The maximum window size stays portrait after a
        // rotation, so use the display's real size in the current orientation.
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            android.view.Display display = dm != null
                    ? dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) : null;
            if (display != null) {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                display.getRealMetrics(metrics);
                if (metrics.widthPixels > 1 && metrics.heightPixels > 1) {
                    displayW = metrics.widthPixels;
                    displayH = metrics.heightPixels;
                    displayProbed = true;
                    return;
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
        displayProbed = true;
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
        enableGestureCapability();
        refreshDisplaySize();
        SilentScreenStreamer.resume();
        onConnectedCommon();
    }

    /**
     * The XML already asks for gestures, but a service that was enabled before that
     * flag existed keeps the old capability set until it is toggled off and on.
     * Re-apply it on every bind so a click is allowed to become a gesture without
     * making the user reopen the accessibility settings.
     */
    private void enableGestureCapability() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            return;
        }
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                java.lang.reflect.Field field =
                        AccessibilityServiceInfo.class.getDeclaredField("mCapabilities");
                field.setAccessible(true);
                int caps = field.getInt(info);
                // CAPABILITY_CAN_PERFORM_GESTURES | CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
                field.setInt(info, caps | 0x00000020 | 0x00000001);
            } catch (Throwable ignored) {
            }
        }
        try {
            setServiceInfo(info);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) {
            instance = null;
        }
        // Any in-flight stroke dies with the binding; clear the state so a rebind
        // does not try to continue a stroke the system has already dropped.
        handler.removeCallbacks(gestureWatchdog);
        RemoteCursor.hide(this);
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
        RemoteCursor.hide(this);
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

    /**
     * Where the remote pointer is, and whether it is on screen. Written by the
     * input thread, read by the one runnable that applies it on the main thread.
     *
     * Moving the overlay means a WindowManager layout pass, which only the main
     * thread can do and which is slower than the rate a mouse produces positions
     * at. One pending runnable that always reads the newest position is therefore
     * all that is ever queued: an older position is not worth drawing, because by
     * the time it would appear the pointer has already moved on.
     */
    private final Object cursorLock = new Object();
    private boolean cursorVisible;
    private float cursorX;
    private float cursorY;
    private boolean cursorPending;

    private final Runnable applyCursor = new Runnable() {
        @Override
        public void run() {
            boolean visible;
            float x;
            float y;
            synchronized (cursorLock) {
                cursorPending = false;
                visible = cursorVisible;
                x = cursorX;
                y = cursorY;
            }
            if (visible) {
                RemoteCursor.move(AgentInputService.this, x, y);
            } else {
                RemoteCursor.hide(AgentInputService.this);
            }
        }
    };

    public void showCursor(float x, float y) {
        setCursor(true, x, y);
    }

    public void hideCursor() {
        float x;
        float y;
        synchronized (cursorLock) {
            x = cursorX;
            y = cursorY;
        }
        setCursor(false, x, y);
    }

    private void setCursor(boolean visible, float x, float y) {
        boolean post;
        synchronized (cursorLock) {
            cursorVisible = visible;
            cursorX = x;
            cursorY = y;
            post = !cursorPending;
            cursorPending = true;
        }
        if (Looper.myLooper() == handler.getLooper()) {
            applyCursor.run();
            return;
        }
        // Already queued: it will pick up what was just written, so adding another
        // would only draw the same thing twice.
        if (post) {
            handler.post(applyCursor);
        }
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
            AccessibilityNodeInfo stale = scrollTarget;
            scrollTarget = null;
            recycleQuiet(stale);
            node = null;
            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
                node = findScrollable(root, x, y, 0);
            } catch (Throwable t) {
                node = null;
            }
            if (root != null && root != node) {
                recycleQuiet(root);
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
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo found = findScrollable(child, x, y, depth + 1);
            if (found != null) {
                if (found != child) {
                    recycleQuiet(child);
                }
                return found;
            }
            recycleQuiet(child);
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

    // One arrow press is one selection step, and it must not touch the main
    // looper except to hand the system a gesture. Letters wait on that looper
    // (typeText). Walking the node tree or retrying a swipe there filled it, the
    // wait timed out, and the letter was reported as typed so it was never
    // injected either — the whole keyboard went dead after the first arrows.
    private static final int ARROW_QUEUE_MAX = 12;
    private static final long ARROW_MOVE_MS = 40L;
    private static final long ARROW_HOLD_MS = 30L;
    private static final long ARROW_STALL_MS = 500L;
    private final java.util.ArrayDeque<Integer> arrowQueue = new java.util.ArrayDeque<>();
    private final java.util.concurrent.ExecutorService arrowExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "arrow-step");
                t.setDaemon(true);
                return t;
            });
    private boolean arrowBusy;
    private int arrowGen;

    /** One arrow key from the desktop. Every press is kept, in order. */
    public void arrowKey(int keyCode) {
        arrowExec.execute(() -> {
            if (arrowQueue.size() >= ARROW_QUEUE_MAX) {
                return;
            }
            arrowQueue.add(keyCode);
            drainArrows();
        });
    }

    /** Runs only on {@link #arrowExec}. */
    private void drainArrows() {
        if (arrowBusy || arrowQueue.isEmpty()) {
            return;
        }
        final int keyCode = arrowQueue.poll();
        if (systemDpad(keyCode) || moveFocusOneStep(keyCode)) {
            arrowBusy = true;
            final int gen = ++arrowGen;
            arrowExec.execute(() -> finishArrow(gen, 0, false));
            return;
        }
        if (AgentInput.canSendRealKeys(this)) {
            // A real key moves focus one item even in touch mode; a swipe does not.
            AgentInput.pressKey(this, keyCode);
            arrowBusy = true;
            final int gen = ++arrowGen;
            arrowExec.execute(() -> finishArrow(gen, 0, false));
            return;
        }
        final float step = measureStep(keyCode);
        arrowBusy = true;
        final int gen = ++arrowGen;
        handler.post(() -> dispatchArrow(keyCode, step, gen));
    }

    /**
     * Main thread, gesture dispatch only. No node queries: those deadlock this
     * looper against system_server and then every key posted here stops.
     */
    private void dispatchArrow(int keyCode, float step, int gen) {
        if (gestureActive || pressPending || scrollBusy || displayW < 3 || displayH < 3
                || step < 2f) {
            arrowExec.execute(() -> finishArrow(gen, keyCode, true));
            return;
        }
        final boolean vertical = keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        final boolean forward = keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        final float travel = Math.min(step, vertical ? displayH * 0.45f : displayW * 0.45f);
        final float cx = displayW * 0.5f;
        final float cy = displayH * 0.5f;
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        if (vertical) {
            x1 = cx;
            x2 = cx;
            if (forward) {
                y1 = Math.min(displayH - 2f, cy + travel * 0.5f);
                y2 = Math.max(1f, y1 - travel);
            } else {
                y1 = Math.max(1f, cy - travel * 0.5f);
                y2 = Math.min(displayH - 2f, y1 + travel);
            }
        } else {
            y1 = cy;
            y2 = cy;
            if (forward) {
                x1 = Math.min(displayW - 2f, cx + travel * 0.5f);
                x2 = Math.max(1f, x1 - travel);
            } else {
                x1 = Math.max(1f, cx - travel * 0.5f);
                x2 = Math.min(displayW - 2f, x1 + travel);
            }
        }
        if (Math.abs(x2 - x1) < 2f && Math.abs(y2 - y1) < 2f) {
            arrowExec.execute(() -> finishArrow(gen, keyCode, true));
            return;
        }
        // One gesture, and it ends with the finger still. willContinue left true
        // with no follow-up keeps the pointer down, and the device then ignores
        // every key until that pointer is lifted. The hold is in the same gesture
        // so the lift is part of this call, not a second one that might never run.
        final GestureDescription gesture;
        try {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription.StrokeDescription move =
                    new GestureDescription.StrokeDescription(path, 0, ARROW_MOVE_MS, true);
            Path hold = new Path();
            hold.moveTo(x2, y2);
            hold.lineTo(x2 + (x2 < displayW - 3f ? 1f : -1f), y2);
            GestureDescription.StrokeDescription rest =
                    move.continueStroke(hold, ARROW_MOVE_MS, ARROW_HOLD_MS, false);
            gesture = new GestureDescription.Builder().addStroke(move).addStroke(rest).build();
        } catch (Throwable t) {
            arrowExec.execute(() -> finishArrow(gen, keyCode, true));
            return;
        }
        handler.postDelayed(() -> arrowExec.execute(() -> finishArrow(gen, keyCode, false)),
                ARROW_MOVE_MS + ARROW_HOLD_MS + ARROW_STALL_MS);
        // Set only after dispatchGesture returns. A callback delivered before that
        // must not consume the generation, or the key is dropped and also not injected.
        final java.util.concurrent.atomic.AtomicBoolean accepted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean ok;
        try {
            ok = dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    if (accepted.get()) {
                        arrowExec.execute(() -> finishArrow(gen, keyCode, false));
                    }
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    if (accepted.get()) {
                        arrowExec.execute(() -> finishArrow(gen, keyCode, false));
                    }
                }
            }, handler);
        } catch (Throwable t) {
            ok = false;
        }
        if (ok) {
            accepted.set(true);
        } else {
            arrowExec.execute(() -> finishArrow(gen, keyCode, true));
        }
    }

    // AccessibilityService.GLOBAL_ACTION_DPAD_* (API 33). The system delivers a
    // real d-pad key, which leaves touch mode and moves focus exactly one item.
    // An app cannot inject that key itself without INJECT_EVENTS.
    private static final int GLOBAL_DPAD_UP = 16;
    private static final int GLOBAL_DPAD_DOWN = 17;
    private static final int GLOBAL_DPAD_LEFT = 18;
    private static final int GLOBAL_DPAD_RIGHT = 19;
    private static final int GLOBAL_DPAD_CENTER = 20;

    private boolean systemDpad(int keyCode) {
        if (Build.VERSION.SDK_INT < 33) {
            return false;
        }
        final int action;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                action = GLOBAL_DPAD_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                action = GLOBAL_DPAD_DOWN;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                action = GLOBAL_DPAD_LEFT;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                action = GLOBAL_DPAD_RIGHT;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                action = GLOBAL_DPAD_CENTER;
                break;
            default:
                return false;
        }
        try {
            return performGlobalAction(action);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Runs only on {@link #arrowExec}. {@code deliverKey} is the swipe that never started. */
    private void finishArrow(int gen, int keyCode, boolean deliverKey) {
        if (gen != arrowGen) {
            return;
        }
        arrowGen++;
        arrowBusy = false;
        if (deliverKey && keyCode != 0) {
            AgentInput.pressKey(this, keyCode);
        }
        drainArrows();
    }

    /** Distance of one row, from the focused item's neighbour. No full-tree walk. */
    private float measureStep(int keyCode) {
        ensureDisplaySize();
        final boolean vertical = keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        final float fallback = Math.max(48f, vertical ? displayH * 0.12f : displayW * 0.18f);
        AccessibilityNodeInfo focus = null;
        AccessibilityNodeInfo next = null;
        try {
            focus = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focus == null) {
                focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            }
            if (focus == null) {
                return fallback;
            }
            Rect from = new Rect();
            focus.getBoundsInScreen(from);
            next = siblingInDirection(focus, keyCode);
            if (next != null) {
                Rect to = new Rect();
                next.getBoundsInScreen(to);
                int delta = vertical
                        ? Math.abs(to.centerY() - from.centerY())
                        : Math.abs(to.centerX() - from.centerX());
                if (delta > 8) {
                    return delta;
                }
            }
            int self = vertical ? from.height() : from.width();
            float limit = vertical ? displayH * 0.45f : displayW * 0.45f;
            if (self > 8 && self < limit) {
                return self;
            }
            return fallback;
        } catch (Throwable t) {
            return fallback;
        } finally {
            recycleQuiet(next);
            recycleQuiet(focus);
        }
    }

    private AccessibilityNodeInfo siblingInDirection(AccessibilityNodeInfo from, int keyCode) {
        AccessibilityNodeInfo parent = from.getParent();
        if (parent == null) {
            return null;
        }
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        try {
            Rect fromBounds = new Rect();
            from.getBoundsInScreen(fromBounds);
            int count = Math.min(parent.getChildCount(), 40);
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = parent.getChild(i);
                if (child == null) {
                    continue;
                }
                try {
                    if (!child.isVisibleToUser()) {
                        continue;
                    }
                    Rect to = new Rect();
                    child.getBoundsInScreen(to);
                    if (to.width() < 8 || to.height() < 8 || sameBounds(fromBounds, to)) {
                        continue;
                    }
                    int score = directionScore(fromBounds, to, keyCode);
                    if (score < 0 || score >= bestScore) {
                        continue;
                    }
                    recycleQuiet(best);
                    best = AccessibilityNodeInfo.obtain(child);
                    bestScore = score;
                } finally {
                    recycleQuiet(child);
                }
            }
            return best;
        } catch (Throwable t) {
            recycleQuiet(best);
            return null;
        } finally {
            recycleQuiet(parent);
        }
    }

    private static boolean sameBounds(Rect a, Rect b) {
        return Math.abs(a.centerX() - b.centerX()) < 4
                && Math.abs(a.centerY() - b.centerY()) < 4
                && Math.abs(a.width() - b.width()) < 8
                && Math.abs(a.height() - b.height()) < 8;
    }

    /** Smaller is closer. Negative means {@code to} is not in the key's direction. */
    private static int directionScore(Rect from, Rect to, int keyCode) {
        int dx = to.centerX() - from.centerX();
        int dy = to.centerY() - from.centerY();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (to.centerY() <= from.centerY() || to.top < from.bottom - from.height() / 3) {
                    return -1;
                }
                return dy * 4 + Math.abs(dx);
            case KeyEvent.KEYCODE_DPAD_UP:
                if (to.centerY() >= from.centerY() || to.bottom > from.top + from.height() / 3) {
                    return -1;
                }
                return -dy * 4 + Math.abs(dx);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (to.centerX() <= from.centerX() || to.left < from.right - from.width() / 3) {
                    return -1;
                }
                return dx * 4 + Math.abs(dy);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (to.centerX() >= from.centerX() || to.right > from.left + from.width() / 3) {
                    return -1;
                }
                return -dx * 4 + Math.abs(dy);
            default:
                return -1;
        }
    }

    /** How many nodes one arrow press may look at. This runs off the main looper. */
    private static final int FOCUS_SCAN_BUDGET = 140;

    /**
     * Move input focus to the neighbour in this direction, which is one item by
     * definition. False when nothing holds focus (a plain touch screen, where a
     * swipe is the only thing that moves) or the focus is already at that edge.
     */
    private boolean moveFocusOneStep(int keyCode) {
        AccessibilityNodeInfo current = null;
        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo best = null;
        try {
            current = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (current == null || current.isEditable()) {
                // No focus, or a text field that editKey has already had its turn at.
                return false;
            }
            Rect from = new Rect();
            current.getBoundsInScreen(from);
            if (from.isEmpty()) {
                return false;
            }
            root = getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            best = nearestFocusable(root, from, keyCode, null, new int[]{FOCUS_SCAN_BUDGET});
            return best != null && best.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        } catch (Throwable t) {
            return false;
        } finally {
            recycleQuiet(best);
            recycleQuiet(root);
            recycleQuiet(current);
        }
    }

    private AccessibilityNodeInfo nearestFocusable(AccessibilityNodeInfo node, Rect from,
            int keyCode, AccessibilityNodeInfo best, int[] budget) {
        if (node == null || budget[0] <= 0) {
            return best;
        }
        budget[0]--;
        if (isFocusTarget(node)) {
            Rect to = new Rect();
            node.getBoundsInScreen(to);
            if (inDirection(from, to, keyCode) && closer(from, to, best, keyCode)) {
                recycleQuiet(best);
                best = AccessibilityNodeInfo.obtain(node);
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            best = nearestFocusable(child, from, keyCode, best, budget);
            recycleQuiet(child);
        }
        return best;
    }

    private static boolean isFocusTarget(AccessibilityNodeInfo node) {
        if (!node.isFocusable() || !node.isVisibleToUser() || node.isFocused()) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return !bounds.isEmpty();
    }

    /** True when {@code to} sits wholly past the middle of {@code from} along the key's axis. */
    private static boolean inDirection(Rect from, Rect to, int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return to.bottom <= from.centerY();
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return to.top >= from.centerY();
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return to.right <= from.centerX();
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return to.left >= from.centerX();
            default:
                return false;
        }
    }

    /**
     * Shortest step along the key's axis wins, and among equals the one closest
     * across it. That is the order the platform itself uses, so one press lands on
     * the adjacent item instead of skipping one.
     */
    private static boolean closer(Rect from, Rect to, AccessibilityNodeInfo best, int keyCode) {
        if (best == null) {
            return true;
        }
        Rect other = new Rect();
        best.getBoundsInScreen(other);
        final boolean vertical = keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        int toMain = vertical ? Math.abs(to.centerY() - from.centerY())
                : Math.abs(to.centerX() - from.centerX());
        int bestMain = vertical ? Math.abs(other.centerY() - from.centerY())
                : Math.abs(other.centerX() - from.centerX());
        if (toMain != bestMain) {
            return toMain < bestMain;
        }
        int toCross = vertical ? Math.abs(to.centerX() - from.centerX())
                : Math.abs(to.centerY() - from.centerY());
        int bestCross = vertical ? Math.abs(other.centerX() - from.centerX())
                : Math.abs(other.centerY() - from.centerY());
        return toCross < bestCross;
    }

    private static void recycleQuiet(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        try {
            node.recycle();
        } catch (Throwable ignored) {
        }
    }

    private int selAnchor = -1;

    /**
     * Home, End and the arrow keys, including Shift and Ctrl. Moves or extends
     * the caret in the focused field. Returns false when nothing is being edited,
     * so a plain left/right can still pan the screen.
     */
    public boolean editKey(int keyCode, int meta) {
        final boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
        final boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
        final boolean alt = (meta & KeyEvent.META_ALT_ON) != 0;
        if (alt) {
            return false;
        }
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || !focus.isEditable()) {
            if (focus != null) {
                focus.recycle();
            }
            if (!shift) {
                selAnchor = -1;
            }
            return false;
        }
        String text = fieldText(focus);
        int len = text.length();
        int selStart = focus.getTextSelectionStart();
        int selEnd = focus.getTextSelectionEnd();
        if (showingHint(focus)) {
            selStart = 0;
            selEnd = 0;
        }
        if (selStart < 0) {
            selStart = len;
        }
        if (selEnd < 0) {
            selEnd = selStart;
        }
        if (selStart > selEnd) {
            int swap = selStart;
            selStart = selEnd;
            selEnd = swap;
        }
        int moving = movingEdge(selStart, selEnd);
        int target = moving;
        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME) {
            target = ctrl ? 0 : lineStart(text, moving);
        } else if (keyCode == KeyEvent.KEYCODE_MOVE_END) {
            target = ctrl ? len : lineEnd(text, moving);
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean toRight = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            target = ctrl ? stepWord(text, moving, toRight)
                    : (toRight ? Math.min(len, moving + 1) : Math.max(0, moving - 1));
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            boolean downKey = keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
            int stepped = stepLine(text, moving, downKey);
            if (stepped == moving) {
                // No hard line break to cross. Ask the field to move by a visual
                // line. If it cannot, do not jump the caret to either end of this
                // line; the caller then scrolls the page or injects the key.
                if (moveByLine(focus, downKey, shift)) {
                    focus.recycle();
                    return true;
                }
                focus.recycle();
                return false;
            }
            target = stepped;
        }
        if (!shift) {
            selAnchor = -1;
            setSelection(focus, target, target);
        } else {
            if (selAnchor < 0) {
                selAnchor = selStart == selEnd ? selEnd : selStart;
            }
            setSelection(focus, Math.min(selAnchor, target), Math.max(selAnchor, target));
        }
        focus.recycle();
        return true;
    }

    private int movingEdge(int selStart, int selEnd) {
        if (selAnchor < 0 || selEnd != selAnchor) {
            return selEnd;
        }
        return selStart;
    }

    private static int lineStart(String text, int index) {
        if (index < 0) {
            index = 0;
        }
        if (index > text.length()) {
            index = text.length();
        }
        int nl = text.lastIndexOf('\n', index - 1);
        return nl < 0 ? 0 : nl + 1;
    }

    private static int lineEnd(String text, int index) {
        int nl = text.indexOf('\n', Math.max(0, index));
        return nl < 0 ? text.length() : nl;
    }

    private static int stepWord(String text, int index, boolean toRight) {
        int len = text.length();
        int i = Math.max(0, Math.min(len, index));
        if (toRight) {
            while (i < len && !Character.isLetterOrDigit(text.charAt(i))) {
                i++;
            }
            while (i < len && Character.isLetterOrDigit(text.charAt(i))) {
                i++;
            }
            return i;
        }
        while (i > 0 && !Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static int stepLine(String text, int index, boolean down) {
        int start = lineStart(text, index);
        int col = index - start;
        if (!down) {
            if (start == 0) {
                return index;
            }
            int prevEnd = start - 1;
            int prevStart = lineStart(text, prevEnd);
            return Math.min(prevStart + col, prevEnd);
        }
        int end = lineEnd(text, index);
        if (end >= text.length()) {
            return index;
        }
        int nextStart = end + 1;
        int nextEnd = lineEnd(text, nextStart);
        return Math.min(nextStart + col, nextEnd);
    }

    /** Move the caret by one visual line. False when this field cannot. */
    private static boolean moveByLine(AccessibilityNodeInfo node, boolean down, boolean extend) {
        Bundle args = new Bundle();
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);
        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extend);
        int action = down
                ? AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                : AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY;
        try {
            return node.performAction(action, args);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setSelection(AccessibilityNodeInfo node, int start, int end) {
        Bundle args = new Bundle();
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
        try {
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
        } catch (Throwable t) {
            return false;
        }
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
            // On this thread, not the main looper. Posting it there meant one
            // stalled arrow gesture dropped Enter along with every letter.
            performEnter(keyCode);
            return true;
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

    private static final String[] IME_LABELS = {
            "enter", "return", "search", "go", "done", "send", "next", "ok", "confirm",
            "검색", "이동", "완료", "전송", "다음", "확인", "보내기", "엔터"
    };

    /**
     * Enter. The field's own IME action comes first: it is a single call and it is
     * exactly what the field is listening for. Only if that is refused do we look
     * for the on-screen action key (Search, Send, newline). A multiline field with
     * no such key gets a line break. Anything still left is a real key press.
     */
    private void performEnter(int keyCode) {
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null && focus.isEditable()) {
            if (Build.VERSION.SDK_INT >= 30
                    && focus.performAction(android.R.id.accessibilityActionImeEnter)) {
                focus.recycle();
                return;
            }
            focus.recycle();
            if (clickImeActionKey()) {
                return;
            }
            focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && focus.isMultiLine() && insertNewline(focus)) {
                focus.recycle();
                return;
            }
            if (focus != null) {
                focus.recycle();
            }
        } else if (focus != null) {
            boolean ok = focus.isClickable()
                    && focus.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            focus.recycle();
            if (ok) {
                return;
            }
        } else {
            AccessibilityNodeInfo acc = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (acc != null) {
                boolean ok = acc.isClickable()
                        && acc.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                acc.recycle();
                if (ok) {
                    return;
                }
            }
            if (clickImeActionKey()) {
                return;
            }
        }
        if (systemDpad(keyCode)) {
            return;
        }
        AgentInput.pressKey(this, keyCode);
    }

    /** True only when the field actually kept the line break. */
    private boolean insertNewline(AccessibilityNodeInfo focus) {
        String before = fieldText(focus);
        if (!replaceSelection(focus, "\n")) {
            return false;
        }
        try {
            if (!focus.refresh()) {
                return true;
            }
        } catch (Throwable t) {
            return true;
        }
        String after = fieldText(focus);
        return after.length() > before.length() && after.contains("\n");
    }

    /** Press the keyboard's Enter / Search / Send key when that keyboard is showing. */
    private boolean clickImeActionKey() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Throwable t) {
            return false;
        }
        if (windows == null) {
            return false;
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null
                    || window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                continue;
            }
            AccessibilityNodeInfo hit = findImeActionKey(root, 0, new int[]{IME_SCAN_BUDGET});
            boolean ok = hit != null && hit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (hit != null) {
                hit.recycle();
            }
            root.recycle();
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /**
     * A keyboard is hundreds of nodes and every getChild is a binder round trip, so
     * the walk is capped. Running past the cap on the service thread held up every
     * later key.
     */
    private static final int IME_SCAN_BUDGET = 300;

    private AccessibilityNodeInfo findImeActionKey(AccessibilityNodeInfo node, int depth,
            int[] budget) {
        if (node == null || depth > 12 || budget[0] <= 0) {
            return null;
        }
        budget[0]--;
        if (isImeActionKey(node)) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo found = findImeActionKey(child, depth + 1, budget);
            child.recycle();
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isImeActionKey(AccessibilityNodeInfo node) {
        if (!node.isClickable()) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        ensureDisplaySize();
        if (displayW > 0 && bounds.width() > displayW * 0.5f) {
            return false;
        }
        if (displayH > 0 && bounds.height() > displayH * 0.22f) {
            return false;
        }
        String id = null;
        try {
            id = node.getViewIdResourceName();
        } catch (Throwable ignored) {
        }
        if (id != null) {
            String lower = id.toLowerCase(Locale.US);
            if (lower.contains("ime_action") || lower.contains("key_enter")
                    || lower.contains("key_pos_ime") || lower.contains("enter_key")) {
                return true;
            }
        }
        String label = nodeLabel(node).toLowerCase(Locale.ROOT).trim();
        if (label.isEmpty()) {
            return false;
        }
        for (String word : IME_LABELS) {
            if (label.equals(word) || label.equals(word + " button")
                    || label.equals(word + " key")) {
                return true;
            }
        }
        return false;
    }

    private static String nodeLabel(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            return text.toString();
        }
        CharSequence desc = node.getContentDescription();
        return desc == null ? "" : desc.toString();
    }

    private String lastEditId;
    private final Rect lastEditBounds = new Rect();
    private int lastEditCaret = -1;

    public boolean typeText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (Looper.myLooper() == handler.getLooper()) {
            return typeTextOnMain(text);
        }
        final java.util.concurrent.atomic.AtomicBoolean ok =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger state =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        final Runnable job = () -> {
            // 2 means the caller gave up and will inject the character itself.
            if (!state.compareAndSet(0, 1)) {
                done.countDown();
                return;
            }
            try {
                ok.set(typeTextOnMain(text));
            } finally {
                done.countDown();
            }
        };
        handler.post(job);
        try {
            if (!done.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // Still queued, not running. Drop it and let the caller inject.
                // Returning true here used to swallow the letter: the main looper
                // was busy, the wait expired, and nothing was ever inserted.
                if (state.compareAndSet(0, 2)) {
                    handler.removeCallbacks(job);
                    return false;
                }
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok.get();
    }

    /**
     * Inserts into the focused field and keeps that focus. Setting the text from
     * accessibility drops input focus on some phones, so the next letter never
     * landed until the field was tapped again.
     */
    private boolean typeTextOnMain(String text) {
        ensureLastFocus();
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || !focus.isEditable()) {
            if (focus != null) {
                focus.recycle();
            }
            return false;
        }
        rememberEditable(focus);
        boolean ok = replaceSelection(focus, text);
        if (ok) {
            focus.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            int end = focus.getTextSelectionEnd();
            CharSequence current = focus.getText();
            int len = current == null ? 0 : current.length();
            lastEditCaret = end >= 0 ? end : len;
            handler.post(this::ensureLastFocus);
        }
        focus.recycle();
        return ok;
    }

    private void rememberEditable(AccessibilityNodeInfo node) {
        try {
            lastEditId = node.getViewIdResourceName();
        } catch (Throwable t) {
            lastEditId = null;
        }
        node.getBoundsInScreen(lastEditBounds);
        int end = node.getTextSelectionEnd();
        if (end >= 0) {
            lastEditCaret = end;
        }
    }

    /** Puts input focus back on the field that was just typed into. */
    private void ensureLastFocus() {
        AccessibilityNodeInfo current = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (current != null && current.isEditable()) {
            rememberEditable(current);
            current.recycle();
            return;
        }
        if (current != null) {
            current.recycle();
        }
        AccessibilityNodeInfo node = findRememberedEditable();
        if (node == null) {
            return;
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        AccessibilityNodeInfo again = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        boolean focused = again != null && again.isEditable();
        if (again != null) {
            again.recycle();
        }
        if (!focused) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            String value = fieldText(node);
            int len = value.length();
            int caret = lastEditCaret >= 0 ? Math.min(lastEditCaret, len) : len;
            setSelection(node, caret, caret);
        }
        rememberEditable(node);
        node.recycle();
    }

    private AccessibilityNodeInfo findRememberedEditable() {
        if ((lastEditId == null || lastEditId.isEmpty()) && lastEditBounds.isEmpty()) {
            return null;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        AccessibilityNodeInfo found = null;
        if (lastEditId != null && !lastEditId.isEmpty()) {
            try {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByViewId(lastEditId);
                if (nodes != null) {
                    for (AccessibilityNodeInfo n : nodes) {
                        if (n == null) {
                            continue;
                        }
                        if (found == null && n.isEditable()) {
                            found = n;
                        } else {
                            n.recycle();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (found == null && !lastEditBounds.isEmpty()) {
            found = smallestEditable(root, lastEditBounds.centerX(), lastEditBounds.centerY(), null);
        }
        root.recycle();
        return found;
    }

    /**
     * True when the field is empty and the words on screen are its placeholder.
     * Those words are not content: typing must replace nothing, and editing must
     * leave the placeholder alone until real text exists.
     */
    private static boolean showingHint(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (node.isShowingHintText()) {
            return true;
        }
        CharSequence hint = node.getHintText();
        CharSequence text = node.getText();
        if (hint == null || hint.length() == 0 || text == null) {
            return false;
        }
        if (!hint.toString().equals(text.toString())) {
            return false;
        }
        int start = node.getTextSelectionStart();
        int end = node.getTextSelectionEnd();
        if (start < 0 || end < 0) {
            return true;
        }
        if (start == 0 && end == 0) {
            return true;
        }
        return start == 0 && end == text.length();
    }

    /** Field content. Empty while a placeholder is showing, so it is never edited. */
    private static String fieldText(AccessibilityNodeInfo node) {
        if (node == null || showingHint(node)) {
            return "";
        }
        CharSequence raw = node.getText();
        return raw == null ? "" : raw.toString();
    }

    private static String selectedText(AccessibilityNodeInfo node) {
        String text = fieldText(node);
        if (text.isEmpty()) {
            return "";
        }
        int start = node.getTextSelectionStart();
        int end = node.getTextSelectionEnd();
        if (start < 0 || end < 0 || start == end) {
            return "";
        }
        if (start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        if (end > text.length()) {
            end = text.length();
        }
        if (start >= text.length()) {
            return "";
        }
        return text.substring(start, end);
    }

    private void setPhoneClipboard(String value) {
        try {
            ClipboardManager clips = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clips != null) {
                clips.setPrimaryClip(ClipData.newPlainText("remote", value));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Inserts at the caret, or replaces the current selection. */
    private static boolean replaceSelection(AccessibilityNodeInfo node, String insert) {
        boolean hint = showingHint(node);
        String text = hint ? "" : fieldText(node);
        int start = hint ? 0 : node.getTextSelectionStart();
        int end = hint ? 0 : node.getTextSelectionEnd();
        if (start < 0) {
            start = text.length();
        }
        if (end < 0) {
            end = start;
        }
        if (start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        start = Math.max(0, Math.min(text.length(), start));
        end = Math.max(start, Math.min(text.length(), end));
        String next = text.substring(0, start) + (insert == null ? "" : insert) + text.substring(end);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return false;
        }
        int caret = start + (insert == null ? 0 : insert.length());
        setSelection(node, caret, caret);
        return true;
    }

    /**
     * Copy, cut, paste and select-all on the focused field. Paste uses [text]
     * from the controller when the field will not take the phone clipboard.
     */
    public boolean clipboard(String action, String text) {
        if ("paste".equals(action) && text != null && !text.isEmpty()) {
            try {
                ClipboardManager clips = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clips != null) {
                    clips.setPrimaryClip(ClipData.newPlainText("remote", text));
                }
            } catch (Throwable ignored) {
            }
        }
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) {
            focus = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        }
        if (focus == null) {
            return "paste".equals(action) && text != null && !text.isEmpty() && typeText(text);
        }
        boolean ok = false;
        if ("copy".equals(action) || "cut".equals(action)) {
            String selected = selectedText(focus);
            if (selected != null && !selected.isEmpty()) {
                setPhoneClipboard(selected);
                ok = true;
            }
            int act = "cut".equals(action)
                    ? AccessibilityNodeInfo.ACTION_CUT
                    : AccessibilityNodeInfo.ACTION_COPY;
            if (focus.performAction(act)) {
                ok = true;
            } else if ("cut".equals(action)) {
                ok = replaceSelection(focus, "") || ok;
            }
        } else if ("paste".equals(action)) {
            ok = focus.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            if (!ok && text != null && !text.isEmpty()) {
                ok = replaceSelection(focus, text);
                if (!ok) {
                    focus.recycle();
                    return typeText(text);
                }
            }
        } else if ("selectall".equals(action)) {
            String existing = fieldText(focus);
            int len = existing.length();
            Bundle args = new Bundle();
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len);
            ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
        }
        focus.recycle();
        return ok;
    }

    /** Forget an in-flight press so a new controller is not stuck behind it. */
    public void releasePointer() {
        Runnable reset = () -> {
            pendingDown = false;
            pendingUp = false;
            pressPending = false;
            selAnchor = -1;
            lastEditId = null;
            lastEditBounds.setEmpty();
            lastEditCaret = -1;
            arrowExec.execute(() -> {
                arrowQueue.clear();
                arrowBusy = false;
                arrowGen++;
            });
            resetGesture();
        };
        if (Looper.myLooper() == handler.getLooper()) {
            reset.run();
        } else {
            handler.post(reset);
        }
    }

    boolean deleteText() {
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || !focus.isEditable()) {
            if (focus != null) {
                focus.recycle();
            }
            return false;
        }
        String existing = fieldText(focus);
        if (existing.isEmpty()) {
            focus.recycle();
            return false;
        }
        String next = existing.substring(0, existing.length() - 1);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
        boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        focus.recycle();
        return ok;
    }

    private void handleTouch(String action, float x, float y) {
        RemoteCursor.move(this, x, y);
        if ("down".equals(action)) {
            if (gestureActive && segmentDispatchedAt == 0L) {
                // The chain is marked open but nothing is in flight, so no callback
                // will ever close it. Leaving it there dropped every later click.
                resetGesture();
            }
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
        if (segmentDispatchedAt == 0L
                || now - segmentDispatchedAt >= segmentDurationMs + GESTURE_STALL_MS) {
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
        refreshDisplaySize();
        x = clampX(x);
        y = clampY(y);
        // A switch ignores a 1px gesture and stays put. Click that switch only when
        // the pointer is actually on it. Walking up to a row and clicking whatever
        // toggle lived there returned success for ordinary buttons and then skipped
        // the gesture, which is why a click did nothing.
        if (holdMs < 400L && clickExactToggle(x, y)) {
            return;
        }
        if (holdMs < 400L) {
            focusEditableAt(x, y);
        }
        tapAttempt(x, y, holdMs, 0);
    }

    /** Toggle the checkable control under this point. False when the point is not on one. */
    private boolean clickExactToggle(float x, float y) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        AccessibilityNodeInfo hit = deepestAt(root, (int) x, (int) y, null);
        root.recycle();
        if (hit == null) {
            return false;
        }
        AccessibilityNodeInfo cursor = hit;
        boolean ok = false;
        for (int depth = 0; cursor != null && depth < 6 && !ok; depth++) {
            Rect bounds = new Rect();
            cursor.getBoundsInScreen(bounds);
            if (!bounds.contains((int) x, (int) y)) {
                break;
            }
            if (isToggle(cursor) && cursor.isClickable()) {
                ok = cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                break;
            }
            AccessibilityNodeInfo parent = cursor.getParent();
            if (cursor != hit) {
                cursor.recycle();
            }
            cursor = parent;
        }
        if (cursor != null && cursor != hit) {
            cursor.recycle();
        }
        hit.recycle();
        return ok;
    }

    private static boolean isToggle(AccessibilityNodeInfo node) {
        if (node.isCheckable()) {
            return true;
        }
        CharSequence name = node.getClassName();
        if (name == null) {
            return false;
        }
        String s = name.toString();
        return s.contains("Switch") || s.contains("CheckBox") || s.contains("Toggle")
                || s.contains("RadioButton") || s.contains("CompoundButton")
                || s.contains("QSTile") || s.contains("TileView");
    }

    private boolean focusEditableAt(float x, float y) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        AccessibilityNodeInfo hit = smallestEditable(root, (int) x, (int) y, null);
        root.recycle();
        if (hit == null) {
            return false;
        }
        // Focus only. ACTION_CLICK here used to report success and skip the gesture,
        // so the field (or a control mis-read as editable) never actually received
        // the press. The gesture below is the click.
        hit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        hit.recycle();
        return false;
    }

    private AccessibilityNodeInfo smallestEditable(AccessibilityNodeInfo node, int x, int y,
            AccessibilityNodeInfo best) {
        if (node == null) {
            return best;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) {
            return best;
        }
        if (node.isEditable()) {
            if (best != null) {
                best.recycle();
            }
            best = AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            best = smallestEditable(child, x, y, best);
            child.recycle();
        }
        return best;
    }

    private boolean clickNodeAtPoint(float x, float y) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        AccessibilityNodeInfo hit = deepestAt(root, (int) x, (int) y, null);
        root.recycle();
        if (hit == null) {
            return false;
        }
        boolean ok = performClickCompat(hit);
        hit.recycle();
        return ok;
    }

    private AccessibilityNodeInfo deepestAt(AccessibilityNodeInfo node, int x, int y,
            AccessibilityNodeInfo best) {
        if (node == null) {
            return best;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) {
            return best;
        }
        if (best != null) {
            best.recycle();
        }
        best = AccessibilityNodeInfo.obtain(node);
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            best = deepestAt(child, x, y, best);
            child.recycle();
        }
        return best;
    }

    /**
     * The system allows one gesture in flight at a time and refuses the rest.
     * Until now a refused tap was simply dropped, which is what made a click do
     * nothing until the operator clicked again — the earlier gesture (the scroll
     * stroke, or the tap before it playing out its hold) was still running. The
     * slot frees itself in a few tens of milliseconds, so wait for it rather than
     * losing the click.
     */
    private void tapAttempt(final float x, final float y, final long holdMs, final int attempt) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        ensureDisplaySize();
        float x1 = clampX(x);
        float y1 = clampY(y);
        float x2 = x1 + (displayW <= 0 || x1 < displayW - 2f ? 1f : -1f);
        Path path = new Path();
        line(path, x1, y1, x2, y1);
        final long dur = Math.max(TAP_GESTURE_MS, Math.min(MAX_TAP_HOLD_MS, holdMs));
        if (dispatchTap(new GestureDescription.StrokeDescription(path, 0, dur), x1, y1)) {
            return;
        }
        if (attempt >= TAP_RETRY_MAX) {
            deliverTapFallback(x1, y1);
            return;
        }
        handler.postDelayed(() -> tapAttempt(x, y, holdMs, attempt + 1), TAP_RETRY_GAP_MS);
    }

    /**
     * One-shot click. A cancelled gesture (the system accepted the dispatch and
     * then dropped it) still has to land, or the desktop click does nothing.
     */
    private boolean dispatchTap(GestureDescription.StrokeDescription next, float x, float y) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        try {
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(next)
                    .build();
            return dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCancelled(GestureDescription g) {
                    deliverTapFallback(x, y);
                }
            }, handler);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Node click, then an injected tap, when the gesture never ran. */
    private void deliverTapFallback(float x, float y) {
        if (clickNodeAtPoint(x, y)) {
            return;
        }
        AgentInput.injectTap(this, Math.round(clampX(x)), Math.round(clampY(y)));
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
