package com.gathin.tvhome;

import android.Manifest;
import android.content.res.ColorStateList;
import android.annotation.TargetApi;
import android.annotation.SuppressLint;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.app.slice.SliceItem;
import android.app.slice.SliceManager;
import android.app.slice.SliceSpec;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.app.ActivityManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.util.SparseArray;
import java.util.LinkedHashMap;
import java.util.Map;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The launcher's own Settings page (config.xml settings_hub): the everyday settings in Home's look, a category column
 * on the left and a frosted panel on the right, with the ambient glow taking each category's colour. Everything the
 * page cannot change itself opens the matching screen of the TV's settings, and "All settings" at the bottom of the
 * column opens the whole TV Settings app. Remote only: Up/Down move, Right enters a category, Left/Back leave it,
 * Left/Right change a slider or a choice.
 */
public final class SettingsHubActivity extends Activity {

    /** Private DNS host set when the anti-tracking switch turns on and none is configured yet. */
    private static final String DEFAULT_DNS = "x-hagezi-proplus.freedns.controld.com";
    /** The packages the privacy category counts as switched-off trackers (examples/xiaomi-mi-box-s-2nd-gen, this box). */
    private static final String[] TRACKERS = {
            "com.miui.tv.analytics", "com.xiaomi.statistic", "mitv.service", "com.xiaomi.tvqs.overseas",
            "com.xiaomi.mitv.updateservice", "com.google.android.partnersetup", "com.google.android.feedback",
            "com.mitv.tvhome.oemtab", "com.mitv.tvhome.michannel", "com.mitv.tvhome.mitvplus", "com.mitv.tvhome.atv",
            "com.xm.webcontent", "android.autoinstalls.config.xioami.mibox3", "com.google.android.apps.tv.dreamx",
    };
    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final String AERIAL_VIEWS = "com.neilturner.aerialviews";

    private static final int NETWORK = 0, BLUETOOTH = 1, PICTURE = 2, SOUND = 3, SAVER = 4, APPS = 5, PRIVACY = 6,
            SYSTEM = 7, ALL = 8;
    private static final int[] TITLES = {R.string.hub_cat_network, R.string.hub_cat_bluetooth, R.string.hub_cat_picture,
            R.string.hub_cat_sound, R.string.hub_cat_screensaver, R.string.hub_cat_apps, R.string.hub_cat_privacy,
            R.string.hub_cat_system, R.string.hub_all_settings};
    private static final int[] ICONS = {R.drawable.ic_wifi, R.drawable.ic_hub_bluetooth, R.drawable.ic_picture,
            R.drawable.ic_hub_volume, R.drawable.ic_hub_moon, R.drawable.ic_apps, R.drawable.ic_hub_shield,
            R.drawable.ic_hub_info, R.drawable.ic_settings};
    /** Glow colour per category, the macOS System Settings palette. */
    private static final int[] ACCENTS = {0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF9F0A, 0xFF64D2FF, 0xFF30D158,
            0xFFFF375F, 0xFF8E8E9A, 0xFF8E8E9A};
    private static final int[] SAVER_MINUTES = {5, 10, 15, 30, 60};
    private static final float[] ANIM_SCALES = {0.5f, 1f, 1.5f};

