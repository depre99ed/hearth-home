package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app switcher, opened by Home pressed twice (home_double_tap): the apps open right now (RunningApps), as
 * cards in a carousel, with the picture each one had when it was left (Thumbnails) or its banner. Left and Right
 * pick, OK goes back to the app where it was, Up closes it (the card flies away), Down then OK closes them all,
 * Back leaves. Only apps that are really open are listed, never ones closed or killed since.
 */
public class RecentsActivity extends Activity {

    /** Set by AppCloser when it brings the switcher back after closing apps: no entrance, the overlay fades. */
    static final String EXTRA_AFTER_CLOSE = "after_close";
    /** A package to leave out (the TV settings AppCloser just used, while Android is still closing them). */
    static final String EXTRA_HIDE = "hide";
    private static final long MOVE_MS = 260;

    private static final class Item {
        final RunningApps.Task task;
        final String label;
        View view;

        Item(RunningApps.Task task, String label) {
            this.task = task;
            this.label = label;
        }
    }

    /** On screen right now: Home pressed then goes Home instead of counting towards a double press. */
    static volatile boolean showing;

    private final List<Item> items = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout stage;
    private View scrim;
    private TextView title, count, empty, closeAll;
    private LinearLayout hints;
    private int selected;
    private boolean closeAllFocused;
    private boolean busy;   // an opening or closing animation is running: keys wait
    private boolean loaded;
    private float density;
    private int cardW, cardH;
    private Typeface light, medium, body;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        light = UiFont.get(this, R.string.font_light);
        medium = UiFont.get(this, R.string.font_medium);
        body = UiFont.get(this, R.string.font_body);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        cardW = Math.round(screenW * 0.40f);
        cardH = Math.round(cardW * 9f / 16f);
        setContentView(buildLayout());
        boolean afterClose = getIntent().getBooleanExtra(EXTRA_AFTER_CLOSE, false);
        if (!afterClose) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(220).start();
        }
        load(!afterClose);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!busy) load(false);   // Home twice again while open: show what is open now
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        showing = false;
        // Home's window is translucent: the switcher would stay visible under it, and never stop (noHistory waits
        // for that). Leaving it in any way, Home, an app, the settings AppCloser drives, closes it.
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---- layout ---------------------------------------------------------------------------------------

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        scrim = new View(this);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFA050508, 0xF50B0B12, 0xFA050508});
        scrim.setBackground(shade);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(56), dp(36), dp(56), 0);
        title = text(getString(R.string.recents_title), 30, light, 0xFFFFFFFF);
        count = text("", 13, body, 0x99FFFFFF);
        count.setLetterSpacing(0.08f);
        count.setPadding(dp(2), dp(2), 0, 0);
        header.addView(title);
        header.addView(count);
        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                cardH + dp(70), Gravity.CENTER_VERTICAL);
        slp.topMargin = -dp(12);
        root.addView(stage, slp);

        empty = text(getString(R.string.recents_empty), 22, light, 0xDDFFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        closeAll = text(getString(R.string.recents_close_all), 15, medium, 0xFFFFFFFF);
        closeAll.setGravity(Gravity.CENTER);
        closeAll.setPadding(dp(22), dp(9), dp(22), dp(9));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        clp.bottomMargin = dp(86);
        root.addView(closeAll, clp);
        styleCloseAll(false);

        hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER);
        addHint("◀ ▶", R.string.recents_hint_pick);
        addHint("OK", R.string.recents_hint_open);
        addHint("▲", R.string.recents_hint_close);
        addHint("▼", R.string.recents_hint_close_all);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        hlp.bottomMargin = dp(34);
        root.addView(hints, hlp);
        return root;
    }

    private void addHint(String key, int label) {
        TextView k = text(key, 12, medium, 0xFFFFFFFF);
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(6));
        chip.setColor(0x26FFFFFF);
        k.setBackground(chip);
        k.setPadding(dp(8), dp(3), dp(8), dp(3));
        TextView l = text(getString(label), 13, body, 0xA6FFFFFF);
        l.setPadding(dp(8), 0, dp(22), 0);
        hints.addView(k);
        hints.addView(l);
    }

    private void styleCloseAll(boolean focused) {
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(22));
        pill.setColor(focused ? 0xFFFFFFFF : 0x1FFFFFFF);
        pill.setStroke(dp(1), focused ? 0xFFFFFFFF : 0x33FFFFFF);
        closeAll.setBackground(pill);
        closeAll.setTextColor(focused ? 0xFF111114 : 0xD9FFFFFF);
        closeAll.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(160).start();
    }

    /** One card: the picture (or banner, or icon) in a rounded frame, the app's icon and name under it. */
    private View card(Item item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);

        FrameLayout frame = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2A2A31, 0xFF16161B});
        bg.setCornerRadius(dp(18));
        frame.setBackground(bg);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        frame.setClipToOutline(true);
        frame.setElevation(dp(10));
        PackageManager pm = getPackageManager();
        ImageView picture = new ImageView(this);
        Bitmap shot = Thumbnails.get(item.task.pkg);
        Drawable banner = null;
        if (shot == null) {
            try {
                banner = pm.getApplicationBanner(item.task.pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (shot != null) {
            picture.setImageBitmap(shot);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (banner != null) {
            picture.setImageDrawable(banner);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            picture.setImageDrawable(icon(item.task.pkg));
            frame.addView(picture, new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER));
        }
        // The ring that marks the chosen card.
        View ring = new View(this);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setCornerRadius(dp(18));
        stroke.setStroke(dp(3), Color.WHITE);
        ring.setBackground(stroke);
        ring.setTag("ring");
        ring.setAlpha(0f);
        frame.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(frame, new LinearLayout.LayoutParams(cardW, cardH));

        LinearLayout label = new LinearLayout(this);
        label.setOrientation(LinearLayout.HORIZONTAL);
        label.setGravity(Gravity.CENTER_VERTICAL);
        ImageView small = new ImageView(this);
        small.setImageDrawable(icon(item.task.pkg));
        label.addView(small, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView name = text(item.label, 18, medium, 0xFFFFFFFF);
        name.setSingleLine(true);
        name.setPadding(dp(10), 0, 0, 0);
        label.addView(name);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(18);
        box.addView(label, llp);
        return box;
    }

    private Drawable icon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return getPackageManager().getDefaultActivityIcon();
        }
    }

    // ---- data -----------------------------------------------------------------------------------------

    /** Reads the open apps off the main thread, then lays the cards out (with the entrance the first time). */
    private void load(boolean entrance) {
        worker.execute(() -> {
            String recents = RunningApps.dumpsys("activity", "recents");
            String lru = RunningApps.dumpsys("activity", "lru");
            PackageManager pm = getPackageManager();
            String hide = getIntent().getStringExtra(EXTRA_HIDE);
            List<RunningApps.Task> open = RunningApps.open(recents, lru, getPackageName(),
                    pkg -> !pkg.equals(hide) && (pm.getLeanbackLaunchIntentForPackage(pkg) != null
                            || pm.getLaunchIntentForPackage(pkg) != null));
            List<Item> fresh = new ArrayList<>();
            for (RunningApps.Task t : open) fresh.add(new Item(t, label(t.pkg)));
            boolean denied = recents.startsWith("Permission Denial");
            ui.post(() -> {
                if (isFinishing()) return;
                show(fresh, entrance);
                if (denied) count.setText(R.string.recents_no_permission);
            });
        });
    }

    private String label(String pkg) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void show(List<Item> fresh, boolean entrance) {
        String keep = items.isEmpty() ? null : items.get(selected).task.pkg;
        stage.removeAllViews();
        items.clear();
        items.addAll(fresh);
        selected = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).task.pkg.equals(keep)) selected = i;
        for (Item item : items) {
            item.view = card(item);
            stage.addView(item.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        loaded = true;
        updateCount();
        if (items.isEmpty() && getIntent().getBooleanExtra(EXTRA_AFTER_CLOSE, false)) {
            // Everything closed: show "No app is open" a moment, then back to Home, as a phone does.
            ui.postDelayed(() -> {
                if (!isFinishing() && items.isEmpty() && !busy) leave();
            }, 1100);
        }
        layout(false);
        if (entrance) {
            for (int i = 0; i < items.size(); i++) {
                View v = items.get(i).view;
                float x = v.getTranslationX();
                v.setAlpha(0f);
                v.setTranslationY(dp(46));
                v.setTranslationX(x + dp(40));
                v.animate().alpha(alphaFor(i)).translationY(0f).translationX(x).setStartDelay(50L * Math.abs(i - selected))
                        .setDuration(380).setInterpolator(new DecelerateInterpolator(2.2f)).start();
            }
        }
    }

    private void updateCount() {
        boolean none = items.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        closeAll.setVisibility(none ? View.GONE : View.VISIBLE);
        hints.setVisibility(none ? View.GONE : View.VISIBLE);
        count.setText(none ? getString(R.string.recents_empty_hint)
                : getResources().getQuantityString(R.plurals.recents_count, items.size(), items.size()));
        if (none) closeAllFocused = false;
    }

    // ---- layout of the carousel --------------------------------------------------------------------------

    private float alphaFor(int i) {
        int d = Math.abs(i - selected);
        return d == 0 ? 1f : d == 1 ? 0.55f : 0.22f;
    }

    /** Places every card around the chosen one: it in the middle, full size, the others smaller and dimmer. */
    private void layout(boolean animated) {
        float gap = dp(40);
        for (int i = 0; i < items.size(); i++) {
            View v = items.get(i).view;
            int d = i - selected;
            // The chosen card full size in the middle; the others at 80 %, a clear gap between all of them.
            float x = d == 0 ? 0f : Math.signum(d) * (cardW * 0.9f + gap + (Math.abs(d) - 1) * (cardW * 0.8f + gap));
            float scale = d == 0 ? 1f : 0.80f;
            float alpha = alphaFor(i);
            View ring = v.findViewWithTag("ring");
            float ringAlpha = d == 0 && !closeAllFocused ? 1f : 0f;
            v.setZ(d == 0 ? 2f : 0f);
            if (animated) {
                v.animate().translationX(x).translationY(0f).scaleX(scale).scaleY(scale).alpha(alpha).setStartDelay(0)
                        .setDuration(MOVE_MS).setInterpolator(new DecelerateInterpolator(2f)).start();
                if (ring != null) ring.animate().alpha(ringAlpha).setDuration(180).start();
            } else {
                v.setTranslationX(x);
                v.setScaleX(scale);
                v.setScaleY(scale);
                v.setAlpha(alpha);
                if (ring != null) ring.setAlpha(ringAlpha);
            }
        }
    }

    // ---- keys -----------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP && !busy) leave();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        if (busy || !loaded) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!closeAllFocused) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!closeAllFocused) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (closeAllFocused) focusCloseAll(false);
                else if (event.getRepeatCount() == 0) closeSelected();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!closeAllFocused && !items.isEmpty()) focusCloseAll(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() != 0) return true;
                if (closeAllFocused) closeEverything();
                else openSelected();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void move(int step) {
        int next = selected + step;
        if (next < 0 || next >= items.size()) {
            // At an end: a small push that springs back says there is nothing further.
            if (items.isEmpty()) return;
            View v = items.get(selected).view;
            v.animate().translationX(v.getTranslationX() + dp(14) * step).setDuration(90)
                    .withEndAction(() -> layout(true)).start();
            return;
        }
        selected = next;
        layout(true);
    }

    private void focusCloseAll(boolean focus) {
        closeAllFocused = focus;
        styleCloseAll(focus);
        layout(true);
    }

    // ---- actions --------------------------------------------------------------------------------------

    /** Back to the chosen app where it was: its card grows towards the viewer as the rest fades. */
    private void openSelected() {
        if (items.isEmpty()) return;
        busy = true;
        Item item = items.get(selected);
        for (Item other : items) if (other != item) other.view.animate().alpha(0f).setDuration(160).start();
        hints.animate().alpha(0f).setDuration(120).start();
        closeAll.animate().alpha(0f).setDuration(120).start();
        item.view.animate().scaleX(1.18f).scaleY(1.18f).setDuration(200).setInterpolator(new AccelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    bringToFront(item.task);
                    finish();
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                }).start();
    }

    private void bringToFront(RunningApps.Task task) {
        ActivityManager am = getSystemService(ActivityManager.class);
        if (task.live) {
            try {
                am.moveTaskToFront(task.id, 0);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        // No window left (its process still runs): start it as its tile would.
        PackageManager pm = getPackageManager();
        Intent open = pm.getLeanbackLaunchIntentForPackage(task.pkg);
        if (open == null) open = pm.getLaunchIntentForPackage(task.pkg);
        if (open == null) return;
        try {
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** The chosen card flies up and away, the others close the gap, and the app is closed. */
    private void closeSelected() {
        if (items.isEmpty()) return;
        busy = true;
        Item item = items.get(selected);
        item.view.animate().translationY(-cardH * 1.7f).alpha(0f).setDuration(240)
                .setInterpolator(new AccelerateInterpolator(1.6f)).withEndAction(() -> {
                    stage.removeView(item.view);
                    items.remove(item);
                    if (selected >= items.size()) selected = Math.max(0, items.size() - 1);
                    updateCount();
                    layout(true);
                    busy = false;
                    kill(java.util.Collections.singletonList(item));
                }).start();
    }

    private void closeEverything() {
        busy = true;
        List<Item> all = new ArrayList<>(items);
        for (int i = 0; i < all.size(); i++) {
            View v = all.get(i).view;
            v.animate().translationY(-cardH * 1.7f).alpha(0f).setStartDelay(45L * Math.abs(i - selected))
                    .setDuration(240).setInterpolator(new AccelerateInterpolator(1.6f)).start();
        }
        ui.postDelayed(() -> {
            stage.removeAllViews();
            items.clear();
            selected = 0;
            focusCloseAll(false);
            updateCount();
            busy = false;
            kill(all);
        }, 300 + 45L * all.size());
    }

    /**
     * Closes the apps as Android lets an app do it: their processes are killed once in the background, which they
     * are, the switcher being in front. One that keeps a foreground service (music still playing, a download) is
     * kept alive by Android; it comes back in the list with a word saying so.
     */
    private void kill(List<Item> gone) {
        List<String> pkgs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Item item : gone) {
            Thumbnails.forget(item.task.pkg);
            pkgs.add(item.task.pkg);
            names.add(item.label);
        }
        // The real way, through the key helper (see AppCloser); it reopens this switcher when it is done.
        boolean settingsOpen = false;
        for (Item item : items) if ("com.android.tv.settings".equals(item.task.pkg)) settingsOpen = true;
        if (AppCloser.close(pkgs, names, settingsOpen, failed -> {
            if (!failed.isEmpty()) {
                Toast.makeText(getApplicationContext(), getString(R.string.recents_close_failed,
                        String.join(", ", failed)), Toast.LENGTH_LONG).show();
            }
        })) return;
        // Without the key helper: what Android still allows (enough before the 2024 security updates).
        ActivityManager am = getSystemService(ActivityManager.class);
        for (String pkg : pkgs) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (RuntimeException ignored) {
            }
        }
        ui.postDelayed(() -> worker.execute(() -> {
            String recents = RunningApps.dumpsys("activity", "recents");
            String lru = RunningApps.dumpsys("activity", "lru");
            List<String> survivors = new ArrayList<>();
            for (RunningApps.Task t : RunningApps.open(recents, lru, getPackageName(), pkg -> true)) {
                for (Item item : gone) if (item.task.pkg.equals(t.pkg)) survivors.add(item.label);
            }
            if (survivors.isEmpty()) return;
            ui.post(() -> {
                if (isFinishing()) return;
                Toast.makeText(this, getString(R.string.recents_kept, String.join(", ", survivors)),
                        Toast.LENGTH_LONG).show();
                load(false);
            });
        }), 900);
    }

    private void leave() {
        busy = true;
        for (Item item : items) item.view.animate().alpha(0f).translationY(dp(30)).setDuration(170).start();
        hints.animate().alpha(0f).setDuration(150).start();
        scrim.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private TextView text(String s, int sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (face != null) t.setTypeface(face);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
