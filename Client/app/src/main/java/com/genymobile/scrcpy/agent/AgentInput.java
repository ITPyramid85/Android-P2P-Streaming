package com.genymobile.scrcpy.agent;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;


import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Forwards desktop mouse/keyboard to the phone.
 * Tries the system InputManager first, then Accessibility.
 */
public final class AgentInput {

    private static final String SERVICE_CLASS =
            "com.screencapture.app.input.InputAccessibility";
    private static long downTime;
    private static Method injectMethod;

    // Off-main worker for navigation on boards that need blocking shell/su calls
    // (RK3588 / LVK3588SF). Keeps performGlobalAction / input keyevent off the
    // main thread so an unavailable "su" cannot ANR the input pipeline.
    //
    // The executor is replaceable on purpose. performGlobalAction and
    // injectInputEvent are synchronous binder calls into system_server with no
    // timeout; on these boards one of them can block forever. Because navigation
    // was funnelled through a single thread, one such hang silently swallowed every
    // later Back/Home/Recents — the queued tasks never ran, so not even a warning
    // was logged. If the running task overruns NAV_STUCK_MS we abandon that thread
    // and continue on a fresh one, so navigation always recovers.
    private static final AtomicReference<ExecutorService> NAV_EXEC =
            new AtomicReference<>(Executors.newSingleThreadExecutor());
    /** uptimeMillis when the current nav task started; 0 when idle. */
    private static final AtomicLong navTaskStartedAt = new AtomicLong(0L);
    /** Ids identifying which task owns the busy marker above. */
    private static final AtomicLong navTaskSeq = new AtomicLong(0L);
    private static final AtomicLong navBusyTask = new AtomicLong(0L);
    private static final long NAV_STUCK_MS = 3000L;
    private static volatile Boolean suAvailable;

    private AgentInput() {
        // utility
    }

    public static void touch(Context context, String action, float nx, float ny) {
        int[] size = screenSize(context);
        int x = clamp((int) (nx * size[0]), 0, size[0] - 1);
        int y = clamp((int) (ny * size[1]), 0, size[1] - 1);
        ensureAccessibility(context);
        AgentInputService service = AgentInputService.get();
        if (service != null) {
            service.touch(action, x, y);
            return;
        }
        if (!tryInjectMotion(context, action, x, y)) {
            logInputDropped("touch " + action);
        }
    }

    public static void scroll(Context context, float nx, float ny, float vertical) {
        int[] size = screenSize(context);
        int x = clamp((int) (nx * size[0]), 0, size[0] - 1);
        int y = clamp((int) (ny * size[1]), 0, size[1] - 1);
        ensureAccessibility(context);
        AgentInputService service = AgentInputService.get();
        if (service != null) {
            service.scroll(x, y, vertical);
            return;
        }
        logInputDropped("scroll");
    }

    private static long lastDropLogAt;

    /**
     * Report input we could not deliver. Without this, a session where the
     * accessibility service is gone looks identical in the log to one where nothing
     * was ever clicked, which is exactly why "no mouse and no log lines" was so hard
     * to place. Throttled so a held mouse cannot flood the log stream.
     */
    private static void logInputDropped(String what) {
        long now = SystemClock.uptimeMillis();
        if (now - lastDropLogAt < 2000L) {
            return;
        }
        lastDropLogAt = now;
    }