    private float density;
    private Typeface medium, light, extraLight, body;
    private final List<View> categoryRows = new ArrayList<>();
    private LinearLayout panelRows;
    private ScrollView panelScroll;
    private TextView panelTitle;
    private GradientDrawable glow;
    private int glowColor = ACCENTS[0];
    private ValueAnimator glowAnimator;
    private int current = -1;
    private boolean animate;
    /** True while the panel is being rebuilt: focus moving off a removed row must not switch category. */
    private boolean rebuilding;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        medium = UiFont.get(this, R.string.font_medium);
        light = UiFont.get(this, R.string.font_light);
        extraLight = UiFont.get(this, R.string.font_extralight);
        body = UiFont.get(this, R.string.font_body);
        animate = Config.load(this).animations;
        setContentView(buildLayout());
        setupBluetooth();
        categoryRows.get(0).requestFocus();
        if (animate) {
            overridePendingTransition(R.anim.hub_enter, 0);
            entrance();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Values change behind our back (a TV settings screen, a vendor confirmation dialog, the remote's volume
        // keys): rebuild what is shown, staying on the TV settings page that was open.
        if (!slicePages.isEmpty() && slicePages.getLast().startsWith(BT_PAGE)) {
            refreshBluetooth();
        } else if (!slicePages.isEmpty()) {
            String uri = slicePages.getLast();
            int generation = ++panelGeneration;
            ui.postDelayed(() -> loadSlice(uri, rows -> {
                if (generation != panelGeneration) return;
                View focused = getCurrentFocus();
                int index = focused != null && focused.getParent() == panelRows ? panelRows.indexOfChild(focused) : 0;
                rebuilding = true;
                categoryRows.get(current).requestFocus();
                renderSlicePage(uri, rows);
                View again = panelRows.getChildAt(Math.max(0, Math.min(index, panelRows.getChildCount() - 1)));
                if (again != null) again.requestFocus();
                rebuilding = false;
            }), 500);
        } else if (current >= 0) {
            showCategory(current, false);
        }
    }

    @Override
    public void finish() {
        super.finish();
        if (animate) overridePendingTransition(0, R.anim.hub_exit);
    }

    // ---- layout ----------------------------------------------------------------------------------

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // Ambient glow, as on Home: a soft radial wash in the category's colour from the upper left.
        View glowView = new View(this);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.22f, 0.28f);
        glow.setGradientRadius(820 * density);
        glow.setColors(new int[]{withAlpha(glowColor, 0x70), withAlpha(glowColor, 0x22), 0x00000000});
        glowView.setBackground(glow);
        root.addView(glowView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.setClipChildren(false);
        LinearLayout columns = new LinearLayout(this);
        columns.setClipChildren(false);
        // A focused row grows a little (pop()); padding must not clip it, or its rounded ends are cut flat.
        columns.setClipToPadding(false);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(56), dp(30), dp(56), dp(28));
        root.addView(columns, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Left: title, clock and the category list, "All settings" pinned at the bottom.
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setClipChildren(false);
        side.setClipToPadding(false);
        columns.addView(side, new LinearLayout.LayoutParams(dp(286), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text(getString(R.string.hub_title), 34, extraLight, 0xFFFFFFFF);
        title.setPadding(dp(6), 0, 0, 0);
        side.addView(title);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm  ·  EEEE d MMMM");
        clock.setFormat12Hour("h:mm a  ·  EEEE d MMMM");
        clock.setTypeface(body);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clock.setLetterSpacing(0.08f);
        clock.setAllCaps(true);
        clock.setTextColor(0x99FFFFFF);
        clock.setPadding(dp(7), dp(2), 0, dp(14));
        side.addView(clock);

        for (int i = 0; i < TITLES.length; i++) {
            if (i == ALL) {
                View spacer = new View(this);
                side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
            }
            View row = categoryRow(i);
            categoryRows.add(row);
            side.addView(row);
        }

        // Right: the frosted panel with the selected category.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFFFFF, 0x10FFFFFF});
        glass.setCornerRadius(dp(26));
        glass.setStroke(Math.max(1, dp(1) / 2 + 1), 0x2EFFFFFF);
        panel.setBackground(glass);
        panel.setPadding(dp(14), dp(20), dp(14), dp(14));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        plp.setMarginStart(dp(30));
        columns.addView(panel, plp);

        panelTitle = text("", 28, light, 0xFFFFFFFF);
        panelTitle.setPadding(dp(14), 0, 0, dp(10));
        panel.addView(panelTitle);
        panelScroll = new ScrollView(this);
        panelScroll.setVerticalScrollBarEnabled(false);
        panelScroll.setFillViewport(true);
        panelScroll.setClipToPadding(false);
        panelRows = new LinearLayout(this);
        panelRows.setOrientation(LinearLayout.VERTICAL);
        panelRows.setClipChildren(false);
        panelRows.setClipToPadding(false);
        panelRows.setPadding(dp(10), dp(4), dp(10), dp(10));
        panelScroll.addView(panelRows, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(panelScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View categoryRow(int index) {
        LinearLayout row = baseRow(dp(41));
        ImageView icon = new ImageView(this);
        icon.setImageResource(ICONS[index]);
        icon.setImageTintList(states(R.drawable.pill_text));
        icon.setDuplicateParentStateEnabled(true);
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView label = text(getString(TITLES[index]), 16, medium, 0);
        label.setTextColor(states(R.drawable.pill_text));
        label.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.setMarginStart(dp(12));
        row.addView(label, llp);
        if (index == ALL) {
            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_hub_chevron);
            chevron.setImageTintList(states(R.drawable.pill_text_dim));
            chevron.setDuplicateParentStateEnabled(true);
            row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        }
        row.setOnFocusChangeListener((v, focused) -> {
            pop(v, focused, 1.03f);
            if (focused && !rebuilding) showCategory(index, true);
        });
        row.setOnClickListener(v -> {
            if (index == ALL) open(SystemSettings.stock(this, Settings.ACTION_SETTINGS));
            else focusPanel();
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && index != ALL) return focusPanel();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;   // nothing to the left of the column
            return false;
        });
        return row;
    }

    /** The first row of the panel takes focus; false when the category has nothing focusable. */
    private boolean focusPanel() {
        for (int i = 0; i < panelRows.getChildCount(); i++) {
            View child = panelRows.getChildAt(i);
            if (child.isFocusable()) {
                child.requestFocus();
                return true;
            }
        }
        return true;
    }

    private void focusCategory() {
        if (current >= 0) categoryRows.get(current).requestFocus();
    }

    @Override
    public void onBackPressed() {
        View focus = getCurrentFocus();
        if (focus != null && focus.getParent() == panelRows) goBack();
        else super.onBackPressed();
    }

    // ---- categories ------------------------------------------------------------------------------

    private void showCategory(int index, boolean transition) {
        boolean wasSlicePage = !slicePages.isEmpty();
        slicePages.clear();
        panelGeneration++;
        boolean changed = index != current || wasSlicePage;
        current = index;
        panelTitle.setText(TITLES[index]);
        int scrollY = changed ? 0 : panelScroll.getScrollY();
        // A refresh while a panel row has focus keeps focus on the same row instead of letting it fall back to
        // the category column (removing the focused view moves focus, which would switch category mid-rebuild).
        View focused = getCurrentFocus();
        int focusedRow = focused != null && focused.getParent() == panelRows ? panelRows.indexOfChild(focused) : -1;
        rebuilding = true;
        if (focusedRow >= 0) categoryRows.get(index).requestFocus();
        panelRows.removeAllViews();
        switch (index) {
            case NETWORK: buildNetwork(); break;
            case BLUETOOTH: buildBluetooth(); break;
            case PICTURE: buildPicture(); break;
            case SOUND: buildSound(); break;
            case SAVER: buildSaver(); break;
            case APPS: buildApps(); break;
            case PRIVACY: buildPrivacy(); break;
            case SYSTEM: buildSystem(); break;
            default: buildAll(); break;
        }
        panelScroll.scrollTo(0, scrollY);
        if (focusedRow >= 0 && focusedRow < panelRows.getChildCount()) panelRows.getChildAt(focusedRow).requestFocus();
        rebuilding = false;
        tintGlow(ACCENTS[index]);
        if (changed && transition && animate) {
            // The panel's content slides in a few dp and fades, as a macOS settings pane does when switching.
            panelRows.setAlpha(0f);
            panelRows.setTranslationX(dp(18));
            panelTitle.setAlpha(0f);
            panelRows.animate().alpha(1f).translationX(0f).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            panelTitle.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void buildNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network active = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
        boolean wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean ethernet = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        section(R.string.hub_sec_connection);
        String status;
        if (wifi) {
            status = getString(R.string.hub_connected);
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            if (info != null) {
                String ssid = info.getSSID();
                if (ssid != null && !ssid.contains("unknown ssid")) status = ssid.replace("\"", "");
                int level = WifiManager.calculateSignalLevel(info.getRssi(), 5);
                int[] signal = {R.string.hub_signal_1, R.string.hub_signal_1, R.string.hub_signal_2,
                        R.string.hub_signal_3, R.string.hub_signal_4};
                status += "  ·  " + getString(signal[Math.max(0, Math.min(4, level))]);
            }
        } else if (ethernet) {
            status = getString(R.string.hub_connected);
        } else {
            status = getString(R.string.hub_disconnected);
        }
        info(ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi,
                getString(ethernet ? R.string.hub_ethernet : R.string.hub_wifi), status);
        info(R.drawable.ic_hub_info, getString(R.string.hub_ip), ipv4(cm, active));
        boolean validated = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        info(R.drawable.ic_hub_shield, getString(R.string.hub_internet),
                getString(validated ? R.string.hub_internet_ok : R.string.hub_internet_no));
        link(R.drawable.ic_wifi, getString(R.string.hub_choose_wifi), null,
                SystemSettings.stock(this, Settings.ACTION_WIFI_SETTINGS));

        section(R.string.hub_sec_protection);
        link(R.drawable.ic_hub_shield, getString(R.string.hub_dns), dnsStatus(), null, () -> {
            showCategory(PRIVACY, true);
            focusPanel();
        });
        String vpnApp = getString(R.string.vpn_app);
        Intent vpn = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (vpn == null && !vpnApp.isEmpty()) vpn = getPackageManager().getLaunchIntentForPackage(vpnApp);
        link(R.drawable.ic_hub_key, getString(R.string.hub_vpn),
                getString(vpnActive(cm) ? R.string.hub_vpn_on : R.string.hub_vpn_off), vpn);
    }

    private void buildPicture() {
        section(R.string.hub_sec_display);
        Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? getDisplay()
                : getWindowManager().getDefaultDisplay();
        if (display != null) {
            Display.Mode mode = display.getMode();
            info(R.drawable.ic_picture, getString(R.string.hub_resolution), mode.getPhysicalWidth() + " × "
                    + mode.getPhysicalHeight() + "  ·  " + Math.round(mode.getRefreshRate()) + " Hz");
            Display.HdrCapabilities hdr = display.getHdrCapabilities();
            info(R.drawable.ic_hub_tune, getString(R.string.hub_hdr), hdrTypes(hdr));
        }
        sliceLink(R.drawable.ic_picture, getString(R.string.hub_slice_resolution), SLICE_HDR + "resolution");
        sliceLink(R.drawable.ic_hub_tune, getString(R.string.hub_slice_hdr_pref), SLICE_HDR + "hdr_format_preference");
        sliceLink(R.drawable.ic_hub_tune, getString(R.string.hub_slice_match), SLICE_HDR + "match_content");
        sliceLink(R.drawable.ic_hub_tune, getString(R.string.hub_slice_color), SLICE_HDR + "color_attribute");
        sliceLink(R.drawable.ic_hub_tune, getString(R.string.hub_slice_dolby), SLICE_HDR + "dolby_vision_mode");
        link(R.drawable.ic_hub_tune, getString(R.string.hub_display_advanced),
                getString(R.string.hub_display_advanced_hint), displaySound());

        section(R.string.hub_sec_motion);
        float scale = Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        int currentScale = 1;
        for (int i = 0; i < ANIM_SCALES.length; i++) if (Math.abs(ANIM_SCALES[i] - scale) < 0.01f) currentScale = i;
        String[] labels = {getString(R.string.hub_anim_fast), getString(R.string.hub_anim_normal),
                getString(R.string.hub_anim_slow)};
        choice(R.drawable.ic_hub_play, getString(R.string.hub_anim_speed), labels, currentScale, canWriteSecure(), i -> {
            ContentResolver cr = getContentResolver();
            Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, ANIM_SCALES[i]);
            Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, ANIM_SCALES[i]);
            Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, ANIM_SCALES[i]);
        });
    }

    private void buildSound() {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio != null) {
            if (audio.isVolumeFixed()) {
                info(R.drawable.ic_hub_volume, getString(R.string.hub_volume), getString(R.string.hub_volume_fixed));
            } else {
                slider(R.drawable.ic_hub_volume, getString(R.string.hub_volume),
                        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                        v -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0));
            }
            toggle(R.drawable.ic_hub_volume, getString(R.string.hub_mute), null,
                    audio.isStreamMute(AudioManager.STREAM_MUSIC), true,
                    on -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0));
        }
        boolean effects = Settings.System.getInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1;
        toggle(R.drawable.ic_hub_play, getString(R.string.hub_sound_effects), null, effects,
                Settings.System.canWrite(this), on -> {
                    Settings.System.putInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, on ? 1 : 0);
                    if (audio != null) {
                        if (on) audio.loadSoundEffects();
                        else audio.unloadSoundEffects();
                    }
                });
        sliceLink(R.drawable.ic_hub_speaker, getString(R.string.hub_slice_surround), SLICE_SOUND + "advanced_sound_settings");
        link(R.drawable.ic_hub_tune, getString(R.string.hub_audio_advanced), null, displaySound());
    }

    private void buildSaver() {
        ContentResolver cr = getContentResolver();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> dreams = pm.queryIntentServices(new Intent(DreamService.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA);
        List<String> labels = new ArrayList<>();
        List<String> components = new ArrayList<>();
        labels.add(getString(R.string.hub_saver_none));
        components.add("");
        for (ResolveInfo r : dreams) {
            if (!r.serviceInfo.isEnabled() || !r.serviceInfo.applicationInfo.enabled) continue;
            labels.add(r.loadLabel(pm).toString());
            components.add(new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name).flattenToShortString());
        }
        String now = Settings.Secure.getString(cr, "screensaver_components");
        boolean enabled = Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 1;
        int selected = 0;
        if (enabled && now != null) {
            ComponentName nowName = ComponentName.unflattenFromString(now.split(",")[0]);
            for (int i = 1; i < components.size(); i++) {
                if (nowName != null && nowName.equals(ComponentName.unflattenFromString(components.get(i)))) selected = i;
            }
        }
        choice(R.drawable.ic_hub_moon, getString(R.string.hub_saver), labels.toArray(new String[0]), selected,
                canWriteSecure(), i -> {
                    if (i == 0) {
                        Settings.Secure.putInt(cr, "screensaver_enabled", 0);
                    } else {
                        Settings.Secure.putString(cr, "screensaver_components", components.get(i));
                        Settings.Secure.putInt(cr, "screensaver_enabled", 1);
                    }
                });

        int timeout = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 600000);
        String[] after = new String[SAVER_MINUTES.length];
        int afterIndex = 1;
        for (int i = 0; i < SAVER_MINUTES.length; i++) {
            after[i] = SAVER_MINUTES[i] == 60 ? getString(R.string.hub_hour)
                    : getString(R.string.hub_minutes, SAVER_MINUTES[i]);
            if (Math.abs(timeout - SAVER_MINUTES[i] * 60000) < 30000) afterIndex = i;
        }
        choice(R.drawable.ic_hub_clock, getString(R.string.hub_saver_after), after, afterIndex,
                Settings.System.canWrite(this),
                i -> Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, SAVER_MINUTES[i] * 60000));

        Intent start = new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui",
                "com.android.systemui.Somnambulator");
        link(R.drawable.ic_hub_play, getString(R.string.hub_saver_now), null, resolves(start) ? start : null);
        Intent aerial = pm.getLeanbackLaunchIntentForPackage(AERIAL_VIEWS);
        if (aerial == null) aerial = pm.getLaunchIntentForPackage(AERIAL_VIEWS);
        if (aerial != null) {
            link(R.drawable.ic_hub_tune, getString(R.string.hub_saver_customize),
                    getString(R.string.hub_saver_customize_hint), aerial);
        }
    }

    private void buildApps() {
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        info(R.drawable.ic_hub_storage, getString(R.string.hub_storage), getString(R.string.hub_storage_value,
                Formatter.formatShortFileSize(this, stat.getAvailableBytes()),
                Formatter.formatShortFileSize(this, stat.getTotalBytes())));
        link(R.drawable.ic_apps, getString(R.string.hub_manage_apps), getString(R.string.hub_manage_apps_hint),
                SystemSettings.stock(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        if (Config.load(this).appLibrary) {
            link(R.drawable.ic_apps, getString(R.string.hub_library), null,
                    new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_OPEN_LIBRARY));
        }
        link(R.drawable.ic_home_settings, getString(R.string.hub_home_settings), null,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
    }

    private void buildPrivacy() {
        ContentResolver cr = getContentResolver();
        boolean dnsOn = "hostname".equals(Settings.Global.getString(cr, "private_dns_mode"));
        toggle(R.drawable.ic_hub_shield, getString(R.string.hub_dns), dnsStatus(), dnsOn, canWriteSecure(), on -> {
            if (on) {
                String host = Settings.Global.getString(cr, "private_dns_specifier");
                if (host == null || host.isEmpty()) Settings.Global.putString(cr, "private_dns_specifier", DEFAULT_DNS);
                Settings.Global.putString(cr, "private_dns_mode", "hostname");
            } else {
                Settings.Global.putString(cr, "private_dns_mode", "opportunistic");
            }
        }, this::dnsStatus);
        boolean locationOn = Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE, 0) != 0;
        toggle(R.drawable.ic_hub_location, getString(R.string.hub_location),
                getString(locationOn ? R.string.hub_location_on : R.string.hub_location_off), locationOn,
                canWriteSecure(), on -> Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE,
                        on ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY : Settings.Secure.LOCATION_MODE_OFF),
                () -> getString(Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE, 0) != 0
                        ? R.string.hub_location_on : R.string.hub_location_off));
        Intent ads = new Intent().setComponent(new ComponentName("com.google.android.gms",
                "com.google.android.gms.adid.settings.AdsSettingsActivity"));
        link(R.drawable.ic_hub_key, getString(R.string.hub_ads_id), null, resolves(ads) ? ads : null);

        section(R.string.hub_sec_box);
        int off = 0, present = 0;
        PackageManager pm = getPackageManager();
        for (String pkg : TRACKERS) {
            try {
                int state = pm.getApplicationEnabledSetting(pkg);
                present++;
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) off++;
            } catch (IllegalArgumentException notInstalled) {
                // Not on this box, or not visible: not counted.
            }
        }
        info(R.drawable.ic_hub_shield, getString(R.string.hub_trackers),
                getString(R.string.hub_trackers_value, off, present));
        String logs = Settings.Secure.getString(cr, "upload_log_pref");
        info(R.drawable.ic_hub_info, getString(R.string.hub_xiaomi_logs),
                getString("1".equals(logs) ? R.string.hub_on : R.string.hub_off));
    }

    private void buildSystem() {
        section(R.string.hub_sec_box);
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        info(R.drawable.ic_hub_info, getString(R.string.hub_device),
                (name != null ? name + "  ·  " : "") + Build.MODEL);
        info(R.drawable.ic_hub_info, getString(R.string.hub_android), Build.VERSION.RELEASE + "  ·  " + Build.DISPLAY);
        info(R.drawable.ic_hub_shield, getString(R.string.hub_patch), Build.VERSION.SECURITY_PATCH);
        long minutes = SystemClock.elapsedRealtime() / 60000;
        info(R.drawable.ic_hub_clock, getString(R.string.hub_uptime), minutes >= 24 * 60
                ? getString(R.string.hub_days_hours, (int) (minutes / 1440), (int) (minutes % 1440 / 60))
                : getString(R.string.hub_hours_minutes, (int) (minutes / 60), (int) (minutes % 60)));
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am != null) {
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mem);
            info(R.drawable.ic_hub_storage, getString(R.string.hub_memory), getString(R.string.hub_memory_value,
                    Formatter.formatShortFileSize(this, mem.availMem), Formatter.formatShortFileSize(this, mem.totalMem)));
        }
        section(R.string.hub_sec_tv);
        sliceLink(R.drawable.ic_hub_remote, getString(R.string.hub_slice_cec), SLICE_CEC);
        sliceLink(R.drawable.ic_hub_play, getString(R.string.hub_slice_power), SLICE_POWER);
        section(R.string.hub_sec_more);
        link(R.drawable.ic_hub_clock, getString(R.string.hub_date), null, SystemSettings.stock(this, Settings.ACTION_DATE_SETTINGS));
        link(R.drawable.ic_hub_info, getString(R.string.hub_language), null, SystemSettings.stock(this, Settings.ACTION_LOCALE_SETTINGS));
        link(R.drawable.ic_hub_tune, getString(R.string.hub_keyboard), null, SystemSettings.stock(this, Settings.ACTION_INPUT_METHOD_SETTINGS));
        link(R.drawable.ic_hub_key, getString(R.string.hub_accounts), null, SystemSettings.stock(this, "android.settings.USER_SETTINGS"));
        link(R.drawable.ic_hub_info, getString(R.string.hub_captions), null, SystemSettings.stock(this, Settings.ACTION_CAPTIONING_SETTINGS));
        link(R.drawable.ic_hub_shield, getString(R.string.hub_updates), null, SystemSettings.stock(this, "android.settings.SYSTEM_UPDATE_SETTINGS"));
        link(R.drawable.ic_settings, getString(R.string.hub_about), null, SystemSettings.stock(this, Settings.ACTION_DEVICE_INFO_SETTINGS));
    }

    private void buildAll() {
        TextView hint = text(getString(R.string.hub_all_settings_hint), 16, light, 0xB3FFFFFF);
        hint.setPadding(dp(14), dp(4), dp(14), dp(18));
        panelRows.addView(hint);
        link(R.drawable.ic_settings, getString(R.string.hub_open), null, SystemSettings.stock(this, Settings.ACTION_SETTINGS));
    }

    // ---- rows --------------------------------------------------------------------------------------

    private LinearLayout baseRow(int height) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setBackgroundResource(R.drawable.input_row_bg);
        row.setFocusable(true);
        row.setClickable(true);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        return row;
    }

    /** Values longer than this go under the title instead of beside it, so neither is ever cut short. */
    private static final int SHORT_VALUE = 22;

    /**
     * A panel row: icon, then the title with an optional second line under it, then a short dim value on the right;
     * Left goes back to the categories (or up one page inside a TV settings page).
     */
    private LinearLayout panelRow(int icon, String title, String value) {
        LinearLayout row = baseRow(ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setMinimumHeight(dp(50));
        row.setPadding(dp(14), dp(7), dp(14), dp(7));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(states(R.drawable.pill_text_mid));
        iv.setDuplicateParentStateEnabled(true);
        row.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(14));
        row.addView(texts, tlp);
        TextView t = text(title, 16, medium, 0);
        t.setTextColor(states(R.drawable.pill_text));
        t.setDuplicateParentStateEnabled(true);
        t.setMaxLines(2);
        t.setTag("title");
        texts.addView(t);
        TextView sub = text("", 13, body, 0);
        sub.setTextColor(states(R.drawable.pill_text_dim));
        sub.setDuplicateParentStateEnabled(true);
        sub.setMaxLines(3);
        sub.setTag("subtitle");
        sub.setVisibility(View.GONE);
        texts.addView(sub);

        TextView v = text("", 14, body, 0);
        v.setTextColor(states(R.drawable.pill_text_dim));
        v.setDuplicateParentStateEnabled(true);
        v.setSingleLine(true);
        v.setTag("value");
        v.setVisibility(View.GONE);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMarginStart(dp(12));
        row.addView(v, vlp);
        setValue(row, value);
        row.setOnFocusChangeListener((view, focused) -> pop(view, focused, 1.012f));
        row.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                goBack();
                return true;
            }
            return false;
        });
        panelRows.addView(row);
        return row;
    }

    /** Puts a value beside the title when it is short, under it when it is long (never truncated). */
    private void setValue(View row, String value) {
        TextView right = row.findViewWithTag("value");
        TextView under = row.findViewWithTag("subtitle");
        if (right == null || under == null) return;
        boolean empty = value == null || value.isEmpty();
        boolean longValue = !empty && value.length() > SHORT_VALUE;
        right.setText(empty || longValue ? "" : value);
        right.setVisibility(empty || longValue ? View.GONE : View.VISIBLE);
        under.setText(longValue ? value : "");
        under.setVisibility(longValue ? View.VISIBLE : View.GONE);
    }

    /** Text shown on a row as a second line whatever its length (a description, a slice summary). */
    private void setSubtitle(View row, String text) {
        TextView under = row.findViewWithTag("subtitle");
        if (under == null || text == null || text.isEmpty()) return;
        under.setText(text);
        under.setVisibility(View.VISIBLE);
    }

    private void info(int icon, String title, String value) {
        panelRow(icon, title, value);
    }

    private void link(int icon, String title, String value, Intent target) {
        link(icon, title, value, target, null);
    }

    private void link(int icon, String title, String value, Intent target, Runnable action) {
        LinearLayout row = panelRow(icon, title, value);
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_hub_chevron);
        chevron.setImageTintList(states(R.drawable.pill_text_dim));
        chevron.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(18), dp(18));
        clp.setMarginStart(dp(6));
        row.addView(chevron, clp);
        if (target == null && action == null) {
            row.setAlpha(0.45f);
            return;
        }
        row.setOnClickListener(v -> {
            if (action != null) action.run();
            else open(target);
        });
    }

    private interface IntChoice { void select(int index); }
    private interface BoolChoice { void set(boolean on); }
    private interface Detail { String get(); }

    /**
     * OK steps to the next value, written straight away; Left still goes back to the categories, so a press meant
     * to leave the panel never changes a setting by accident.
     */
    private void choice(int icon, String title, String[] labels, int selected, boolean writable, IntChoice onSelect) {
        LinearLayout row = panelRow(icon, title, "");
        final int[] index = {selected};
        if (!writable) {
            setValue(row, labels[index[0]] + "  ·  " + getString(R.string.hub_needs_permission));
            return;
        }
        setValue(row, labels[index[0]] + "  ↻");
        row.setOnClickListener(v -> {
            index[0] = (index[0] + 1) % labels.length;
            apply(() -> onSelect.select(index[0]));
            setValue(row, labels[index[0]] + "  ↻");
            nudge(row.findViewWithTag("value"), 1);
        });
    }

    private void toggle(int icon, String title, String detail, boolean on, boolean writable, BoolChoice onChange) {
        toggle(icon, title, detail, on, writable, onChange, null);
    }

    /** detailAfter, when given, re-reads the row's detail once the switch has flipped (the DNS host, the wording). */
    private void toggle(int icon, String title, String detail, boolean on, boolean writable, BoolChoice onChange,
                        Detail detailAfter) {
        LinearLayout row = panelRow(icon, title, writable ? detail : getString(R.string.hub_needs_permission));
        SwitchView sw = new SwitchView(this, on);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(42), dp(24));
        slp.setMarginStart(dp(12));
        row.addView(sw, slp);
        sw.setDuplicateParentStateEnabled(true);
        if (!writable) {
            row.setAlpha(0.6f);
            return;
        }
        row.setOnClickListener(v -> {
            boolean next = !sw.on;
            apply(() -> onChange.set(next));
            sw.setOn(next, animate);
            if (detailAfter != null) setValue(row, detailAfter.get());
        });
    }

    private void slider(int icon, String title, int max, int value, IntChoice onChange) {
        LinearLayout row = panelRow(icon, title, value + " / " + max);
        BarView bar = new BarView(this, max, value);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(160), dp(6));
        blp.setMarginStart(dp(14));
        row.addView(bar, blp);
        bar.setDuplicateParentStateEnabled(true);
        row.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (step == 0) return false;
            int next = Math.max(0, Math.min(max, bar.value + step));
            if (next != bar.value) {
                bar.set(next);
                setValue(row, next + " / " + max);
                onChange.select(next);
            }
            return true;
        });
    }

    private void section(int title) {
        TextView t = text(getString(title), 11, body, 0x80FFFFFF);
        t.setLetterSpacing(0.16f);
        t.setPadding(dp(14), dp(panelRows.getChildCount() == 0 ? 2 : 16), 0, dp(6));
        panelRows.addView(t);
    }

    // ---- TV settings pages (slices) ------------------------------------------------------------------

    /*
     * The TV's own settings pages for picture and sound are slices served by the vendor settings app: a list of
     * preferences, each with its title, summary, radio group or switch state and the PendingIntent that applies it.
     * The panel binds them (no permission needed on this firmware), draws them and sends the actions back, so the
     * change is made by the vendor app with its own rights and dialogs (the "keep this resolution?" countdown
     * included), exactly as when chosen in the TV's settings.
     */
    private static final String SLICE_HDR = "content://com.google.android.tv.settings.hdr.sliceprovider/";
    private static final String SLICE_SOUND = "content://com.google.android.tv.settings.advancedsound.sliceprovider/";
    private static final String SLICE_CEC = "content://com.google.android.tv.settings.displaysound.hdmi.sliceprovider/switch_cec";
    private static final String SLICE_POWER = "content://com.google.android.tv.settings.powerbehavior.sliceprovider/power_boot_resume";
    private static final String SUB_SCREEN_TITLE = "TYPE_PREFERENCE_SCREEN_TITLE";
    private static final String SUB_CATEGORY = "TYPE_PREFERENCE_CATEGORY";
    private static final String SUB_EMBEDDED = "TYPE_PREFERENCE_EMBEDDED";

    private final ExecutorService sliceThread = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** The slice pages open in the panel, innermost last; empty while a category is shown. */
    private final ArrayDeque<String> slicePages = new ArrayDeque<>();
    /** Bumped whenever the panel changes, so a slow bind never draws over a newer page. */
    private int panelGeneration;

    private static final class SliceRow {
        String type, title, summary, radioGroup, target, key;
        boolean enabled = true, selected, toggle;
        PendingIntent action;
    }

    @Override
    protected void onPause() {
        stopScan();   // a search never outlives the page, nor does the location it may have switched on
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseBluetooth();
        sliceThread.shutdownNow();
        super.onDestroy();
    }

    /** Left and Back: up one TV settings page, or back to the category column. */
    private void goBack() {
        if (!slicePages.isEmpty()) {
            slicePages.removeLast();
            if (slicePages.isEmpty()) {
                int category = current;
                current = -1;
                showCategory(category, true);
                focusPanel();
            } else {
                openSlicePage(slicePages.removeLast(), true);
            }
            return;
        }
        focusCategory();
    }

    /** A row that opens a TV settings page in the panel; its value shows the page's current choice once known. */
    private void sliceLink(int icon, String title, String uri) {
        LinearLayout row = panelRow(icon, title, "");
        addChevron(row);
        row.setOnClickListener(v -> openSlicePage(uri, true));
        int generation = panelGeneration;
        loadSlice(uri, rows -> {
            if (generation != panelGeneration) return;
            if (rows == null || rows.size() <= 1) {
                row.setVisibility(View.GONE);   // not on this firmware, or nothing in it
                return;
            }
            setValue(row, selectedSummary(rows));
        });
    }

    private void addChevron(LinearLayout row) {
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_hub_chevron);
        chevron.setImageTintList(states(R.drawable.pill_text_dim));
        chevron.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(18), dp(18));
        clp.setMarginStart(dp(6));
        row.addView(chevron, clp);
    }

    private void openSlicePage(String uri, boolean transition) {
        if (uri.startsWith(BT_PAGE)) {
            BluetoothDevice device = remoteDevice(uri.substring(BT_PAGE.length()));
            if (device == null) return;
            slicePages.addLast(uri);
            panelGeneration++;
            rebuilding = true;
            categoryRows.get(current).requestFocus();
            panelRows.removeAllViews();
            renderDevicePage(device);
            rebuilding = false;
            panelScroll.scrollTo(0, 0);
            focusPanel();
            if (transition && animate) {
                panelRows.setAlpha(0f);
                panelRows.setTranslationX(dp(18));
                panelRows.animate().alpha(1f).translationX(0f).setDuration(220)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }
            return;
        }
        slicePages.addLast(uri);
        int generation = ++panelGeneration;
        rebuilding = true;
        categoryRows.get(current).requestFocus();
        panelRows.removeAllViews();
        rebuilding = false;
        panelScroll.scrollTo(0, 0);
        loadSlice(uri, rows -> {
            if (generation != panelGeneration) return;
            renderSlicePage(uri, rows);
            focusPanel();
            if (transition && animate) {
                panelRows.setAlpha(0f);
                panelRows.setTranslationX(dp(18));
                panelRows.animate().alpha(1f).translationX(0f).setDuration(220)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            }
        });
    }

    private void renderSlicePage(String uri, List<SliceRow> rows) {
        panelRows.removeAllViews();
        if (rows == null) {
            info(R.drawable.ic_hub_info, getString(R.string.not_available), "");
            return;
        }
        for (SliceRow r : rows) {
            if (SUB_SCREEN_TITLE.equals(r.type)) {
                if (r.title != null) panelTitle.setText(r.title);
                continue;
            }
            if (SUB_EMBEDDED.equals(r.type) || r.title == null || r.title.isEmpty()) continue;
            if (SUB_CATEGORY.equals(r.type)) {
                TextView t = text(r.title.toUpperCase(getResources().getConfiguration().getLocales().get(0)), 11,
                        body, 0x80FFFFFF);
                t.setLetterSpacing(0.16f);
                t.setPadding(dp(14), dp(16), 0, dp(6));
                panelRows.addView(t);
                continue;
            }
            LinearLayout row;
            if (r.radioGroup != null) {
                row = panelRow(r.selected ? R.drawable.ic_hub_check : R.drawable.ic_hub_dot, r.title, "");
                setValue(row, r.summary);
                row.setOnClickListener(v -> sendSlice(uri, r, true));
            } else if (r.toggle) {
                row = panelRow(R.drawable.ic_hub_tune, r.title, "");
                setSubtitle(row, r.summary);
                SwitchView sw = new SwitchView(this, r.selected);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(42), dp(24));
                slp.setMarginStart(dp(12));
                row.addView(sw, slp);
                sw.setDuplicateParentStateEnabled(true);
                row.setOnClickListener(v -> {
                    sw.setOn(!sw.on, animate);
                    sendSlice(uri, r, sw.on);
                });
            } else if (r.target != null) {
                row = panelRow(R.drawable.ic_hub_tune, r.title, "");
                setValue(row, r.summary);
                addChevron(row);
                String target = r.target;
                row.setOnClickListener(v -> openSlicePage(target, true));
            } else {
                row = panelRow(R.drawable.ic_hub_info, r.title, "");
                setValue(row, r.summary);
                if (r.action != null) row.setOnClickListener(v -> sendSlice(uri, r, true));
            }
            if (!r.enabled) {
                row.setAlpha(0.4f);
                row.setOnClickListener(null);
            }
        }
    }

    /** Sends a slice action the way the TV's settings do, then reads the page again to show the new state. */
    private void sendSlice(String uri, SliceRow row, boolean state) {
        if (row.action == null) return;
        // The vendor dialogs read the preference key the TV's settings send with every action (the resolution one
        // takes the new mode from it, "1080p50hz").
        Intent fill = new Intent().putExtra(Slice.EXTRA_TOGGLE_STATE, state).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (row.key != null) fill.putExtra("extra_preference_key", row.key);
        try {
            row.action.send(this, 0, fill);
        } catch (PendingIntent.CanceledException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        int generation = panelGeneration;
        ui.postDelayed(() -> {
            if (generation != panelGeneration || slicePages.isEmpty() || !uri.equals(slicePages.getLast())) return;
            View focused = getCurrentFocus();
            int index = focused != null && focused.getParent() == panelRows ? panelRows.indexOfChild(focused) : 0;
            loadSlice(uri, rows -> {
                if (generation != panelGeneration) return;
                rebuilding = true;
                categoryRows.get(current).requestFocus();
                renderSlicePage(uri, rows);
                View again = panelRows.getChildAt(Math.max(0, Math.min(index, panelRows.getChildCount() - 1)));
                if (again != null) again.requestFocus();
                rebuilding = false;
            });
        }, 700);
    }

    private interface SliceRows { void loaded(List<SliceRow> rows); }

    /** Binds a slice on a worker thread, waiting while the provider is still loading it, and parses its rows. */
    @TargetApi(Build.VERSION_CODES.P)
    private void loadSlice(String uri, SliceRows callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            ui.post(() -> callback.loaded(null));   // slices came with Android 9: the rows hide themselves
            return;
        }
        sliceThread.execute(() -> {
            List<SliceRow> rows = null;
            try {
                SliceManager sm = getSystemService(SliceManager.class);
                Set<SliceSpec> specs = new HashSet<>(Arrays.asList(new SliceSpec("androidx.slice.LIST", 1),
                        new SliceSpec("androidx.slice.BASIC", 1)));
                for (int attempt = 0; attempt < 8; attempt++) {
                    Slice slice = sm.bindSlice(Uri.parse(uri), specs);
                    if (slice == null) break;
                    rows = parseSlice(slice);
                    int content = 0;
                    for (SliceRow r : rows) if (!SUB_SCREEN_TITLE.equals(r.type)) content++;
                    // A page still loading carries only its title; the provider fills it in a moment later.
                    if (content > 0) break;
                    Thread.sleep(350);
                }
            } catch (Exception e) {
                rows = null;
            }
            List<SliceRow> result = rows;
            ui.post(() -> {
                if (!isFinishing()) callback.loaded(result);
            });
        });
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static List<SliceRow> parseSlice(Slice slice) {
        List<SliceRow> rows = new ArrayList<>();
        for (SliceItem item : slice.getItems()) {
            if (!SliceItem.FORMAT_SLICE.equals(item.getFormat()) || !item.getHints().contains("list_item")) continue;
            SliceRow r = new SliceRow();
            r.type = item.getSubType();
            for (SliceItem part : item.getSlice().getItems()) {
                String sub = part.getSubType();
                switch (part.getFormat()) {
                    case SliceItem.FORMAT_TEXT:
                        String text = part.getText() == null ? null : part.getText().toString();
                        if (part.getHints().contains("title")) r.title = text;
                        else if ("TAG_RADIO_GROUP".equals(sub)) r.radioGroup = text;
                        else if ("TAG_TARGET_URI".equals(sub)) r.target = text;
                        else if ("TAG_KEY".equals(sub)) r.key = text;
                        else if ("extra_preference_info_summary".equals(sub)) {
                            if (r.summary == null) r.summary = text;
                        } else if (sub == null && part.getHints().isEmpty()) r.summary = text;
                        break;
                    case SliceItem.FORMAT_INT:
                        if ("SUBTYPE_IS_ENABLED".equals(sub)) r.enabled = part.getInt() != 0;
                        break;
                    case SliceItem.FORMAT_SLICE:
                        for (SliceItem a : part.getSlice().getItems()) readAction(r, a);
                        break;
                    case SliceItem.FORMAT_ACTION:
                        readAction(r, part);
                        break;
                    default:
                        break;
                }
            }
            if (r.radioGroup != null) r.toggle = false;   // a radio is sent as a toggle, but drawn as a choice
            rows.add(r);
        }
        return rows;
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static void readAction(SliceRow r, SliceItem a) {
        if (!SliceItem.FORMAT_ACTION.equals(a.getFormat())) return;
        r.action = a.getAction();
        r.toggle = "toggle".equals(a.getSubType());
        r.selected = a.getHints().contains("selected");
    }

    /** The current choice of a page, for the row that opens it: the selected radio, else a switch's state. */
    private String selectedSummary(List<SliceRow> rows) {
        for (SliceRow r : rows) {
            if (r.radioGroup != null && r.selected) {
                return r.summary != null && r.summary.length() <= 8 ? r.title + " · " + r.summary : r.title;
            }
        }
        for (SliceRow r : rows) {
            if (r.toggle && r.title != null) return getString(r.selected ? R.string.hub_on : R.string.hub_off);
        }
        return "";
    }

    // ---- Bluetooth --------------------------------------------------------------------------------

    /*
     * Paired devices, connect and disconnect, forget, search and pair, as in the TV's own settings. What Android 11
     * lets an app without system rights do (checked in the Bluetooth service of this firmware): A2DP and headset
     * connect/disconnect, createBond, removeBond and discovery need BLUETOOTH_ADMIN, a normal permission; input
     * devices (remotes, keyboards, gamepads) connect through HID host, which is reserved to system apps, but they
     * reconnect by themselves when switched on. Discovery also needs location permission and location switched on:
     * when it is off, the page switches it on (GPS only, which a TV box does not have) for the search alone, and
     * puts it back as it was when the search ends, the page is left or, after a crash, the next time it opens.
     */
    private static final String PREFS = "settings_hub";
    private static final String BT_PAGE = "bt:";
    private static final int PROFILE_HID_HOST = 4;   // BluetoothProfile.HID_HOST, a system constant on Android 11
    private static final String HID_STATE_CHANGED = "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";
    private static final ParcelUuid UUID_AUDIO_SINK = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");
    private static final ParcelUuid UUID_HANDSFREE = ParcelUuid.fromString("0000111E-0000-1000-8000-00805F9B34FB");
    private static final ParcelUuid UUID_HEADSET = ParcelUuid.fromString("00001108-0000-1000-8000-00805F9B34FB");
    private static final String PREF_LOCATION_RESTORE = "bt_location_restore";
    private static final int REQUEST_SCAN = 41;

    private final SparseArray<BluetoothProfile> btProfiles = new SparseArray<>();
    /** Devices found by the current or last search that are not paired yet, by address, with their names. */
    private final Map<String, BluetoothDevice> btFound = new LinkedHashMap<>();
    private final Map<String, String> btFoundNames = new LinkedHashMap<>();
    /** Addresses being connected, disconnected or paired, shown as "…" until the stack answers. */
    private final Set<String> btBusy = new HashSet<>();
    private boolean btScanning;
    /** The location mode to put back after a search, -1 when the search did not change it. */
    private int locationBefore = -1;
    private String btForgetArmed;
    private long btForgetArmedAt;
    private BroadcastReceiver btReceiver;
    private final Runnable btRefresh = this::refreshBluetooth;

    @SuppressLint("MissingPermission")
    private void setupBluetooth() {
        restoreLocationAfterCrash();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return;
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                btProfiles.put(profile, proxy);
                scheduleBluetoothRefresh();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                btProfiles.remove(profile);
            }
        };
        for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET, PROFILE_HID_HOST}) {
            try {
                adapter.getProfileProxy(this, listener, profile);
            } catch (RuntimeException ignored) {
            }
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(HID_STATE_CHANGED);
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onBluetoothEvent(intent);
            }
        };
        registerReceiver(btReceiver, filter);
    }

    private void releaseBluetooth() {
        stopScan();
        if (btReceiver != null) {
            unregisterReceiver(btReceiver);
            btReceiver = null;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        for (int i = 0; i < btProfiles.size(); i++) {
            try {
                if (adapter != null) adapter.closeProfileProxy(btProfiles.keyAt(i), btProfiles.valueAt(i));
            } catch (RuntimeException ignored) {
            }
        }
        btProfiles.clear();
        ui.removeCallbacks(btRefresh);
    }

    private void onBluetoothEvent(Intent intent) {
        String action = intent.getAction();
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String address = device != null ? device.getAddress() : null;
        if (action == null) return;
        switch (action) {
            case BluetoothDevice.ACTION_FOUND:
            case BluetoothDevice.ACTION_NAME_CHANGED: {
                if (device == null || bondState(device) == BluetoothDevice.BOND_BONDED) return;
                if (!btScanning && !btFound.containsKey(address)) return;
                String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (name == null) name = deviceName(device);
                if (name == null || name.trim().isEmpty()) return;   // unnamed beacons nobody can pick out
                btFound.put(address, device);
                btFoundNames.put(address, name);
                break;
            }
            case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                if (btScanning) {
                    btScanning = false;
                    restoreLocation();
                }
                break;
            case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                if (device == null) return;
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (state == BluetoothDevice.BOND_BONDED) {
                    btBusy.remove(address);
                    String name = btFoundNames.containsKey(address) ? btFoundNames.get(address) : deviceName(device);
                    btFound.remove(address);
                    btFoundNames.remove(address);
                    toast(getString(R.string.hub_bt_paired_now, name != null ? name : address));
                } else if (state == BluetoothDevice.BOND_NONE) {
                    boolean wasPairing = btBusy.remove(address) && previous == BluetoothDevice.BOND_BONDING;
                    if (wasPairing) toast(getString(R.string.hub_bt_pair_failed));
                }
                break;
            }
            case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
            case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED: {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                int previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                if (address != null && (state == BluetoothProfile.STATE_CONNECTED
                        || state == BluetoothProfile.STATE_DISCONNECTED)) {
                    boolean wasOurs = btBusy.remove(address);
                    if (wasOurs && state == BluetoothProfile.STATE_DISCONNECTED
                            && previous == BluetoothProfile.STATE_CONNECTING) {
                        toast(getString(R.string.hub_bt_connect_failed));
                    }
                }
                break;
            }
            default:
                break;
        }
        scheduleBluetoothRefresh();
    }

    /** Found devices arrive in bursts: one redraw for a burst, not one per device. */
    private void scheduleBluetoothRefresh() {
        ui.removeCallbacks(btRefresh);
        ui.postDelayed(btRefresh, 180);
    }

    private void refreshBluetooth() {
        if (current != BLUETOOTH || isFinishing()) return;
        if (slicePages.isEmpty()) {
            showCategory(BLUETOOTH, false);
            return;
        }
        String page = slicePages.getLast();
        if (!page.startsWith(BT_PAGE)) return;
        BluetoothDevice device = remoteDevice(page.substring(BT_PAGE.length()));
        if (device == null || bondState(device) != BluetoothDevice.BOND_BONDED) {
            goBack();   // forgotten, here or elsewhere
            return;
        }
        keepFocus(() -> renderDevicePage(device));
    }

    /** Rebuilds the panel without losing the focused row (by its position) or switching category. */
    private void keepFocus(Runnable build) {
        View focused = getCurrentFocus();
        int index = focused != null && focused.getParent() == panelRows ? panelRows.indexOfChild(focused) : -1;
        rebuilding = true;
        if (index >= 0) categoryRows.get(current).requestFocus();
        panelRows.removeAllViews();
        build.run();
        if (index >= 0) {
            View again = panelRows.getChildAt(Math.min(index, panelRows.getChildCount() - 1));
            if (again != null) again.requestFocus();
        }
        rebuilding = false;
    }

    @SuppressLint("MissingPermission")
    private void buildBluetooth() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            link(R.drawable.ic_hub_bluetooth, getString(R.string.hub_bt), null, SystemSettings.stock(this, Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        if (adapter == null) {
            info(R.drawable.ic_hub_bluetooth, getString(R.string.hub_bt), getString(R.string.not_available));
            return;
        }
        boolean on = adapter.isEnabled();
        List<BluetoothDevice> paired = pairedDevices(adapter);
        boolean inputConnected = false;
        for (BluetoothDevice d : paired) if (isInput(d) && isConnected(d)) inputConnected = true;
        if (on && inputConnected) {
            // Switching Bluetooth off would leave the TV without its remote: no switch while one is connected.
            info(R.drawable.ic_hub_bluetooth, getString(R.string.hub_bt), getString(R.string.hub_bt_on));
        } else {
            toggle(R.drawable.ic_hub_bluetooth, getString(R.string.hub_bt),
                    getString(on ? R.string.hub_bt_on_plain : R.string.hub_bt_off), on, true, enable -> {
                        if (enable) adapter.enable();
                        else adapter.disable();
                    });
        }
        if (!on) return;

        section(R.string.hub_sec_devices);
        if (paired.isEmpty()) info(R.drawable.ic_hub_bluetooth, getString(R.string.hub_bt_none), "");
        for (BluetoothDevice device : paired) {
            String name = deviceName(device);
            LinearLayout row = panelRow(deviceIcon(device), name != null ? name : device.getAddress(), "");
            setValue(row, getString(btBusy.contains(device.getAddress()) ? R.string.hub_bt_working
                    : isConnected(device) ? R.string.hub_bt_connected : R.string.hub_bt_paired));
            addChevron(row);
            String address = device.getAddress();
            row.setOnClickListener(v -> openSlicePage(BT_PAGE + address, true));
        }

        section(R.string.hub_sec_bt_add);
        LinearLayout scan = panelRow(R.drawable.ic_hub_bluetooth,
                getString(btScanning ? R.string.hub_bt_scanning : R.string.hub_bt_scan), "");
        setSubtitle(scan, getString(btScanning ? R.string.hub_bt_scanning_hint : R.string.hub_bt_scan_hint));
        scan.setOnClickListener(v -> {
            if (btScanning) stopScan();
            else startScan();
            scheduleBluetoothRefresh();
        });
        for (Map.Entry<String, BluetoothDevice> e : btFound.entrySet()) {
            BluetoothDevice device = e.getValue();
            LinearLayout row = panelRow(deviceIcon(device), btFoundNames.get(e.getKey()), "");
            setValue(row, getString(btBusy.contains(e.getKey()) ? R.string.hub_bt_pairing : R.string.hub_bt_pair_action));
            row.setOnClickListener(v -> pair(device));
        }
        if (!btScanning && btFound.isEmpty() && scanEverRan) {
            info(R.drawable.ic_hub_info, getString(R.string.hub_bt_found_none), "");
        }
        Intent system = new Intent().setComponent(new ComponentName(TV_SETTINGS, TV_SETTINGS + ".accessories.AddAccessoryActivity"));
        link(R.drawable.ic_hub_remote, getString(R.string.hub_bt_pair), null, resolves(system) ? system : null);
    }

    private boolean scanEverRan;

    @SuppressLint("MissingPermission")
    private void renderDevicePage(BluetoothDevice device) {
        String address = device.getAddress();
        String name = deviceName(device);
        panelTitle.setText(name != null ? name : address);
        boolean connected = isConnected(device);
        boolean busy = btBusy.contains(address);
        info(deviceIcon(device), getString(R.string.hub_bt_state), getString(busy ? R.string.hub_bt_working
                : connected ? R.string.hub_bt_connected : R.string.hub_bt_not_connected));
        info(R.drawable.ic_hub_info, getString(R.string.hub_bt_kind), getString(kindLabel(device)));

        if (isAudio(device)) {
            LinearLayout row = panelRow(R.drawable.ic_hub_headset,
                    getString(connected ? R.string.hub_bt_disconnect : R.string.hub_bt_connect), "");
            if (busy) setValue(row, getString(R.string.hub_bt_working));
            row.setOnClickListener(v -> {
                if (btBusy.contains(address)) return;
                audioConnection(device, !isConnected(device));
            });
        } else if (isInput(device)) {
            LinearLayout row = panelRow(R.drawable.ic_hub_remote, getString(R.string.hub_bt_input_title), "");
            setSubtitle(row, getString(R.string.hub_bt_input_hint));
        }

        LinearLayout forget = panelRow(R.drawable.ic_hub_close, getString(R.string.hub_bt_forget), "");
        boolean armed = address.equals(btForgetArmed) && SystemClock.uptimeMillis() - btForgetArmedAt < 5000;
        if (armed) setValue(forget, getString(R.string.hub_bt_forget_confirm));
        if (isInput(device) && connected) setSubtitle(forget, getString(R.string.hub_bt_forget_input_warning));
        forget.setOnClickListener(v -> {
            boolean stillArmed = address.equals(btForgetArmed) && SystemClock.uptimeMillis() - btForgetArmedAt < 5000;
            if (!stillArmed) {
                // A first OK only asks: forgetting the remote by a slip would leave the TV without one.
                btForgetArmed = address;
                btForgetArmedAt = SystemClock.uptimeMillis();
                setValue(forget, getString(R.string.hub_bt_forget_confirm));
                nudge(forget, 1);
                return;
            }
            btForgetArmed = null;
            if (!invokeBoolean(device, "removeBond")) toast(getString(R.string.not_available));
        });
    }

    @SuppressLint("MissingPermission")
    private void audioConnection(BluetoothDevice device, boolean connect) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isDiscovering()) stopScan();
        String method = connect ? "connect" : "disconnect";
        boolean sent = false;
        BluetoothProfile a2dp = btProfiles.get(BluetoothProfile.A2DP);
        if (a2dp != null && (!connect || hasUuid(device, UUID_AUDIO_SINK) || device.getUuids() == null
                || majorClass(device) == BluetoothClass.Device.Major.AUDIO_VIDEO)) {
            sent |= invokeProfile(a2dp, method, device);
        }
        BluetoothProfile headset = btProfiles.get(BluetoothProfile.HEADSET);
        if (headset != null && (!connect || hasUuid(device, UUID_HANDSFREE) || hasUuid(device, UUID_HEADSET))) {
            sent |= invokeProfile(headset, method, device);
        }
        if (!sent) {
            toast(getString(R.string.not_available));
            return;
        }
        String address = device.getAddress();
        btBusy.add(address);
        // The stack answers with a connection state broadcast; a device that is off or away never answers.
        ui.postDelayed(() -> {
            if (btBusy.remove(address)) {
                if (connect && !isConnected(device)) toast(getString(R.string.hub_bt_connect_failed));
                scheduleBluetoothRefresh();
            }
        }, 20000);
        scheduleBluetoothRefresh();
    }

    @SuppressLint("MissingPermission")
    private void pair(BluetoothDevice device) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        String address = device.getAddress();
        if (btBusy.contains(address)) return;
        stopScan();   // pairing while discovering is slow and often fails
        boolean started;
        try {
            started = device.createBond();
        } catch (SecurityException e) {
            started = false;
        }
        if (!started) {
            toast(getString(R.string.hub_bt_pair_failed));
            return;
        }
        btBusy.add(address);
        scheduleBluetoothRefresh();
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_SCAN);
            return;
        }
        ContentResolver cr = getContentResolver();
        int mode = Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
        if (mode == Settings.Secure.LOCATION_MODE_OFF) {
            if (!canWriteSecure()) {
                toast(getString(R.string.hub_bt_scan_needs_location));
                return;
            }
            try {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_LOCATION_RESTORE, mode).commit();
                Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_SENSORS_ONLY);
                locationBefore = mode;
            } catch (SecurityException e) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_LOCATION_RESTORE).apply();
                toast(getString(R.string.hub_bt_scan_needs_location));
                return;
            }
        }
        btFound.clear();
        btFoundNames.clear();
        boolean started;
        try {
            started = adapter.startDiscovery();
        } catch (SecurityException e) {
            started = false;
        }
        if (!started) {
            restoreLocation();
            toast(getString(R.string.not_available));
            return;
        }
        btScanning = true;
        scanEverRan = true;
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (btScanning) {
            btScanning = false;
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            try {
                if (adapter != null) adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
            }
        }
        restoreLocation();
    }

    private void restoreLocation() {
        if (locationBefore < 0) return;
        try {
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.LOCATION_MODE, locationBefore);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_LOCATION_RESTORE).apply();
        } catch (SecurityException ignored) {
        }
        locationBefore = -1;
    }

    /** A search cut short by a crash or a force stop left location on: put it back as it was. */
    private void restoreLocationAfterCrash() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains(PREF_LOCATION_RESTORE)) return;
        locationBefore = prefs.getInt(PREF_LOCATION_RESTORE, Settings.Secure.LOCATION_MODE_OFF);
        restoreLocation();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_SCAN && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
            scheduleBluetoothRefresh();
        }
    }

    @SuppressLint("MissingPermission")
    private static List<BluetoothDevice> pairedDevices(BluetoothAdapter adapter) {
        List<BluetoothDevice> list = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) list.addAll(bonded);
        } catch (SecurityException ignored) {
        }
        return list;
    }

    private BluetoothDevice remoteDevice(String address) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        try {
            return adapter != null && BluetoothAdapter.checkBluetoothAddress(address) ? adapter.getRemoteDevice(address) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private static int bondState(BluetoothDevice device) {
        try {
            return device.getBondState();
        } catch (SecurityException e) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    @SuppressLint("MissingPermission")
    private static String deviceName(BluetoothDevice device) {
        try {
            String alias = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? device.getAlias() : null;
            return alias != null ? alias : device.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private static int majorClass(BluetoothDevice device) {
        try {
            BluetoothClass cls = device.getBluetoothClass();
            return cls != null ? cls.getMajorDeviceClass() : -1;
        } catch (SecurityException e) {
            return -1;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean hasUuid(BluetoothDevice device, ParcelUuid uuid) {
        try {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids == null) return false;
            for (ParcelUuid u : uuids) if (uuid.equals(u)) return true;
        } catch (SecurityException ignored) {
        }
        return false;
    }

    private static boolean isAudio(BluetoothDevice device) {
        return majorClass(device) == BluetoothClass.Device.Major.AUDIO_VIDEO || hasUuid(device, UUID_AUDIO_SINK)
                || hasUuid(device, UUID_HANDSFREE) || hasUuid(device, UUID_HEADSET);
    }

    private boolean isInput(BluetoothDevice device) {
        if (majorClass(device) == BluetoothClass.Device.Major.PERIPHERAL) return true;
        BluetoothProfile hid = btProfiles.get(PROFILE_HID_HOST);
        try {
            return hid != null && hid.getConnectionState(device) != BluetoothProfile.STATE_DISCONNECTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int kindLabel(BluetoothDevice device) {
        switch (majorClass(device)) {
            case BluetoothClass.Device.Major.AUDIO_VIDEO: return R.string.hub_bt_kind_audio;
            case BluetoothClass.Device.Major.PHONE: return R.string.hub_bt_kind_phone;
            case BluetoothClass.Device.Major.PERIPHERAL: return R.string.hub_bt_kind_input;
            case BluetoothClass.Device.Major.COMPUTER: return R.string.hub_bt_kind_computer;
            default: return R.string.hub_bt_kind_other;
        }
    }

    /** Connected on any profile we can see, or (hidden API, read by reflection) at the link level. */
    private boolean isConnected(BluetoothDevice device) {
        for (int i = 0; i < btProfiles.size(); i++) {
            try {
                if (btProfiles.valueAt(i).getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) return true;
            } catch (RuntimeException ignored) {
            }
        }
        return invokeBoolean(device, "isConnected");
    }

    /** BluetoothDevice's hidden isConnected() and removeBond(), allowed to apps on Android 11 (greylist). */
    private static boolean invokeBoolean(BluetoothDevice device, String name) {
        try {
            Object r = BluetoothDevice.class.getMethod(name).invoke(device);
            return r instanceof Boolean && (Boolean) r;
        } catch (Exception e) {
            return false;
        }
    }

    /** BluetoothA2dp and BluetoothHeadset connect()/disconnect(): hidden, BLUETOOTH_ADMIN on Android 11. */
    private static boolean invokeProfile(BluetoothProfile proxy, String name, BluetoothDevice device) {
        try {
            Method m = proxy.getClass().getMethod(name, BluetoothDevice.class);
            Object r = m.invoke(proxy, device);
            return r instanceof Boolean && (Boolean) r;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** The pill_text selectors live in drawable/, as the launcher's layouts reference them; they are colour lists. */
    @SuppressLint("ResourceType")
    private ColorStateList states(int id) {
        return getColorStateList(id);
    }

    private void apply(Runnable write) {
        try {
            write.run();
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.hub_needs_permission, Toast.LENGTH_LONG).show();
        }
    }

    private void open(Intent target) {
        if (target == null) return;
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean resolves(Intent intent) {
        return getPackageManager().resolveActivity(intent, 0) != null;
    }

    private Intent displaySound() {
        Intent explicit = new Intent().setComponent(new ComponentName(TV_SETTINGS,
                TV_SETTINGS + ".device.displaysound.DisplaySoundActivity"));
        return resolves(explicit) ? explicit : SystemSettings.stock(this, Settings.ACTION_SOUND_SETTINGS);
    }

    private boolean canWriteSecure() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private String dnsStatus() {
        ContentResolver cr = getContentResolver();
        if (!"hostname".equals(Settings.Global.getString(cr, "private_dns_mode"))) return getString(R.string.hub_dns_off);
        String host = Settings.Global.getString(cr, "private_dns_specifier");
        String shown = host == null ? "" : host.contains("hagezi-proplus") ? "Hagezi Pro++ · Control D" : host;
        return getString(R.string.hub_dns_on, shown);
    }

    private static boolean vpnActive(ConnectivityManager cm) {
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private static String ipv4(ConnectivityManager cm, Network network) {
        if (cm == null || network == null) return "—";
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) return "—";
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return "—";
    }

    private String hdrTypes(Display.HdrCapabilities hdr) {
        if (hdr == null || hdr.getSupportedHdrTypes().length == 0) return getString(R.string.hub_hdr_none);
        StringBuilder sb = new StringBuilder();
        for (int type : hdr.getSupportedHdrTypes()) {
            String n = type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION ? "Dolby Vision"
                    : type == Display.HdrCapabilities.HDR_TYPE_HDR10 ? "HDR10"
                    : type == Display.HdrCapabilities.HDR_TYPE_HLG ? "HLG"
                    : type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS ? "HDR10+" : null;
            if (n == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
        }
        return sb.length() > 0 ? sb.toString() : getString(R.string.hub_hdr_none);
    }

    @SuppressLint("MissingPermission")
    private static int deviceIcon(BluetoothDevice device) {
        BluetoothClass cls;
        try {
            cls = device.getBluetoothClass();
        } catch (SecurityException e) {
            cls = null;
        }
        if (cls == null) return R.drawable.ic_hub_remote;
        switch (cls.getMajorDeviceClass()) {
            case BluetoothClass.Device.Major.AUDIO_VIDEO:
                int d = cls.getDeviceClass();
                return d == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ? R.drawable.ic_hub_speaker
                        : R.drawable.ic_hub_headset;
            case BluetoothClass.Device.Major.PHONE:
                return R.drawable.ic_hub_phone;
            default:
                return R.drawable.ic_hub_remote;
        }
    }

    private void tintGlow(int color) {
        if (color == glowColor) return;
        int from = glowColor;
        glowColor = color;
        if (glowAnimator != null) glowAnimator.cancel();
        if (!animate) {
            glow.setColors(new int[]{withAlpha(color, 0x70), withAlpha(color, 0x22), 0x00000000});
            return;
        }
        ArgbEvaluator eval = new ArgbEvaluator();
        glowAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(420);
        glowAnimator.setInterpolator(new DecelerateInterpolator());
        glowAnimator.addUpdateListener(a -> {
            int c = (int) eval.evaluate(a.getAnimatedFraction(), from, color);
            glow.setColors(new int[]{withAlpha(c, 0x70), withAlpha(c, 0x22), 0x00000000});
        });
        glowAnimator.start();
    }

    /** A gentle zoom on focus, eased out, so the white highlight seems to rise towards the viewer. */
    private void pop(View v, boolean focused, float scale) {
        if (!animate) return;
        v.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f).setDuration(focused ? 180 : 140)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private void nudge(View v, int direction) {
        if (!animate) return;
        v.setTranslationX(dp(6) * direction);
        v.animate().translationX(0f).setDuration(160).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private void entrance() {
        // Categories cascade in from the left, the panel rises a little: the page settles like a macOS window.
        for (int i = 0; i < categoryRows.size(); i++) {
            View row = categoryRows.get(i);
            row.setAlpha(0f);
            row.setTranslationX(-dp(14));
            row.animate().alpha(1f).translationX(0f).setStartDelay(30L * i).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        View panel = (View) panelScroll.getParent();
        panel.setAlpha(0f);
        panel.setTranslationY(dp(16));
        panel.animate().alpha(1f).translationY(0f).setStartDelay(80).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private TextView text(String s, int sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (face != null) t.setTypeface(face);
        if (color != 0) t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ---- small custom views -----------------------------------------------------------------------

    /** A macOS-style switch: green track when on, the white knob slides across. Dark parts on a focused row. */
    private static final class SwitchView extends View {
        private static final ArgbEvaluator EVALUATOR = new ArgbEvaluator();
        boolean on;
        private float position;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        SwitchView(Context context, boolean on) {
            super(context);
            this.on = on;
            position = on ? 1f : 0f;
        }

        void setOn(boolean value, boolean animated) {
            on = value;
            if (!animated) {
                position = on ? 1f : 0f;
                invalidate();
                return;
            }
            ValueAnimator a = ValueAnimator.ofFloat(position, on ? 1f : 0f).setDuration(200);
            a.setInterpolator(new DecelerateInterpolator(1.8f));
            a.addUpdateListener(x -> {
                position = (float) x.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            boolean focused = isFocused() || (getParent() instanceof View && ((View) getParent()).isFocused());
            int offColor = focused ? 0x33000000 : 0x40FFFFFF;
            paint.setColor((Integer) EVALUATOR.evaluate(position, offColor, 0xFF34C759));
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, paint);
            float inset = h * 0.1f;
            float knob = h - 2 * inset;
            float x = inset + position * (w - 2 * inset - knob);
            paint.setColor(0xFFFFFFFF);
            paint.setShadowLayer(inset, 0, inset / 2f, 0x40000000);
            canvas.drawOval(x, inset, x + knob, inset + knob, paint);
            paint.clearShadowLayer();
        }
    }

    /** A thin level bar for the volume row: white fill on the glass, dark fill on the focused white row. */
    private static final class BarView extends View {
        final int max;
        int value;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        BarView(Context context, int max, int value) {
            super(context);
            this.max = Math.max(1, max);
            this.value = value;
        }

        void set(int v) {
            value = v;
            invalidate();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            boolean focused = getParent() instanceof View && ((View) getParent()).isFocused();
            paint.setColor(focused ? 0x26000000 : 0x33FFFFFF);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setColor(focused ? 0xFF111111 : 0xFFFFFFFF);
            rect.set(0, 0, Math.max(h, w * value / (float) max), h);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }
}
