package com.gathin.tvhome;

import android.view.KeyEvent;

/**
 * Home pressed twice opens the app switcher (home_double_tap). The first press is never held back: it goes to the
 * system at once, so Home is exactly as quick as before. A second press that starts within WINDOW_MS of the first
 * one's release is swallowed whole (down, repeats and up), and its release opens the switcher.
 */
final class HomeDoubleTap {
    static final long WINDOW_MS = 400;

    enum Result { PASS, CONSUME, OPEN }

    private long lastUp = Long.MIN_VALUE / 2;
    private boolean swallowing;

    Result onKey(int action, long eventTime, boolean canceled) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (swallowing) return Result.CONSUME;   // auto-repeat of the second press
            if (eventTime - lastUp <= WINDOW_MS) {
                swallowing = true;
                return Result.CONSUME;
            }
            return Result.PASS;
        }
        if (action == KeyEvent.ACTION_UP) {
            if (swallowing) {
                swallowing = false;
                lastUp = Long.MIN_VALUE / 2;   // a third quick press starts a new pair
                return canceled ? Result.CONSUME : Result.OPEN;
            }
            lastUp = canceled ? Long.MIN_VALUE / 2 : eventTime;
            return Result.PASS;
        }
        return swallowing ? Result.CONSUME : Result.PASS;
    }
}
