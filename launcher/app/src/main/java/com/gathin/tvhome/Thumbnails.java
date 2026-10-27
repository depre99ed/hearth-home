package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The picture of each open app for the app switcher. Android gives other apps' task snapshots only to the system,
 * so the remote's key helper (InputKeyService) takes a screenshot (Android 11, canTakeScreenshot) when Home is
 * pressed in an app, before Home comes up, and files it under that app. Protected video (Netflix, Prime and other
 * DRM) comes out black: such a picture is dropped and the switcher shows the app's banner instead.
 * Kept in memory only, small (RGB_565, 640 px wide) and few.
 */
final class Thumbnails {
    private Thumbnails() {}

    private static final int MAX = 10;
    private static final int WIDTH = 640;
    private static final Executor WORKER = Executors.newSingleThreadExecutor();
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > MAX;
        }
    };

    static synchronized Bitmap get(String pkg) {
        return CACHE.get(pkg);
    }

    static synchronized void forget(String pkg) {
        CACHE.remove(pkg);
    }

    private static synchronized void put(String pkg, Bitmap bitmap) {
        if (bitmap == null) CACHE.remove(pkg);
        else CACHE.put(pkg, bitmap);
    }

    /** Captures the screen and files it under the app on top, if the top is an app and not Home. */
    @TargetApi(Build.VERSION_CODES.R)
    static void capture(AccessibilityService service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        // Which app is on screen: read now, while Home has not come up yet (it does on the key's release).
        final String[] top = {null};
        Thread who = new Thread(() -> top[0] = topApp(service.getPackageName()));
        who.start();
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, WORKER, new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    Bitmap small = null;
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hw != null) {
                            small = scale(hw);
                            hw.recycle();
                        }
                    } catch (RuntimeException ignored) {
                    }
                    try {
                        who.join(1500);
                    } catch (InterruptedException ignored) {
                    }
                    if (top[0] != null) put(top[0], small == null || mostlyBlack(small) ? null : small);
                }

                @Override
                public void onFailure(int errorCode) {
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    /** The package of the app task on top of recents, or null when Home or one of our own screens is on top. */
    static String topApp(String self) {
        String dump = RunningApps.dumpsys("activity", "recents");
        int first = dump.indexOf("* Recent #0:");
        if (first < 0) return null;
        String head = dump.substring(first, Math.min(dump.length(), first + 400));
        if (!head.contains("type=standard")) return null;
        List<RunningApps.Task> tasks = RunningApps.parseRecents(dump);
        if (tasks.isEmpty()) return null;
        String pkg = tasks.get(0).pkg;
        // parseRecents skips dead tasks; make sure the first live one is really task #0.
        if (!head.contains("#" + tasks.get(0).id + " ")) return null;
        return pkg.equals(self) ? null : pkg;
    }

    private static Bitmap scale(Bitmap hw) {
        int w = WIDTH;
        int h = Math.max(1, Math.round(hw.getHeight() * (w / (float) hw.getWidth())));
        Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
        if (soft == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        new Canvas(out).drawBitmap(soft, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        soft.recycle();
        return out;
    }

    /** A protected surface or a blank frame: almost every sampled pixel close to black. */
    static boolean mostlyBlack(Bitmap b) {
        int dark = 0, total = 0;
        for (int y = 2; y < b.getHeight(); y += b.getHeight() / 12 + 1) {
            for (int x = 2; x < b.getWidth(); x += b.getWidth() / 16 + 1) {
                int c = b.getPixel(x, y);
                int lum = ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
                if (lum < 36) dark++;
                total++;
            }
        }
        return total > 0 && dark * 100 >= total * 97;
    }
}
