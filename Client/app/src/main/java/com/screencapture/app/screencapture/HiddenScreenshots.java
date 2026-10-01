package com.screencapture.app.screencapture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.lang.reflect.Method;

/**
 * Full-screen capture without MediaProjection. Calls hidden SurfaceControl /
 * IWindowManager screenshot methods via HiddenApiBypass.
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "NewApi"})
public final class HiddenScreenshots {


    private static volatile String lastMode = "none";
    private static volatile String lastError = "";
    private static volatile Context app;

    private HiddenScreenshots() {
    }

    public static String lastMode() {
        return lastMode;
    }

    public static String lastError() {
        return lastError;
    }

    static void setError(String error) {
        lastError = error == null ? "" : error;
        if (!lastError.isEmpty()) {
        }
    }

    static void appendError(String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (lastError.isEmpty()) {
            lastError = part;
        } else if (lastError.length() < 220) {
            lastError = lastError + "," + part;
        }
    }

    public static Bitmap capture(Context context) {
        HiddenApi.unseal();
        if (context != null) {
            app = context.getApplicationContext();
        }
        int[] size = screenSize(context);
        int width = size[0];
        int height = size[1];
        lastError = "";
        lastMode = "none";
        Bitmap bitmap = captureDisplayModern(width, height);
        if (valid(bitmap)) {
            lastMode = "captureDisplay";
            lastError = "";
            return software(bitmap);
        }
        bitmap = screenshotWh(width, height);
        if (valid(bitmap)) {
            lastMode = "screenshot";
            lastError = "";
            return software(bitmap);
        }
        bitmap = screenshotRect(width, height);
        if (valid(bitmap)) {
            lastMode = "screenshotAt";
            lastError = "";
            return software(bitmap);
        }
        bitmap = invokeFoundMethods(width, height);
        if (valid(bitmap)) {
            lastMode = lastMode.equals("none") ? "found" : lastMode;
            lastError = "";
            return software(bitmap);
        }
        bitmap = takeScreenshotAt(width, height);
        if (valid(bitmap)) {
            lastMode = "takeScreenshotAt";
            lastError = "";
            return software(bitmap);
        }
        bitmap = windowManagerScreenshot(width, height);
        if (valid(bitmap)) {
            lastMode = "IWindowManager.screenshot";
            lastError = "";
            return software(bitmap);
        }
        bitmap = execScreencap();
        if (valid(bitmap)) {
            lastMode = "screencap";
            lastError = "";
            return software(bitmap);
        }
        lastMode = "failed";
        if (lastError.isEmpty()) {
            lastError = "all_null";
        }
        return null;
    }

    private static Bitmap execScreencap() {
        return execScreencapProcess(new String[]{"/system/bin/screencap", "-p"});
    }

    private static Bitmap execScreencapProcess(String[] cmd) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(cmd);
            java.io.InputStream in = process.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(256 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            int code = process.waitFor();
            byte[] data = out.toByteArray();
            if (data.length < 32) {
                appendError("screencap:short:" + code);
                return null;
            }
            android.graphics.Bitmap decoded =
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
            if (decoded == null) {
                appendError("screencap:decode");
            }
            return decoded;
        } catch (Throwable e) {
            appendError("screencap:" + HiddenApi.rootName(e));
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static Bitmap captureDisplayModern(int width, int height) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            IBinder token = displayToken(sc);
            if (token == null) {
                appendError("displayToken:null");
                return null;
            }
            String[] hosts = {
                    "android.window.ScreenCapture",
                    "android.view.SurfaceControl",
            };
            Rect crop = new Rect(0, 0, width, height);
            for (int i = 0; i < hosts.length; ++i) {
                Bitmap bitmap = captureDisplayOn(hosts[i], token, crop, width, height);
                if (valid(bitmap)) {
                    return bitmap;
                }
            }
        } catch (Throwable e) {
            appendError("captureDisplay:" + HiddenApi.rootName(e));
        }
        return null;
    }

    private static Bitmap captureDisplayOn(String hostName, IBinder token, Rect crop,
                                           int width, int height) {
        try {
            Class<?> host = Class.forName(hostName);
            Class<?> argsClass = Class.forName(hostName + "$DisplayCaptureArgs");
            Class<?> builderClass = Class.forName(hostName + "$DisplayCaptureArgs$Builder");
            Object builder = builderClass.getConstructor(IBinder.class).newInstance(token);
            try {
                builderClass.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
            } catch (Throwable ignored) {
                // optional
            }
            try {
                builderClass.getMethod("setSize", int.class, int.class)
                        .invoke(builder, width, height);
            } catch (Throwable ignored) {
                // optional
            }
            Object args = builderClass.getMethod("build").invoke(builder);
            Method capture = HiddenApi.method(host, "captureDisplay", argsClass);
            Object result = capture.invoke(null, args);
            Bitmap bitmap = asBitmap(result);
            if (!valid(bitmap)) {
                appendError(host.getSimpleName() + ".captureDisplay:null");
            }
            return bitmap;
        } catch (Throwable e) {
            appendError(hostName.substring(hostName.lastIndexOf('.') + 1)
                    + ".captureDisplay:" + HiddenApi.rootName(e));
            return null;
        }
    }

    private static Bitmap invokeFoundMethods(int width, int height) {
        Bitmap bitmap;
        try {
            bitmap = invokeFoundOn(Class.forName("android.view.SurfaceControl"), null,
                    width, height);
            if (valid(bitmap)) {
                lastMode = "SurfaceControl.found";
                return bitmap;
            }
        } catch (Throwable e) {
            appendError("SurfaceControl.found:" + HiddenApi.rootName(e));
        }
        Object wm = windowManager();
        if (wm != null) {
            try {
                bitmap = invokeFoundOn(Class.forName("android.view.IWindowManager"), wm,
                        width, height);
                if (valid(bitmap)) {
                    lastMode = "IWindowManager.found";
                    return bitmap;
                }
            } catch (Throwable e) {
                appendError("IWindowManager.found:" + HiddenApi.rootName(e));
            }
            bitmap = invokeFoundOn(wm.getClass(), wm, width, height);
            if (valid(bitmap)) {
                lastMode = "WmProxy.found";
                return bitmap;
            }
        }
        return null;
    }

    private static Bitmap invokeFoundOn(Class<?> cls, Object instance, int width, int height) {
        java.util.List<Method> methods = HiddenApi.declaredMethods(cls);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < methods.size(); ++i) {
            Method method = methods.get(i);
            String name = method.getName();
            if (!isCaptureName(name)) {
                continue;
            }
            if (names.length() > 0) {
                names.append('+');
            }
            names.append(name).append(method.getParameterTypes().length);
            try {
                method.setAccessible(true);
                Object result = invokeWithSize(method, instance, width, height);
                Bitmap bitmap = asBitmap(result);
                if (valid(bitmap)) {
                    lastMode = cls.getSimpleName() + "." + name;
                    return bitmap;
                }
            } catch (Throwable e) {
                String root = HiddenApi.rootName(e);
                if (root.contains("Security")) {
                    appendError(name + ":" + root);
                }
            }
        }
        if (names.length() == 0) {
            appendError("found." + cls.getSimpleName() + "=none");
        } else {
            appendError("found." + cls.getSimpleName() + "=" + names);
        }
        return null;
    }

    private static boolean isCaptureName(String name) {
        return name.contains("creenshot") || name.contains("aptureDisplay")
                || name.contains("akeScreenshot");
    }

    private static Object invokeWithSize(Method method, Object instance, int width, int height)
            throws Exception {
        Class<?>[] types = method.getParameterTypes();
        if (types.length == 0) {
            return method.invoke(instance);
        }
        if (types.length == 2 && types[0] == int.class && types[1] == int.class) {
            return method.invoke(instance, width, height);
        }
        if (types.length == 4 && types[0] == int.class && types[1] == int.class
                && types[2] == int.class && types[3] == int.class) {
            return method.invoke(instance, 0, 0, width, height);
        }
        if (types.length == 4 && types[0] == Rect.class) {
            return method.invoke(instance, new Rect(0, 0, width, height), width, height, 0);
        }
        if (types.length == 1 && types[0] == int.class) {
            return method.invoke(instance, 0);
        }
        return null;
    }

    private static Bitmap screenshotWh(int width, int height) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Method method = HiddenApi.method(sc, "screenshot", int.class, int.class);
            Object result = method.invoke(null, width, height);
            Bitmap bitmap = asBitmap(result);
            if (!valid(bitmap)) {
                note("screenshot(w,h)", "null");
            }
            return bitmap;
        } catch (Throwable e) {
            note("screenshot(w,h)", e);
            return null;
        }
    }

    private static Bitmap screenshotRect(int width, int height) {
        Class<?> sc;
        try {
            sc = Class.forName("android.view.SurfaceControl");
        } catch (Throwable e) {
            note("SurfaceControl", e);
            return null;
        }
        Rect crop = new Rect(0, 0, width, height);
        try {
            Method method = HiddenApi.method(sc, "screenshot", Rect.class, int.class,
                    int.class, int.class);
            Object result = method.invoke(null, crop, width, height, 0);
            Bitmap bitmap = asBitmap(result);
            if (valid(bitmap)) {
                return bitmap;
            }
        } catch (Throwable e) {
            note("screenshot(rect,w,h,rot)", e);
        }
        try {
            Method method = HiddenApi.method(sc, "screenshot", Rect.class, int.class,
                    int.class, int.class, int.class, boolean.class, int.class);
            Object result = method.invoke(null, crop, width, height,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, false, 0);
            Bitmap bitmap = asBitmap(result);
            if (valid(bitmap)) {
                return bitmap;
            }
        } catch (Throwable e) {
            note("screenshot(layers)", e);
        }
        IBinder token = displayToken(sc);
        if (token != null) {
            try {
                Method method = HiddenApi.method(sc, "screenshotToBuffer", IBinder.class,
                        Rect.class, int.class, int.class, int.class, int.class,
                        boolean.class, int.class);
                Object result = method.invoke(null, token, crop, width, height,
                        Integer.MIN_VALUE, Integer.MAX_VALUE, false, 0);
                Bitmap bitmap = asBitmap(result);
                if (valid(bitmap)) {
                    return bitmap;
                }
            } catch (Throwable e) {
                note("screenshotToBuffer", e);
            }
        }
        return null;
    }

    private static Bitmap takeScreenshotAt(int width, int height) {
        Object wm = windowManager();
        if (wm == null) {
            return null;
        }
        try {
            Method method = HiddenApi.method(wm.getClass(), "takeScreenshotAt",
                    int.class, int.class, int.class, int.class);
            Object result = method.invoke(wm, 0, 0, width, height);
            Bitmap bitmap = asBitmap(result);
            if (valid(bitmap)) {
                return bitmap;
            }
            note("takeScreenshotAt", "null");
        } catch (Throwable e) {
            note("takeScreenshotAt", e);
        }
        return null;
    }

    private static Bitmap windowManagerScreenshot(int width, int height) {
        Object wm = windowManager();
        if (wm == null) {
            return null;
        }
        try {
            Method method = HiddenApi.method(wm.getClass(), "screenshot", int.class, int.class);
            Object result = method.invoke(wm, width, height);
            Bitmap bitmap = asBitmap(result);
            if (valid(bitmap)) {
                return bitmap;
            }
        } catch (Throwable e) {
            note("IWindowManager.screenshot_wh", e);
        }
        return null;
    }

    private static Object windowManager() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = HiddenApi.method(sm, "getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "window");
            if (binder == null) {
                note("ServiceManager.window", "null");
                return null;
            }
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            Method asInterface = HiddenApi.method(stub, "asInterface", IBinder.class);
            return asInterface.invoke(null, binder);
        } catch (Throwable e) {
            note("IWindowManager", e);
            return null;
        }
    }

    static IBinder displayToken(Class<?> sc) {
        IBinder token = invokeToken(sc, "getInternalDisplayToken");
        if (token != null) {
            return token;
        }
        try {
            Method method = HiddenApi.method(sc, "getBuiltInDisplay", int.class);
            Object result = method.invoke(null, 0);
            if (result instanceof IBinder) {
                return (IBinder) result;
            }
        } catch (Throwable ignored) {
        }
        token = physicalDisplayToken(sc);
        if (token != null) {
            return token;
        }
        token = displayTokenFromDisplay(sc);
        if (token != null) {
            return token;
        }
        token = displayTokenFromManager(sc);
        if (token != null) {
            return token;
        }
        return null;
    }

    private static IBinder invokeToken(Class<?> sc, String name) {
        try {
            Method method = HiddenApi.method(sc, name);
            Object token = method.invoke(null);
            if (token instanceof IBinder) {
                return (IBinder) token;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static IBinder physicalDisplayToken(Class<?> sc) {
        try {
            Method ids = HiddenApi.method(sc, "getPhysicalDisplayIds");
            Object raw = ids.invoke(null);
            long[] physical = null;
            if (raw instanceof long[]) {
                physical = (long[]) raw;
            }
            if (physical != null && physical.length > 0) {
                Method token = HiddenApi.method(sc, "getPhysicalDisplayToken", long.class);
                Object result = token.invoke(null, physical[0]);
                if (result instanceof IBinder) {
                    return (IBinder) result;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static IBinder displayTokenFromDisplay(Class<?> sc) {
        Context ctx = app;
        if (ctx == null) {
            return null;
        }
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return null;
            }
            android.view.Display display = wm.getDefaultDisplay();
            String[] fields = {"mDisplayToken", "mToken", "mGlobalDisplayToken"};
            for (int i = 0; i < fields.length; ++i) {
                try {
                    java.lang.reflect.Field field = android.view.Display.class.getDeclaredField(fields[i]);
                    field.setAccessible(true);
                    Object value = field.get(display);
                    if (value instanceof IBinder) {
                        return (IBinder) value;
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                Method getAddress = android.view.Display.class.getMethod("getAddress");
                Object address = getAddress.invoke(display);
                if (address != null) {
                    Method physId = address.getClass().getMethod("getPhysicalDisplayId");
                    long id = ((Number) physId.invoke(address)).longValue();
                    Method token = HiddenApi.method(sc, "getPhysicalDisplayToken", long.class);
                    Object result = token.invoke(null, id);
                    if (result instanceof IBinder) {
                        return (IBinder) result;
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable e) {
        }
        return null;
    }

    private static IBinder displayTokenFromManager(Class<?> sc) {
        try {
            Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstance = HiddenApi.method(dmgClass, "getInstance");
            Object dmg = getInstance.invoke(null);
            try {
                Method ids = HiddenApi.method(dmg.getClass(), "getPhysicalDisplayIds");
                Object raw = ids.invoke(dmg);
                if (raw instanceof long[]) {
                    long[] physical = (long[]) raw;
                    if (physical.length > 0) {
                        Method token = HiddenApi.method(sc, "getPhysicalDisplayToken", long.class);
                        Object result = token.invoke(null, physical[0]);
                        if (result instanceof IBinder) {
                            return (IBinder) result;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable e) {
        }
        return null;
    }

    static int layerStack() {
        try {
            Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstance = HiddenApi.method(dmgClass, "getInstance");
            Object dmg = getInstance.invoke(null);
            Method getInfo = HiddenApi.method(dmg.getClass(), "getDisplayInfo", int.class);
            Object info = getInfo.invoke(dmg, 0);
            if (info != null) {
                return info.getClass().getField("layerStack").getInt(info);
            }
        } catch (Throwable e) {
            note("layerStack", e);
        }
        return 0;
    }

    private static Bitmap asBitmap(Object result) {
        if (result instanceof Bitmap) {
            return (Bitmap) result;
        }
        if (result == null) {
            return null;
        }
        try {
            Method asBitmap = result.getClass().getMethod("asBitmap");
            Object bitmap = asBitmap.invoke(result);
            if (bitmap instanceof Bitmap) {
                return (Bitmap) bitmap;
            }
        } catch (Throwable ignored) {
            // not a buffer wrapper
        }
        return null;
    }

    private static Bitmap software(Bitmap src) {
        if (src.getConfig() == Bitmap.Config.ARGB_8888 && !src.isRecycled()) {
            return src;
        }
        Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, false);
        if (copy != src) {
            src.recycle();
        }
        return copy;
    }

    private static boolean valid(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled()
                && bitmap.getWidth() > 1 && bitmap.getHeight() > 1;
    }

    private static void note(String step, Throwable e) {
        appendError(step + ":" + HiddenApi.rootName(e));
    }

    private static void note(String step, String detail) {
        appendError(step + ":" + detail);
    }

    static int[] screenSize(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{Math.max(2, metrics.widthPixels), Math.max(2, metrics.heightPixels)};
    }
}
