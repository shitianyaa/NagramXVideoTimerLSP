package com.shitianyaa.nagramx.videotimer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.ScrollView;

import java.util.HashMap;
import java.util.Map;

/**
 * 取色与度量：提供与宿主保持一致的配色、对比度校验与动效曲线。
 */
final class HostTheme {

    public static final int WHITE = 0xFFFFFFFF;
    public static final int DARK_INK = 0xFF121212;
    public static final int TRANSPARENT = 0;
    private static final int DEFAULT_DARK_ACCENT = 0xFF1A9CFF;
    private static final int DEFAULT_LIGHT_ACCENT = 0xFF168ACD;

    private static final class EaseOutQuintHolder {
        static final Interpolator INSTANCE = new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    }

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

    Drawable rippleRoundBorderRect(Context context, float radiusDp, int background,
                                   int strokeWidthDp, int strokeColor, int ripple) {
        GradientDrawable content = roundRect(context, radiusDp, background);
        if (strokeWidthDp > 0) {
            content.setStroke(dp(context, strokeWidthDp), strokeColor);
        }
        return new RippleDrawable(
                ColorStateList.valueOf(ripple),
                content,
                roundRect(context, radiusDp, WHITE)
        );
    }

    int accentColor(int background) {
        int fallback = isLight(background) ? DEFAULT_LIGHT_ACCENT : DEFAULT_DARK_ACCENT;
        return resolveAccent(
                background,
                activeColor("dialogTextBlue2"),
                activeColor("windowBackgroundWhiteBlueText"),
                activeColor("featuredStickers_addButton"),
                fallback
        );
    }

    int primaryButtonFill(int background) {
        return resolvePrimaryFill(
                background,
                activeColor("featuredStickers_addButton"),
                accentColor(background),
                isLight(background) ? DEFAULT_LIGHT_ACCENT : DEFAULT_DARK_ACCENT
        );
    }

    int primaryButtonText(int fillColor) {
        return resolveButtonText(fillColor, activeColor("featuredStickers_buttonText"));
    }

    int resolveDarkAccent(int fallback) {
        return resolveAccent(
                0xFF1F1F1F,
                activeColor("dialogTextBlue2"),
                activeColor("windowBackgroundWhiteBlueText"),
                activeColor("featuredStickers_addButton"),
                fallback
        );
    }

    static Interpolator easeOutQuint() {
        return EaseOutQuintHolder.INSTANCE;
    }

    static void tapHaptic(View view) {
        if (view == null) return;
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Throwable ignored) {
        }
    }

    static void tickHaptic(View view) {
        if (view == null) return;
        try {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } catch (Throwable ignored) {
        }
    }

    static boolean isLight(int color) {
        return relativeLuminance(color) > 0.5;
    }

    static double relativeLuminance(int color) {
        double r = linearChannel(((color >>> 16) & 0xFF) / 255.0);
        double g = linearChannel(((color >>> 8) & 0xFF) / 255.0);
        double b = linearChannel((color & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linearChannel(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    static double contrastRatio(int first, int second) {
        double l1 = relativeLuminance(first);
        double l2 = relativeLuminance(second);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    static int resolveAccent(int background, int primaryCandidate, int secondaryCandidate,
                             int tertiaryCandidate, int fallback) {
        int[] candidates = {primaryCandidate, secondaryCandidate, tertiaryCandidate};
        for (int candidate : candidates) {
            if (isUsableFill(candidate, background, 2.6)) {
                return candidate;
            }
        }
        return fallback;
    }

    static int resolvePrimaryFill(int background, int buttonFillCandidate,
                                  int accentCandidate, int fallback) {
        if (isUsableFill(buttonFillCandidate, background, 2.6)) {
            return buttonFillCandidate;
        }
        if (isUsableFill(accentCandidate, background, 2.6)) {
            return accentCandidate;
        }
        return fallback;
    }

    static int resolveButtonText(int fillColor, int preferredTextColor) {
        double fillLuminance = relativeLuminance(fillColor);
        if (isOpaqueEnough(preferredTextColor)) {
            if (fillLuminance <= 0.52 && relativeLuminance(preferredTextColor) >= 0.75) {
                return preferredTextColor;
            }
            if (contrastRatio(preferredTextColor, fillColor) >= 3.5) {
                return preferredTextColor;
            }
        }
        return fillLuminance > 0.52 ? DARK_INK : WHITE;
    }

    Drawable createTimerIcon(Context context, int color) {
        return ListenVideoIcons.timer(context, color);
    }

    View createDragHandle(Context context, int color) {
        View handle = new View(context);
        handle.setBackground(roundRect(context, 2, color));
        return handle;
    }

    static ScrollView pullDownScrollView(Context context) {
        ScrollView view = new ScrollView(context) {
            private final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            private float startX;
            private float startY;
            private boolean decided;
            private boolean childDisallowed;

            @Override
            public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
                if (disallowIntercept) {
                    childDisallowed = true;
                    decided = true;
                }
                super.requestDisallowInterceptTouchEvent(disallowIntercept);
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                ViewParent parent = getParent();
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    startX = event.getX();
                    startY = event.getY();
                    decided = false;
                    childDisallowed = false;
                    if (parent != null) {
                        // Hold BottomSheet swipe-to-dismiss until drag direction is resolved.
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                } else if (action == MotionEvent.ACTION_MOVE && !decided && !childDisallowed && parent != null) {
                    float dx = event.getX() - startX;
                    float dy = event.getY() - startY;
                    if (Math.abs(dy) > touchSlop || Math.abs(dx) > touchSlop) {
                        decided = true;
                        boolean pullDownAtTop = dy > touchSlop && dy > Math.abs(dx)
                                && getScrollY() <= 0 && !canScrollVertically(-1);
                        parent.requestDisallowInterceptTouchEvent(!pullDownAtTop);
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    decided = false;
                    childDisallowed = false;
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
                return super.dispatchTouchEvent(event);
            }
        };
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return view;
    }

    private static boolean isOpaqueEnough(int color) {
        return ((color >>> 24) & 0xFF) >= 200;
    }

    private static boolean isUsableFill(int color, int background, double minContrast) {
        return isOpaqueEnough(color) && contrastRatio(color, background) >= minContrast;
    }
}
