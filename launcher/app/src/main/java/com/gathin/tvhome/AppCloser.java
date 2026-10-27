package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Closes apps for the app switcher. Since the 2024 security updates Android ignores killBackgroundProcesses() for
 * another app's processes unless the caller is a system app or holds FORCE_STOP_PACKAGES, which cannot be granted
 * to the launcher. What remains is what a person does: the app's page in the TV settings, "Force stop", "OK". The
 * remote's key helper (InputKeyService, an accessibility service with canRetrieveWindowContent) presses those two
 * buttons itself, under an overlay that looks like the switcher, so all one sees is the card flying away. The
 * buttons are found by the TV settings' own strings, so it works in any language.
 */
final class AppCloser {
    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final long STEP_TIMEOUT_MS = 5000;
    private static final long POLL_MS = 120;

    interface Done { void done(List<String> failed); }

    private static AccessibilityService service;

    static void attach(AccessibilityService s) {
        service = s;
    }

    static void detach(AccessibilityService s) {
        if (service == s) service = null;
    }

    static boolean available() {
        return service != null;
    }

    private final AccessibilityService host;
    private final List<String> pkgs;
    private final List<String> labels;
    private final Done done;
    /** The TV settings were open before: they stay listed in the switcher afterwards. */
    private final boolean settingsWasOpen;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> failed = new ArrayList<>();
    private final String forceStopText, okText;
    private View overlay;
    private TextView message;
    private int index;
    private int stage;           // 0: waiting for "Force stop", 1: waiting for "OK", 2: done with this app
    private long stageStarted;

    /** Closes the apps one after the other; false when the key helper is not running (nothing was done). */
    static boolean close(List<String> pkgs, List<String> labels, boolean settingsWasOpen, Done done) {
        AccessibilityService s = service;
        if (s == null || pkgs.isEmpty()) return false;
        new AppCloser(s, pkgs, labels, settingsWasOpen, done).start();
        return true;
    }

    private AppCloser(AccessibilityService host, List<String> pkgs, List<String> labels, boolean settingsWasOpen,
                      Done done) {
        this.settingsWasOpen = settingsWasOpen;
        this.host = host;
        this.pkgs = pkgs;
        this.labels = labels;
        this.done = done;
        forceStopText = settingsString("device_apps_app_management_force_stop", "Force stop");
        okText = host.getString(android.R.string.ok);
    }

    private String settingsString(String name, String fallback) {
        try {
            Resources res = host.getPackageManager().getResourcesForApplication(TV_SETTINGS);
            int id = res.getIdentifier(name, "string", TV_SETTINGS);
            if (id != 0) return res.getString(id);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
        }
        return fallback;
    }

    private void start() {
        showOverlay();
        next();
    }

    private void next() {
        if (index >= pkgs.size()) {
            finish();
            return;
        }
        String pkg = pkgs.get(index);
        message.setText(pkgs.size() == 1 ? host.getString(R.string.recents_closing, labels.get(index))
                : host.getString(R.string.recents_closing_n, index + 1, pkgs.size()));
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            host.startActivity(details);
        } catch (ActivityNotFoundException | SecurityException e) {
            failed.add(labels.get(index));
            index++;
            next();
            return;
        }
        stage = 0;
        stageStarted = SystemClock.uptimeMillis();
        ui.postDelayed(this::poll, 250);
    }

    private void poll() {
        if (SystemClock.uptimeMillis() - stageStarted > STEP_TIMEOUT_MS) {
            failed.add(labels.get(index));
            index++;
            next();
            return;
        }
        AccessibilityNodeInfo root;
        try {
            root = host.getRootInActiveWindow();
        } catch (RuntimeException e) {
            root = null;
        }
        if (root != null && TV_SETTINGS.contentEquals(String.valueOf(root.getPackageName()))) {
            String wanted = stage == 0 ? forceStopText : okText;
            if (click(root, wanted)) {
                stage++;
                stageStarted = SystemClock.uptimeMillis();
                if (stage == 2) {
                    // Closed: leave the settings, so they are neither left open nor listed in the switcher.
                    index++;
                    ui.postDelayed(() -> leaveSettings(4, this::next), 300);
                    return;
                }
            }
        }
        ui.postDelayed(this::poll, POLL_MS);
    }

    /** Back until the TV settings are no longer in front (at most {@code tries} times), then {@code then}. */
    private void leaveSettings(int tries, Runnable then) {
        AccessibilityNodeInfo root;
        try {
            root = host.getRootInActiveWindow();
        } catch (RuntimeException e) {
            root = null;
        }
        boolean inSettings = root != null && TV_SETTINGS.contentEquals(String.valueOf(root.getPackageName()));
        if (!inSettings || tries <= 0) {
            then.run();
            return;
        }
        host.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        ui.postDelayed(() -> leaveSettings(tries - 1, then), 220);
    }

    /** Clicks the first enabled node whose text is exactly {@code text} (or its clickable parent). */
    private static boolean click(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : found) {
            CharSequence t = node.getText();
            if (t == null || !text.contentEquals(t.toString().trim())) continue;
            AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null && target.isEnabled() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }
        return false;
    }

    private void finish() {
        // The switcher again, underneath, then the overlay fades to show it.
        try {
            host.startActivity(new Intent(host, RecentsActivity.class).putExtra(RecentsActivity.EXTRA_AFTER_CLOSE, true)
                    .putExtra(RecentsActivity.EXTRA_HIDE, settingsWasOpen ? null : TV_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException ignored) {
        }
        ui.postDelayed(() -> {
            if (overlay != null) {
                overlay.animate().alpha(0f).setDuration(220).withEndAction(this::removeOverlay).start();
            }
            if (done != null) done.done(failed);
        }, 450);
    }

    private void showOverlay() {
        float density = host.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(host);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF050508, 0xFF0B0B12, 0xFF050508}));
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        // The dot pulses to 1.6 times its size: nothing around it may clip it.
        root.setClipChildren(false);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        View dot = new View(host);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFFFFFFF);
        dot.setBackground(circle);
        int size = Math.round(12 * density);
        box.addView(dot, new LinearLayout.LayoutParams(size, size));
        message = new TextView(host);
        message.setTextColor(0xCCFFFFFF);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        Typeface light = UiFont.get(host, R.string.font_light);
        if (light != null) message.setTypeface(light);
        message.setPadding(0, Math.round(18 * density), 0, 0);
        message.setGravity(Gravity.CENTER_HORIZONTAL);
        // Full width: the text changes after the overlay is laid out, and must never be cut to its first letter.
        box.addView(message, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // A soft pulse, so a slow settings app never looks frozen.
        dot.animate().scaleX(1.6f).scaleY(1.6f).alpha(0.3f).setDuration(600).withEndAction(new Runnable() {
            boolean out = true;

            @Override
            public void run() {
                out = !out;
                if (overlay == null) return;
                dot.animate().scaleX(out ? 1.6f : 1f).scaleY(out ? 1.6f : 1f).alpha(out ? 0.3f : 1f)
                        .setDuration(600).withEndAction(this).start();
            }
        }).start();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            host.getSystemService(WindowManager.class).addView(root, lp);
            overlay = root;
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(120).start();
        } catch (RuntimeException e) {
            overlay = null;
            message = new TextView(host);   // keeps next() simple when there is no overlay
        }
    }

    private void removeOverlay() {
        if (overlay == null) return;
        try {
            host.getSystemService(WindowManager.class).removeView(overlay);
        } catch (RuntimeException ignored) {
        }
        overlay = null;
    }
}
