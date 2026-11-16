package com.genymobile.scrcpy.agent;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;

/**
 * Pointer position used to be painted as a circle on the phone and into the
 * captured frame. Nothing is drawn now: the desktop's own mouse is the pointer.
 */
final class RemoteCursor {

    static final class Spot {
        final boolean visible;
        final float x;
        final float y;
        final int screenW;
        final int screenH;
        final int seq;

        Spot(boolean visible, float x, float y, int screenW, int screenH, int seq) {
            this.visible = visible;
            this.x = x;
            this.y = y;
            this.screenW = screenW;
            this.screenH = screenH;
            this.seq = seq;
        }
    }

    private static final Spot HIDDEN = new Spot(false, 0f, 0f, 1, 1, 0);

    private RemoteCursor() {
    }

    static Spot spot() {
        return HIDDEN;
    }

    static void move(AccessibilityService service, float x, float y) {
    }

    static void hide(AccessibilityService service) {
    }

    /** No-op. The captured frame is left untouched. */
    static void drawOn(Bitmap bitmap) {
    }
}
