package com.shitianyaa.nagramx.videotimer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

import java.util.HashMap;
import java.util.Map;

/**
 * 取色与度量：提供与宿主 PhotoViewer 保持一致的深色外观。
 */
final class HostTheme {

    public static final int WHITE = 0xFFFFFFFF;
    public static final int TRANSPARENT = 0;

    private final HostBridge bridge;
    private final Class<?> themeClass;
    private final Class<?> androidUtilities;
    final Class<?> resourcesProviderClass;

    private Object darkProvider;
    private boolean darkProviderLoaded;

    private final Map<String, Integer> keyCache = new HashMap<>();
    private final Map<String, Integer> colorCache = new HashMap<>();

    private static final Map<String, Integer> DEFAULTS = new HashMap<>();

    static {
        DEFAULTS.put("dialogBackground", 0xFF1F1F1F);
        DEFAULTS.put("dialogTextBlack", -592138);
        DEFAULTS.put("dialogTextGray2", -8553091);
        DEFAULTS.put("dialogTextGray3", -8553091);
        DEFAULTS.put("dialogTextBlue2", 0xFF1A9CFF);
        DEFAULTS.put("dialogButton", -10177041);
        DEFAULTS.put("dialogButtonSelector", 436207615);
        DEFAULTS.put("dialogTextHint", -8553091);
        DEFAULTS.put("listSelector", 0x16FFFFFF);
        DEFAULTS.put("divider", 0xFF000000);
        DEFAULTS.put("sheet_scrollUp", 0xFF333333);
        DEFAULTS.put("featuredStickers_addButton", 0xFF1A9CFF);
        DEFAULTS.put("featuredStickers_buttonText", WHITE);
        DEFAULTS.put("windowBackgroundWhiteBlackText", WHITE);
        DEFAULTS.put("windowBackgroundWhiteGrayText", 0x7FFFFFFF);
        DEFAULTS.put("text_RedRegular", -1152913);
        DEFAULTS.put("player_actionBarTitle", WHITE);
        DEFAULTS.put("graySection", 0xFF292929);
        DEFAULTS.put("graySectionText", -8158332);
    }

    HostTheme(HostBridge bridge, HostBridge.Logger logger) {
        this.bridge = bridge;
        this.themeClass = bridge.loadClass("org.telegram.ui.ActionBar.Theme");
        this.androidUtilities = bridge.loadClass("org.telegram.messenger.AndroidUtilities");
        this.resourcesProviderClass = bridge.loadClass("org.telegram.ui.ActionBar.Theme$ResourcesProvider");
    }

    synchronized Object getDarkProvider() {
        if (!darkProviderLoaded) {
            darkProviderLoaded = true;
            darkProvider = bridge.newInstance("org.telegram.ui.Stories.DarkThemeResourceProvider");
        }
        return darkProvider;
    }

    private Integer keyOf(String name) {
        Integer cached = keyCache.get(name);
        if (cached != null) return cached;
        Object val = bridge.getStaticField(themeClass, "key_" + name);
        if (val instanceof Number) {
            int key = ((Number) val).intValue();
            keyCache.put(name, key);
            return key;
        }
        return null;
    }

    int color(String name, int fallback) {
        Integer key = keyOf(name);
        if (key == null) return fallback;
        Object actual = bridge.invokeStatic(themeClass, "getColor", key);
        return actual instanceof Number ? ((Number) actual).intValue() : fallback;
    }

    int activeColor(String name) {
        Integer key = keyOf(name);
        Object actual = key == null ? null : bridge.invokeStatic(themeClass, "getColor", key);
        return actual instanceof Number ? ((Number) actual).intValue() : color(name);
    }

    int color(String name) {
        Integer cached = colorCache.get(name);
        if (cached != null) return cached;

        Integer key = keyOf(name);
        if (key != null) {
            Object fromProvider = bridge.invokeNamed(getDarkProvider(), "getColor", key);
            if (fromProvider instanceof Number) {
                int c = ((Number) fromProvider).intValue();
                colorCache.put(name, c);
                return c;
            }
            Object fromTheme = bridge.invokeStatic(themeClass, "getColor", key);
            if (fromTheme instanceof Number) {
                int c = ((Number) fromTheme).intValue();
                colorCache.put(name, c);
                return c;
            }
        }

        Integer def = DEFAULTS.get(name);
        int c = def != null ? def : WHITE;
        colorCache.put(name, c);
        return c;
    }

    int dp(Context context, float value) {
        if (value == 0f) return 0;
        float density = context.getResources().getDisplayMetrics().density;
        return (int) Math.ceil(density * value);
    }

    int dp(Context context, int value) {
        return dp(context, (float) value);
    }

    Typeface bold() {
        Object val = bridge.invokeStatic(androidUtilities, "bold");
        return val instanceof Typeface ? (Typeface) val : Typeface.DEFAULT_BOLD;
    }

    int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    int multAlpha(int color, float factor) {
        int a = (int) (((color >>> 24)) * factor);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return alpha(color, a);
    }

    GradientDrawable roundRect(Context context, float radiusDp, int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius((float) dp(context, radiusDp));
        gd.setColor(color);
        return gd;
    }

    Drawable rippleRect(Context context, float radiusDp, int background, int ripple) {
        return new RippleDrawable(
                ColorStateList.valueOf(ripple),
                roundRect(context, radiusDp, background),
                roundRect(context, radiusDp, WHITE)
        );
    }

    Drawable rippleTransparent(Context context, float radiusDp, int ripple) {
        return rippleRect(context, radiusDp, TRANSPARENT, ripple);
    }
}
