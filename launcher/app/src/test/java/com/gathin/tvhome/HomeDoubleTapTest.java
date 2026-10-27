package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;
import org.junit.Test;

public class HomeDoubleTapTest {
    private static final int DOWN = KeyEvent.ACTION_DOWN;
    private static final int UP = KeyEvent.ACTION_UP;

    @Test
    public void aSinglePressPassesStraightThrough() {
        HomeDoubleTap tap = new HomeDoubleTap();
        assertEquals(HomeDoubleTap.Result.PASS, tap.onKey(DOWN, 1000, false));
        assertEquals(HomeDoubleTap.Result.PASS, tap.onKey(UP, 1080, false));
    }

    @Test
    public void aQuickSecondPressIsSwallowedAndOpensOnRelease() {
        HomeDoubleTap tap = new HomeDoubleTap();
        tap.onKey(DOWN, 1000, false);
        tap.onKey(UP, 1080, false);
        assertEquals(HomeDoubleTap.Result.CONSUME, tap.onKey(DOWN, 1300, false));
        assertEquals(HomeDoubleTap.Result.CONSUME, tap.onKey(DOWN, 1350, false));   // repeat
        assertEquals(HomeDoubleTap.Result.OPEN, tap.onKey(UP, 1400, false));
    }

    @Test
    public void aSlowSecondPressIsAnotherSinglePress() {
        HomeDoubleTap tap = new HomeDoubleTap();
        tap.onKey(DOWN, 1000, false);
        tap.onKey(UP, 1080, false);
        assertEquals(HomeDoubleTap.Result.PASS, tap.onKey(DOWN, 1080 + HomeDoubleTap.WINDOW_MS + 1, false));
    }

    @Test
    public void aThirdQuickPressStartsANewPair() {
        HomeDoubleTap tap = new HomeDoubleTap();
        tap.onKey(DOWN, 1000, false);
        tap.onKey(UP, 1080, false);
        tap.onKey(DOWN, 1200, false);
        tap.onKey(UP, 1280, false);
        assertEquals(HomeDoubleTap.Result.PASS, tap.onKey(DOWN, 1400, false));
    }

    @Test
    public void aCanceledSecondPressOpensNothing() {
        HomeDoubleTap tap = new HomeDoubleTap();
        tap.onKey(DOWN, 1000, false);
        tap.onKey(UP, 1080, false);
        tap.onKey(DOWN, 1200, false);
        assertEquals(HomeDoubleTap.Result.CONSUME, tap.onKey(UP, 1280, true));
    }
}
