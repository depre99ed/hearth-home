package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The apps that are really open now, most recent first, for the app switcher (RecentsActivity).
 *
 * Android gives an ordinary app neither the other apps' tasks nor their processes. The launcher reads them from
 * {@code dumpsys activity recents} and {@code dumpsys activity lru}, which answer it once it holds DUMP (a
 * development permission, granted with {@code pm grant}). "Open" means both: a task that still has live activities
 * (not one restored from disk after a reboot) and a process that is alive. A task whose window is gone while its
 * process lives on is open too (Home pressed during Kodi's splash: Android stops it from opening its window from
 * the background) when Android still counts the process as holding a recent task ("act:recents" in the LRU list).
 * An app closed long ago, or killed by Android for memory, has no process and is never listed.
 */
final class RunningApps {
    private RunningApps() {}

    static final class Task {
        final int id;
        final String pkg;
        /** The task still has an activity: bring it to front. Otherwise the app is started again. */
        final boolean live;

        Task(int id, String pkg, boolean live) {
            this.id = id;
            this.pkg = pkg;
            this.live = live;
        }
    }

    private static final Pattern TASK_HEADER =
            Pattern.compile("^\\s*\\* Recent #\\d+: Task\\{\\S+ #(\\d+) .*?type=(\\w+).* sz=(\\d+)\\}");
    private static final Pattern COMPONENT = Pattern.compile("^\\s*mActivityComponent=([A-Za-z0-9_.]+)/");
    private static final Pattern LRU_LINE = Pattern.compile("^\\s*#\\s*\\d+: .*? \\d+:([A-Za-z0-9_.:]+)/\\S+(.*)$");

    /**
     * The app tasks of {@code dumpsys activity recents} in its order (most recent first), one per package, each
     * marked live when it still holds activities. Home and other non-standard tasks are left out; so are tasks
     * restored from disk, whose type Android has not resolved yet ("undefined").
     */
    static List<Task> parseRecents(String dump) {
        List<Task> tasks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int id = -1;
        boolean standard = false, live = false;
        for (String line : dump.split("\n")) {
            Matcher h = TASK_HEADER.matcher(line);
            if (h.find()) {
                id = Integer.parseInt(h.group(1));
                standard = "standard".equals(h.group(2));
                live = Integer.parseInt(h.group(3)) > 0;
                continue;
            }
            if (id < 0 || !standard) continue;
            Matcher c = COMPONENT.matcher(line);
            if (c.find()) {
                String pkg = c.group(1);
                if (seen.add(pkg)) tasks.add(new Task(id, pkg, live));
                id = -1;
            }
        }
        return tasks;
    }

    /** The packages that have a process alive in {@code dumpsys activity lru} ("com.app" or "com.app:service"). */
    static Set<String> parseAlive(String dump) {
        return parseLru(dump, false);
    }

    /** The packages whose process Android marks as holding a recent task ("act:...recents"). */
    static Set<String> parseWithRecents(String dump) {
        return parseLru(dump, true);
    }

    private static Set<String> parseLru(String dump, boolean recentsOnly) {
        Set<String> out = new HashSet<>();
        for (String line : dump.split("\n")) {
            Matcher m = LRU_LINE.matcher(line);
            if (!m.find()) continue;
            if (recentsOnly && !m.group(2).matches(".*\\bact:\\S*recents.*")) continue;
            String process = m.group(1);
            int colon = process.indexOf(':');
            out.add(colon >= 0 ? process.substring(0, colon) : process);
        }
        return out;
    }

    /** Open tasks whose app is alive, keeping only packages {@code keep} accepts (apps with a launcher entry). */
    static List<Task> open(String recents, String lru, String self, java.util.function.Predicate<String> keep) {
        Set<String> alive = parseAlive(lru);
        Set<String> withRecents = parseWithRecents(lru);
        List<Task> out = new ArrayList<>();
        for (Task t : parseRecents(recents)) {
            if (t.pkg.equals(self) || !alive.contains(t.pkg) || !keep.test(t.pkg)) continue;
            if (!t.live && !withRecents.contains(t.pkg)) continue;
            out.add(t);
        }
        return out;
    }

    /** Runs dumpsys with the given arguments; empty when the launcher lacks DUMP or the call fails. */
    static String dumpsys(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "/system/bin/dumpsys";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 4 * 1024 * 1024) out.write(buf, 0, n);
            }
            p.waitFor();
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
