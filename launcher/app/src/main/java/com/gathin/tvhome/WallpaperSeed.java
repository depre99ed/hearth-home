package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hearth Home's own look on a fresh install: the landscapes bundled in assets/wallpapers go into the theme folder,
 * with a config.json that rotates them every hour behind Home (no colour glow, rows fading under the clock). Done
 * once, and only when no theme was pushed before, so a theme of one's own always wins; the launcher's Settings
 * switch back to the light halos, change the interval or pick a single picture.
 */
final class WallpaperSeed {
    private static final String DONE_KEY = "wallpapers_seeded";
    private static final String CONFIG = "{\n"
            + "  \"wallpaper\": true,\n"
            + "  \"wallpaper_rotation_minutes\": 60,\n"
            + "  \"wallpaper_order\": \"shuffle\",\n"
            + "  \"wallpaper_true_color\": true,\n"
            + "  \"background_color\": \"#0A0B0F\",\n"
            + "  \"ambient_glow\": false,\n"
            + "  \"fade_rows\": true\n"
            + "}\n";

    private WallpaperSeed() {}

    static void seed(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(DONE_KEY, false)) return;
        File dir = Config.dir(ctx);
        if (dir == null) return;
        try {
            if (!new File(dir, Config.FILE_NAME).exists()) {
                File walls = new File(dir, "wallpapers");
                if (!walls.isDirectory() && !walls.mkdirs()) return;
                AssetManager assets = ctx.getAssets();
                String[] names = assets.list("wallpapers");
                if (names == null || names.length == 0) return;
                for (String name : names) copy(assets, "wallpapers/" + name, new File(walls, name));
                try (OutputStream out = new FileOutputStream(new File(dir, Config.FILE_NAME))) {
                    out.write(CONFIG.getBytes("UTF-8"));
                }
            }
            prefs.edit().putBoolean(DONE_KEY, true).apply();
        } catch (Exception ignored) {
            // Storage refused: Home keeps its colour glow and tries again next time.
        }
    }

    private static void copy(AssetManager assets, String from, File to) throws Exception {
        try (InputStream in = assets.open(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
    }
}
