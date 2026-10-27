package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class RunningAppsTest {

    // Shortened from dumpsys activity recents on a Xiaomi Mi Box S (Android 11).
    private static final String RECENTS = String.join("\n",
            "ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)",
            "  * Recent #0: Task{5b50a9a #3079 visible=true type=standard mode=fullscreen translucent=false A=10086:app.hearth.tv U=0 StackId=3079 sz=1}",
            "    affinity=10086:app.hearth.tv",
            "    mActivityComponent=app.hearth.tv/com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity",
            "    Activities=[ActivityRecord{8b88290 u0 app.hearth.tv/.PlaybackActivity t3079}]",
            "  * Recent #1: Task{bbe0a53 #3075 visible=true type=home mode=fullscreen translucent=false A=10083:com.gathin.tvhome U=0 StackId=1 sz=1}",
            "    mActivityComponent=com.gathin.tvhome/.MainActivity",
            "  * Recent #2: Task{cc6dbb5 #2937 visible=false type=undefined mode=undefined translucent=true A=10062:com.google.android.youtube.tv.main U=0 StackId=2937 sz=0}",
            "    mActivityComponent=com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.MainActivity",
            "    Activities=[]",
            "  * Recent #3: Task{a9ae24a #2956 visible=false type=standard mode=fullscreen translucent=true I=org.xbmc.kodi/.Main U=0 StackId=2956 sz=1}",
            "    mActivityComponent=org.xbmc.kodi/.Main",
            "  * Recent #4: Task{576ad8 #2949 visible=false type=standard mode=fullscreen translucent=true A=10112:com.spotify.tv.android U=0 StackId=2949 sz=2}",
            "    mActivityComponent=com.spotify.tv.android/.SpotifyTVActivity",
            "  * Recent #5: Task{576ad9 #2948 visible=false type=standard mode=fullscreen translucent=true A=10112:com.spotify.tv.android U=0 StackId=2948 sz=1}",
            "    mActivityComponent=com.spotify.tv.android/.OtherActivity",
            "  * Recent #6: Task{b1abec7 #3235 visible=true type=standard mode=fullscreen translucent=false A=10111:org.xbmc.kodi.main U=0 StackId=3235 sz=0}",
            "    mActivityComponent=tv.kodi.like/.Splash",
            "  * Recent #7: Task{c1abec7 #3236 visible=false type=standard mode=fullscreen translucent=false A=10120:back.exited U=0 StackId=3236 sz=0}",
            "    mActivityComponent=back.exited/.Main");

    private static final String LRU = String.join("\n",
            "ACTIVITY MANAGER LRU PROCESSES (dumpsys activity lru)",
            "  Activities:",
            "  #36: fg     TOP  LCM 1910:app.hearth.tv/u0a86 act:activities|recents",
            "  #34: vis    BFGS LCM 1448:com.gathin.tvhome/u0a83 act:activities",
            "  Other:",
            "  #26: prcp   FGS  --- 2992:com.spotify.tv.android:playback/u0a112",
            "  #24: cch+25 CEM  --- 2699:com.google.android.youtube.tv/u0a62",
            "  # 6: pers   PER  LCM 1378:com.droidlogic.googletv.settings/1000",
            "  #26: cch    CRE  --- 14438:tv.kodi.like/u0a111 act:recents",
            "  #20: cch+45 CEM  --- 14500:back.exited/u0a120");

    @Test
    public void keepsOnlyStandardTasksMarkingTheLiveOnes() {
        List<RunningApps.Task> tasks = RunningApps.parseRecents(RECENTS);
        assertEquals(5, tasks.size());
        assertFalse(tasks.get(3).live);
        assertEquals("app.hearth.tv", tasks.get(0).pkg);
        assertEquals(3079, tasks.get(0).id);
        assertEquals("org.xbmc.kodi", tasks.get(1).pkg);
        assertEquals("com.spotify.tv.android", tasks.get(2).pkg);
        assertEquals(2949, tasks.get(2).id);   // the most recent task of a package wins
        assertTrue(tasks.get(2).live);
    }

    @Test
    public void processNamesAreReducedToTheirPackage() {
        Set<String> alive = RunningApps.parseAlive(LRU);
        assertTrue(alive.contains("app.hearth.tv"));
        assertTrue(alive.contains("com.spotify.tv.android"));
        assertTrue(alive.contains("com.droidlogic.googletv.settings"));
        assertFalse(alive.contains("org.xbmc.kodi"));
    }

    @Test
    public void openNeedsALiveTaskAndALiveProcess() {
        List<RunningApps.Task> open = RunningApps.open(RECENTS, LRU, "com.gathin.tvhome", pkg -> true);
        assertEquals("app.hearth.tv", open.get(0).pkg);
        assertEquals("com.spotify.tv.android", open.get(1).pkg);
    }

    @Test
    public void aWindowlessTaskCountsOnlyWhileAndroidKeepsItsProcessForRecents() {
        List<RunningApps.Task> open = RunningApps.open(RECENTS, LRU, "com.gathin.tvhome", pkg -> true);
        assertEquals(3, open.size());
        assertEquals("tv.kodi.like", open.get(2).pkg);   // process holds "act:recents"
        assertFalse(open.get(2).live);
        // back.exited: process alive (cached) but no recent task for Android: not open.
    }

    @Test
    public void appsWithoutALauncherEntryAreLeftOut() {
        List<RunningApps.Task> open = RunningApps.open(RECENTS, LRU, "com.gathin.tvhome",
                pkg -> !pkg.startsWith("com.spotify") && !pkg.startsWith("tv."));
        assertEquals(1, open.size());
    }

    @Test
    public void emptyDumpsGiveNothing() {
        assertTrue(RunningApps.open("", "", "x", pkg -> true).isEmpty());
    }
}
