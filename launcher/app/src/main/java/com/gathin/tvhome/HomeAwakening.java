package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Home's opening and its return (Animations on, reduced motion off).
 *
 * The first time Home shows in a process (after a boot, mostly) it wakes up: the backdrop emerges from black,
 * drifting back from a little closer; a soft band of light sweeps across once; the clock rises into place while the
 * greeting tightens its letters; the top buttons drop in; the tiles spring up one after the other. Coming back from
 * an app later it only settles in, briefly, so it never gets tiring.
 */
final class HomeAwakening {
    private static boolean sWoken;

    private HomeAwakening() {}

    /** Set the opening's first frame now (before Home is drawn) and run it from the first frame on. */
    static void wake(ViewGroup root, View sky, View leftBlock, TextView greeting, View topButtons, RecyclerView grid,
                     View... withTiles) {
        if (sWoken) return;
        sWoken = true;
        Context c = root.getContext();
        float dp = c.getResources().getDisplayMetrics().density;
        DecelerateInterpolator slow = new DecelerateInterpolator(2.4f);

        View black = new View(c);
        black.setBackgroundColor(0xFF000000);
        root.addView(black, new FrameLayout.LayoutParams(-1, -1));
        Sweep sweep = new Sweep(c);
        root.addView(sweep, new FrameLayout.LayoutParams(-1, -1));

        if (sky != null) {
            sky.setScaleX(1.12f);
            sky.setScaleY(1.12f);
        }
        leftBlock.setAlpha(0f);
        leftBlock.setTranslationY(36 * dp);
        topButtons.setAlpha(0f);
        topButtons.setTranslationY(-22 * dp);
        grid.setAlpha(0f);
        // The tiles get their own spring entrance here instead of the usual cascade.
        LayoutAnimationController usual = grid.getLayoutAnimation();
        grid.setLayoutAnimation(null);
        float spacing = greeting.getLetterSpacing();

        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                black.animate().alpha(0f).setDuration(650).setInterpolator(slow)
                        .withEndAction(() -> root.removeView(black)).start();
                if (sky != null) sky.animate().scaleX(1f).scaleY(1f).setDuration(1500).setInterpolator(slow).start();
                sweep.run(root, 380, 1250);

                leftBlock.animate().alpha(1f).translationY(0f).setStartDelay(420).setDuration(820).setInterpolator(slow).start();
                ValueAnimator letters = ValueAnimator.ofFloat(spacing + 0.45f, spacing).setDuration(1100);
                letters.setStartDelay(420);
                letters.setInterpolator(slow);
                letters.addUpdateListener(a -> greeting.setLetterSpacing((float) a.getAnimatedValue()));
                letters.start();

                topButtons.animate().alpha(1f).translationY(0f).setStartDelay(620).setDuration(640).setInterpolator(slow).start();

                if (grid.getChildCount() == 0) {
                    // The tiles are not laid out yet: the usual cascade plays when they are, the grid fades in late.
                    grid.setLayoutAnimation(usual);
                    grid.animate().alpha(1f).setStartDelay(700).setDuration(400).start();
                    return true;
                }
                // The dock's band, the focus glass and the app's name come with the first tiles, not before them.
                // The band waits where the tiles will land (it is drawn by the grid, which the tiles' own movement
                // does not redraw, so following them left it stranded half-way).
                DockDecoration.holdBand = true;
                grid.invalidate();
                grid.animate().alpha(1f).setStartDelay(640).setDuration(320).start();
                for (View v : withTiles) {
                    if (v == null) continue;
                    float target = v.getAlpha();
                    v.setAlpha(0f);
                    v.animate().alpha(target).setStartDelay(900).setDuration(420).setInterpolator(slow).start();
                }
                android.view.animation.OvershootInterpolator lift = new android.view.animation.OvershootInterpolator(1.3f);
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View tile = grid.getChildAt(i);
                    tile.animate().cancel();
                    tile.setAlpha(0f);
                    tile.setTranslationY(70 * dp);
                    // One animator per tile, started now with its delay: it survives the grid rebinding its tiles
                    // during start-up, where a delayed spring could be lost and leave a tile floating.
                    tile.animate().alpha(1f).translationY(0f).setStartDelay(700 + 75L * i).setDuration(620)
                            .setInterpolator(lift).start();
                }
                // Whatever happened meanwhile (tiles rebound, recycled), every tile ends exactly in place.
                grid.postDelayed(() -> {
                    for (int i = 0; i < grid.getChildCount(); i++) {
                        View tile = grid.getChildAt(i);
                        if (tile.getTranslationY() != 0f || tile.getAlpha() != 1f) {
                            tile.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).start();
                        }
                    }
                    DockDecoration.holdBand = false;
                    grid.invalidate();
                }, 2400);
                grid.postDelayed(() -> grid.setLayoutAnimation(usual), 2500);
                return true;
            }
        });
    }

    /** Back from an app: the page settles in briefly, a short rise and fade, nothing more. */
    static void settle(View leftBlock, View topButtons, RecyclerView grid, View... withTiles) {
        float dp = grid.getResources().getDisplayMetrics().density;
        DecelerateInterpolator slow = new DecelerateInterpolator(2.2f);
        View[] parts = {leftBlock, topButtons, grid};
        float[] from = {14 * dp, -10 * dp, 20 * dp};
        for (int i = 0; i < parts.length; i++) {
            View v = parts[i];
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(from[i]);
            v.animate().alpha(1f).translationY(0f).setStartDelay(40L * i).setDuration(380).setInterpolator(slow).start();
        }
        for (View v : withTiles) {
            if (v == null) continue;
            float target = v.getAlpha();
            v.setAlpha(0f);
            v.animate().alpha(target).setStartDelay(120).setDuration(360).setInterpolator(slow).start();
        }
    }

    /** One soft diagonal band of light crossing the screen. */
    private static final class Sweep extends View {
        private final Paint paint = new Paint();
        private float progress = -1f;

        Sweep(Context c) {
            super(c);
            setClickable(false);
            setFocusable(false);
        }

        void run(ViewGroup root, long delay, long duration) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(duration);
            a.setStartDelay(delay);
            a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            a.addUpdateListener(v -> {
                progress = (float) v.getAnimatedValue();
                invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    root.removeView(Sweep.this);
                }
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (progress < 0) return;
            float w = getWidth(), h = getHeight(), band = w * 0.22f;
            float x = -band + (w + band * 2) * progress;
            int peak = (int) (84 * Math.sin(Math.PI * progress));
            paint.setShader(new LinearGradient(x - band, 0, x + band, h * 0.35f,
                    new int[]{0x00FFFFFF, (peak << 24) | 0xFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }
}