    public static void key(Context context, int keyCode, boolean down) {
        // Navigation keys (Back/Home/Recents) go through the accessibility global
        // actions. On some system-privileged boards (e.g. LVK3588SF / RK3588,
        // Android 12) injectInputEvent reports success but the injected
        // HOME/BACK/APP_SWITCH keys are dropped by the window policy, so the old
        // "inject first" path returned early and did nothing. performGlobalAction
        // is the portable mechanism other devices already fall back to.
        if (isNavKey(keyCode)) {
            if (down) {
                // Act once, on key-up, to avoid double navigation.
                return;
            }
            if (isNavShellBoard()) {
                // RK3588 / LVK3588SF (Android 12): performGlobalAction can be a
                // no-op and injected policy keys are dropped by the window
                // policy. Run the ordered fallback off the main thread because
                // "su"/"input" may block. Other devices are untouched.
                final Context app = context.getApplicationContext();
                submitNav(() -> deliverNavViaShellBoard(app, keyCode));
                return;
            }
            ensureAccessibility(context);
            AgentInputService navService = AgentInputService.get();
            boolean acc = navService != null && navService.handleKey(keyCode);
            if (acc) {
                return;
            }
            // On some builds the accessibility global action is unavailable or a
            // no-op. HOME is always reachable through the launcher intent and
            // needs no special permission.
            if (keyCode == KeyEvent.KEYCODE_HOME && launchHome(context)) {
                return;
            }
            // Send one well-formed key press (shared downTime, virtual keyboard,
            // WAIT_FOR_FINISH) so the policy accepts it on privileged boards.
            boolean inj = injectNavKey(context, keyCode);
            if (inj) {
                return;
            }
            boolean sh = shellKey(keyCode);
            if (sh) {
                return;
            }
            if (navService == null) {
                // Accessibility not bound yet (e.g. just after boot). Keep the key
                // and replay it via the global action the moment the service binds.
                AgentInputService.enqueueNav(keyCode);
                return;
            }
            return;
        }
        if (tryInjectKey(context, keyCode, down)) {
            return;
        }
        if (down) {
            return;
        }
        if (shellKey(keyCode)) {
            return;
        }
        ensureAccessibility(context);
        AgentInputService service = AgentInputService.get();
        if (service != null) {
            service.handleKey(keyCode);
        }
    }

