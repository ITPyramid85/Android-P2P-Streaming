package com.screencapture.app.screencapture;

import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;

/**
 * Relaxes hidden-API checks so SurfaceControl / IWindowManager screenshot
 * methods can be invoked from a normal installed APK (no ADB).
 */
public final class HiddenApi {

    private static boolean done;

    private HiddenApi() {
    }

    public static void unseal() {
        if (done) {
            return;
        }
        done = true;
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        try {
            HiddenApiBypass.addHiddenApiExemptions("L");
        } catch (Throwable e) {
        }
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", null);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(vmRuntime,
                    "setHiddenApiExemptions", new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
        } catch (Throwable e) {
        }
    }

    static Method method(Class<?> cls, String name, Class<?>... params) throws Exception {
        try {
            return HiddenApiBypass.getDeclaredMethod(cls, name, params);
        } catch (Throwable e) {
            Method method = cls.getDeclaredMethod(name, params);
            method.setAccessible(true);
            return method;
        }
    }

    @SuppressWarnings("unchecked")
    static java.util.List<Method> declaredMethods(Class<?> cls) {
        java.util.ArrayList<Method> out = new java.util.ArrayList<Method>();
        try {
            Object result = HiddenApiBypass.class
                    .getMethod("getDeclaredMethods", Class.class)
                    .invoke(null, cls);
            if (result instanceof java.util.List) {
                for (Object item : (java.util.List<?>) result) {
                    if (item instanceof Method) {
                        out.add((Method) item);
                    }
                }
            }
        } catch (Throwable e) {
        }
        if (out.isEmpty()) {
            for (Method method : cls.getDeclaredMethods()) {
                out.add(method);
            }
        }
        return out;
    }

    static String rootName(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