    /**
     * Hand a navigation task to the nav worker, replacing the worker first if the
     * task already on it has overrun. performGlobalAction and injectInputEvent are
     * unbounded synchronous binder calls, so one wedged call must not be allowed to
     * swallow every later key press in the queue.
     */
    private static void submitNav(Runnable task) {
        final long started = navTaskStartedAt.get();
        if (started != 0L && SystemClock.uptimeMillis() - started > NAV_STUCK_MS) {
            // The worker is stuck inside a system call we cannot interrupt. Abandon
            // that thread and continue on a fresh one so navigation keeps working.
            ExecutorService wedged = NAV_EXEC.getAndSet(Executors.newSingleThreadExecutor());
            navTaskStartedAt.set(0L);
            try {
                // Discards the keys queued behind the stuck call: replaying them now
                // would inject stale navigation. The blocked thread cannot be
                // interrupted out of a binder call, but it exits once that returns.
                wedged.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
        final Runnable timed = () -> {
            // Tag ownership of the busy marker: an abandoned worker that finally
            // unblocks must not clear the marker belonging to the task now running,
            // or the next stuck call would go undetected.
            final long id = navTaskSeq.incrementAndGet();
            navBusyTask.set(id);
            navTaskStartedAt.set(SystemClock.uptimeMillis());
            try {
                task.run();
            } catch (Throwable t) {
            } finally {
                if (navBusyTask.get() == id) {
                    navTaskStartedAt.set(0L);
                }
            }
        };
        try {
            NAV_EXEC.get().execute(timed);
        } catch (Throwable t) {
            // A replaced executor can reject late work; run it on a throwaway thread
            // rather than dropping the key silently.
            new Thread(timed, "nav-fallback").start();
        }
    }

    private static boolean isNavKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_APP_SWITCH;
    }

    /**
     * True for the RK3588 / LVK3588SF class of boards where the accessibility
     * global action is unreliable for HOME/BACK/RECENTS. Gated by build fields
     * so no other device changes behaviour.
     */
    private static boolean isNavShellBoard() {
        return matchesRk3588(Build.MODEL) || matchesRk3588(Build.BOARD)
                || matchesRk3588(Build.HARDWARE) || matchesRk3588(Build.DEVICE)
                || matchesRk3588(Build.PRODUCT);
    }

    private static boolean matchesRk3588(String value) {
        if (value == null) {
            return false;
        }
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("rk3588") || s.contains("lvk3588") || s.contains("3588");
    }

    /**
     * Ordered navigation delivery for the shell-board class. The accessibility
     * global action goes first: it is the only mechanism here that reports real
     * success, needs no root, and works once the service is bound. This board's
     * "su" rejects -c ("invalid uid/gid"), and launchHome only proves an intent
     * was accepted — not that HOME happened — so both used to preempt the action
     * that actually works and Back/Home did nothing. The shell/intent/injection
     * fallbacks are kept for the pre-bind window and other boards in this class.
     * Runs on {@link #NAV_EXEC} so blocking shell calls never touch the main thread.
     */
    private static void deliverNavViaShellBoard(Context context, int keyCode) {
        ensureAccessibility(context);
        AgentInputService navService = AgentInputService.get();
        if (navService != null && navService.handleKey(keyCode)) {
            return;
        }
        if (shellKeyElevated(keyCode)) {
            return;
        }
        if (shellKey(keyCode)) {
            return;
        }
        if (keyCode == KeyEvent.KEYCODE_HOME && launchHome(context)) {
            return;
        }
        if (injectNavKey(context, keyCode)) {
            return;
        }
        if (navService == null) {
            AgentInputService.enqueueNav(keyCode);
            return;
        }
    }

    private static boolean shellKeyElevated(int keyCode) {
        if (Boolean.FALSE.equals(suAvailable)) {
            return false;
        }
        boolean ok = runShellTimed(new String[]{"su", "-c", "input keyevent " + keyCode}, 1500L);
        // Remember su presence so we stop paying the exec cost once it is known
        // to be missing; success obviously means it is present.
        if (ok) {
            suAvailable = Boolean.TRUE;
        } else if (suAvailable == null) {
            suAvailable = probeSu();
        }
        return ok;
    }

    private static Boolean probeSu() {
        Process p = null;
        try {
            p = new ProcessBuilder("which", "su").redirectErrorStream(true).start();
            boolean done = p.waitFor(800L, TimeUnit.MILLISECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return Boolean.FALSE;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Run a command and capture its output, never blocking past {@code timeoutMs}.
     *
     * The read must be deadline-bounded, not just the wait: reading to EOF first and
     * calling waitFor() afterwards meant a child that held its stdout open (this
     * board's broken "su" among them) blocked the caller forever. Since this runs on
     * the single nav worker, that hang silently killed Back/Home/Recents for the rest
     * of the session.
     */
    private static String runShellCapture(String[] cmd, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final long deadline = SystemClock.uptimeMillis() + timeoutMs;
            final java.io.InputStream in = p.getInputStream();
            final StringBuilder sb = new StringBuilder();
            final byte[] buf = new byte[512];
            while (SystemClock.uptimeMillis() < deadline) {
                // available() never blocks, so a child that stops writing without
                // closing stdout cannot pin this thread.
                final int avail = in.available();
                if (avail > 0) {
                    final int n = in.read(buf, 0, Math.min(buf.length, avail));
                    if (n < 0) {
                        break;
                    }
                    sb.append(new String(buf, 0, n, "UTF-8"));
                    continue;
                }
                if (hasExited(p)) {
                    break;
                }
                Thread.sleep(20L);
            }
            return sb.toString().replace('\r', ' ').replace('\n', ' ').trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** exitValue() throws while the process is still running on every API level. */
    private static boolean hasExited(Process p) {
        try {
            p.exitValue();
            return true;
        } catch (IllegalThreadStateException e) {
            return false;
        }
    }

    private static boolean runShellTimed(String[] cmd, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Go to the launcher via an intent. Reliable on phones and TV boxes even
     * where {@code performGlobalAction(GLOBAL_ACTION_HOME)} is a no-op, and
     * needs no injection permission. Only used as a fallback so behaviour on
     * devices where accessibility already works is unchanged.
     */
    private static boolean launchHome(Context context) {
        Context app = context.getApplicationContext();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(home);
            return true;
        } catch (Exception e) {
        }
        try {
            Intent leanback = new Intent(Intent.ACTION_MAIN);
            leanback.addCategory("android.intent.category.LEANBACK_LAUNCHER");
            leanback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(leanback);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void text(Context context, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (tryInjectText(context, value)) {
            return;
        }
        if (shellText(value)) {
            return;
        }
        ensureAccessibility(context);
        AgentInputService service = AgentInputService.get();
        if (service != null) {
            service.typeText(value);
        }
    }

    private static boolean loggedSecureDenied;
    private static boolean promptedSettings;

    public static void ensureAccessibility(Context context) {
        if (AgentInputService.get() != null) {
            return;
        }
        if (enableViaSettings(context, serviceComponent(context))) {
            return;
        }
        // WRITE_SECURE_SETTINGS is denied on normal builds. On the rooted RK3588 /
        // LVK3588SF class of boards su is available (nav keys already use it) but
        // the accessibility service is not re-bound after a reboot, which kills all
        // touch input and stalls consent auto-accept (so the screen opens slowly).
        // Force-enable the service via su so it binds; then gestures, navigation
        // and hands-free consent all work again. Gated to this board only.
        maybeEnableAccessibilityViaSu(context);
    }

    private static volatile int a11ySuAttempts = 0;
    private static volatile long a11ySuLastAttempt = 0;
    private static final int A11Y_SU_MAX_ATTEMPTS = 4;

    /**
     * Give the su-based re-enable a fresh budget after the service is lost.
     *
     * The attempt cap exists so a device without working root does not re-run su
     * forever. But it used to be spent once per process, so when accessibility
     * unbound part-way through a session there was no attempt left to rebind it and
     * every click was silently dropped for the rest of the session.
     */
    public static void onAccessibilityLost() {
        a11ySuAttempts = 0;
        a11ySuLastAttempt = 0;
    }

    private static void maybeEnableAccessibilityViaSu(Context context) {
        if (!isNavShellBoard() || Boolean.FALSE.equals(suAvailable)) {
            return;
        }
        if (a11ySuAttempts >= A11Y_SU_MAX_ATTEMPTS) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (a11ySuLastAttempt != 0 && now - a11ySuLastAttempt < 3000L) {
            return;
        }
        a11ySuLastAttempt = now;
        a11ySuAttempts++;
        final String component = serviceComponent(context);
        submitNav(() -> enableAccessibilityViaSu(component));
    }

    private static void enableAccessibilityViaSu(String component) {
        if (AgentInputService.get() != null) {
            return;
        }
        String current = runShellCapture(new String[]{"su", "-c",
                "settings get secure enabled_accessibility_services"}, 1500L);
        if (current != null) {
            current = current.trim();
            if ("null".equals(current)) {
                current = "";
            }
        }
        String next = mergeEnabledServices(current, component);
        boolean w1 = runShellTimed(new String[]{"su", "-c",
                "settings put secure enabled_accessibility_services " + next}, 1500L);
        boolean w2 = runShellTimed(new String[]{"su", "-c",
                "settings put secure accessibility_enabled 1"}, 1500L);
        if (w1 || w2) {
            suAvailable = Boolean.TRUE;
        } else if (suAvailable == null) {
            suAvailable = probeSu();
        }
    }

    /**
     * Force-open the system Accessibility page for this input service, bypassing
     * the once-per-process guard. Used when the user explicitly opens the app: if
     * the service is not bound we want the enable screen every time, not just the
     * first launch.
     */
    public static void openAccessibilitySettings(Context context) {
        promptedSettings = false;
        promptAccessibilitySettings(context);
    }

    /**
     * Opens the system Accessibility page for this input service.
     * Call from an Activity after install — do not use root or {@code su}.
     */
    public static void promptAccessibilitySettings(Context context) {
        if (context == null || AgentInputService.get() != null || promptedSettings) {
            return;
        }
        promptedSettings = true;
        ComponentName component = new ComponentName(context.getPackageName(), SERVICE_CLASS);
        // App info never lists accessibility services (phone or TV). Open the
        // system Accessibility page — ZX5M is an Android TV stick.
        if (launchAccessibilityList(context)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            details.putExtra(Intent.EXTRA_COMPONENT_NAME, component);
            details.putExtra("component_name", component.flattenToString());
            details.putExtra("component", component.flattenToString());
            details.putExtra(":settings:fragment_args_key", component.flattenToString());
            Bundle args = new Bundle();
            args.putString(":settings:fragment_args_key", component.flattenToString());
            details.putExtra(":settings:show_fragment_args", args);
            if (launchSettings(context, details)) {
                return;
            }
            if (launchNamedDetails(context, component)) {
                return;
            }
        }
    }

    private static boolean launchAccessibilityList(Context context) {
        if (launchSettings(context, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            return true;
        }
        String[] settingsPackages = {
                "com.android.tv.settings",
                "com.droidlogic.tv.settings",
                "com.android.settings",
        };
        for (String pkg : settingsPackages) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setPackage(pkg);
            if (launchSettings(context, intent)) {
                return true;
            }
        }
        return launchNamedAccessibilitySettings(context);
    }

    private static boolean launchNamedDetails(Context context, ComponentName component) {
        String[] classes = {
                "com.android.settings.Settings$AccessibilityDetailsSettingsActivity",
                "com.android.settings.accessibility.AccessibilityDetailsSettingsActivity",
                "com.android.settings.Settings$AccessibilityInstalledActivity",
        };
        for (String cls : classes) {
            Intent intent = new Intent();
            intent.setClassName("com.android.settings", cls);
            intent.putExtra(Intent.EXTRA_COMPONENT_NAME, component);
            intent.putExtra("component_name", component.flattenToString());
            intent.putExtra("component", component.flattenToString());
            if (launchSettings(context, intent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean launchNamedAccessibilitySettings(Context context) {
        String[][] components = {
                {"com.android.tv.settings", "com.android.tv.settings.system.AccessibilityActivity"},
                {"com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity"},
                {"com.droidlogic.tv.settings", "com.android.tv.settings.system.AccessibilityActivity"},
                {"com.droidlogic.tv.settings", "com.droidlogic.tv.settings.system.AccessibilityActivity"},
                {"com.android.settings", "com.android.settings.Settings$AccessibilitySettingsActivity"},
                {"com.android.settings", "com.android.settings.accessibility.AccessibilitySettings"},
                {"com.android.settings", "com.android.settings.AccessibilitySettings"},
        };
        for (String[] pair : components) {
            Intent intent = new Intent();
            intent.setClassName(pair[0], pair[1]);
            if (launchSettings(context, intent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean launchSettings(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.getApplicationContext().startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String serviceComponent(Context context) {
        return context.getPackageName() + "/" + SERVICE_CLASS;
    }

    private static boolean enableViaSettings(Context context, String component) {
        // Writing ENABLED_ACCESSIBILITY_SERVICES needs WRITE_SECURE_SETTINGS, which
        // only rooted/privileged builds hold. On an ordinary device the call throws
        // SecurityException; check first so we skip quietly instead of dumping a
        // scary stack trace, and let the other enable paths (su, manual grant) run.
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            if (!loggedSecureDenied) {
                loggedSecureDenied = true;
            }
            return false;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            String current = Settings.Secure.getString(resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String next = mergeEnabledServices(current, component);
            boolean wrote = Settings.Secure.putString(resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return wrote;
        } catch (SecurityException e) {
            if (!loggedSecureDenied) {
                loggedSecureDenied = true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean shellKey(int keyCode) {
        String code = Integer.toString(keyCode);
        return runShell(new String[]{"input", "keyevent", code});
    }

    private static boolean shellText(String value) {
        String escaped = escapeInputText(value);
        if (escaped.isEmpty()) {
            return false;
        }
        return runShell(new String[]{"input", "text", escaped});
    }

    private static String escapeInputText(String value) {
        StringBuilder out = new StringBuilder(value.length() * 2);
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (c == ' ') {
                out.append("%s");
            } else if (c == '%' || c == '"' || c == '\'' || c == '\\'
                    || c == '&' || c == '|' || c == ';' || c == '<'
                    || c == '>' || c == '(' || c == ')') {
                out.append('\\').append(c);
            } else if (c >= 32) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean tryInjectText(Context context, String value) {
        boolean all = true;
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            int keyCode = charToKeyCode(c);
            if (keyCode == 0) {
                all = false;
                continue;
            }
            if (!tryInjectKey(context, keyCode, true)
                    || !tryInjectKey(context, keyCode, false)) {
                all = false;
            }
        }
        return all && value.length() > 0;
    }

    private static int charToKeyCode(char c) {
        if (c >= 'a' && c <= 'z') {
            return KeyEvent.KEYCODE_A + (c - 'a');
        }
        if (c >= 'A' && c <= 'Z') {
            return KeyEvent.KEYCODE_A + (c - 'A');
        }
        if (c >= '0' && c <= '9') {
            return KeyEvent.KEYCODE_0 + (c - '0');
        }
        if (c == ' ') {
            return KeyEvent.KEYCODE_SPACE;
        }
        if (c == '\n') {
            return KeyEvent.KEYCODE_ENTER;
        }
        return 0;
    }

    private static boolean runShell(String[] cmd) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String mergeEnabledServices(String current, String add) {
        if (current == null || current.isEmpty() || "null".equals(current)) {
            return add;
        }
        if (current.contains(add)) {
            return current;
        }
        return current + ":" + add;
    }

    private static boolean tryInjectMotion(Context context, String action, int x, int y) {
        int motion = motionAction(action);
        if (motion < 0) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (motion == MotionEvent.ACTION_DOWN) {
            downTime = now;
        }
        if (downTime == 0) {
            downTime = now;
        }
        MotionEvent event = MotionEvent.obtain(downTime, now, motion, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean ok = inject(context, event);
        event.recycle();
        if (motion == MotionEvent.ACTION_UP) {
            downTime = 0;
        }
        return ok;
    }

    private static boolean tryInjectKey(Context context, int keyCode, boolean down) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0);
        event.setSource(InputDevice.SOURCE_KEYBOARD);
        return inject(context, event);
    }

    /**
     * Inject a complete key press for navigation keys on boards where the
     * accessibility global action is unavailable. Unlike {@link #tryInjectKey},
     * the DOWN and UP share one {@code downTime} and are delivered with
     * WAIT_FOR_FINISH, which the window policy requires to honour
     * HOME/BACK/APP_SWITCH instead of discarding them.
     */
    private static boolean injectNavKey(Context context, int keyCode) {
        long downTimeMs = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(downTimeMs, downTimeMs, KeyEvent.ACTION_DOWN,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        // Deliberately asynchronous. WAIT_FOR_FINISH blocks in system_server until
        // the focused window finishes the event, and on these boards it can block
        // forever — which wedged the nav worker and killed Back/Home/Recents for the
        // rest of the session. The mode does not affect whether the window policy
        // accepts the key, only whether we wait for it.
        boolean okDown = injectEvent(context, down, INJECT_MODE_ASYNC);
        long upTimeMs = SystemClock.uptimeMillis();
        KeyEvent up = new KeyEvent(downTimeMs, upTimeMs, KeyEvent.ACTION_UP,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        boolean okUp = injectEvent(context, up, INJECT_MODE_ASYNC);
        return okDown && okUp;
    }

    private static final int INJECT_MODE_ASYNC = 0;

    private static boolean inject(Context context, InputEvent event) {
        return injectEvent(context, event, INJECT_MODE_ASYNC);
    }

    private static boolean injectEvent(Context context, InputEvent event, int mode) {
        try {
            Object manager = context.getSystemService(Context.INPUT_SERVICE);
            if (manager == null) {
                return false;
            }
            if (injectMethod == null) {
                injectMethod = manager.getClass()
                        .getMethod("injectInputEvent", InputEvent.class, int.class);
            }
            Object result = injectMethod.invoke(manager, event, mode);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    private static int motionAction(String action) {
        if ("down".equals(action)) {
            return MotionEvent.ACTION_DOWN;
        }
        if ("move".equals(action)) {
            return MotionEvent.ACTION_MOVE;
        }
        if ("up".equals(action)) {
            return MotionEvent.ACTION_UP;
        }
        return -1;
    }

    private static int[] screenSize(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point point = new Point();
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Rect bounds = wm.getMaximumWindowMetrics().getBounds();
                point.x = Math.max(2, bounds.width());
                point.y = Math.max(2, bounds.height());
            } else {
                wm.getDefaultDisplay().getRealSize(point);
            }
        }
        if (point.x <= 0 || point.y <= 0) {
            point.x = 1080;
            point.y = 1920;
        }
        return new int[]{point.x, point.y};
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
