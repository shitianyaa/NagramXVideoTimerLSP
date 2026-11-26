package com.shitianyaa.nagramx.videotimer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;

final class ListenVideoPage {
    private static final long UPDATE_MS = 350L;

    private final Activity activity;
    private final ListenVideoAgent player;
    private final HostTheme theme;
    private final HostStrings strings;
    private final VideoPlaylistSheet playlist;
    private final Dialog dialog;
    private final LinearLayout body;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int primary;
    private final int secondary;
    private final int accent;
    private final int background;
    private final int coverColor;
    private final View coverThumbnail;
    private final TextView title;
    private final TextView elapsed;
    private final TextView timerRemaining;
    private final View timer;
    private final TextView total;
    private final View progress;
    private final HostListenControls hostControls;
    private final ImageView play;
    private final HostBridge bridge;
    private final Drawable playIcon;
    private final String setPause;
    private final View previous;
    private final View next;
    private final Runnable update = this::refresh;
    private Object displayed;
    private Object seeking;
    private float displayedSpeed = Float.NaN;
    private Boolean displayedPaused;
    private String displayedTimer;
    private boolean opened;

    ListenVideoPage(Activity activity, ListenVideoAgent player, HostProfile host, HostTheme theme,
                    HostStrings strings, HostBridge.Logger logger) {
        this.activity = activity;
        this.player = player;
        this.theme = theme;
        this.strings = strings;
        bridge = host.bridge;
        setPause = host.niagram1322 ? "d" : "setPause";
        Object drawable = bridge.newInstance("org.telegram.ui.Components.PlayPauseDrawable", 28);
        playIcon = drawable instanceof Drawable
                && bridge.findMethod(drawable.getClass(), setPause, 2, m ->
                        m.getParameterTypes()[0] == boolean.class && m.getParameterTypes()[1] == boolean.class) != null
                ? (Drawable) drawable : null;
        playlist = new VideoPlaylistSheet(host, theme, strings, logger);
        primary = theme.color("windowBackgroundWhiteBlackText", 0xFF222222);
        secondary = theme.color("windowBackgroundWhiteGrayText", 0xFF707579);
        accent = theme.color("windowBackgroundWhiteBlueText", 0xFF168ACD);
        background = theme.color("windowBackgroundWhite", Color.WHITE);
        coverColor = theme.color("windowBackgroundGray", 0xFFE9EFF4);
        hostControls = new HostListenControls(activity, host, strings, primary, accent);

        Class<?> sheetClass = bridge.loadClass("org.telegram.ui.ActionBar.BottomSheet");
        Object sheet = bridge.newInstance(sheetClass, (Context) activity, false, null);
        if (!(sheet instanceof Dialog)) throw new IllegalStateException("Host BottomSheet unavailable");
        dialog = (Dialog) sheet;
        bridge.invokeNamed(dialog, "setApplyTopPadding", false);
        bridge.invokeNamed(dialog, "setApplyBottomPadding", false);
        bridge.invokeNamed(dialog, "setOpenNoDelay", true);
        bridge.invokeNamed(dialog, "transitionFromRight", true);
        bridge.invokeNamed(dialog, "setCanDismissWithSwipe", false);
        bridge.invokeNamed(dialog, "setCanDismissWithTouchOutside", false);
        bridge.setField(dialog, "fullWidth", true);
        bridge.setField(dialog, "backgroundPaddingTop", 0);
        bridge.setField(dialog, "backgroundPaddingLeft", 0);
        bridge.setField(dialog, "occupyNavigationBar", true);
        bridge.invokeNamed(dialog, "setBackgroundColor", background);
        bridge.invokeNamed(dialog, "setUseLightStatusBar", Color.luminance(background) > 0.5);
        dialog.setOnDismissListener(d -> {
            main.removeCallbacks(update);
            player.pageClosed(this);
        });
        // BottomSheet measures its custom view as wrap-content. Fill the available viewport.
        body = new LinearLayout(activity) {
            @Override protected void onMeasure(int width, int height) {
                if (MeasureSpec.getMode(height) == MeasureSpec.AT_MOST) {
                    height = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(height), MeasureSpec.EXACTLY);
                }
                super.onMeasure(width, height);
            }
        };
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(background);

        FrameLayout header = new FrameLayout(activity);
        Object actionBar = bridge.newInstance("org.telegram.ui.ActionBar.ActionBar", (Context) activity);
        if (!(actionBar instanceof View)) throw new IllegalStateException("Host ActionBar unavailable");
        bridge.invokeNamed(actionBar, "setOccupyStatusBar", false);
        bridge.invokeNamed(actionBar, "setCastShadows", false);
        bridge.invokeNamed(actionBar, "setTitle", strings.get(activity, "ListenVideo"));
        bridge.invokeNamed(actionBar, "setTitleColor", primary);
        bridge.invokeNamed(actionBar, "setItemsColor", primary, false);
        bridge.invokeNamed(actionBar, "setItemsBackgroundColor", theme.alpha(primary, 22), false);
        int back = activity.getResources().getIdentifier("ic_ab_back", "drawable", activity.getPackageName());
        bridge.invokeNamed(actionBar, "setBackButtonImage", back);
        Object backButton = bridge.invokeNamed(actionBar, "getBackButton");
        if (backButton instanceof View) ((View) backButton).setOnClickListener(view -> dialog.dismiss());
        header.addView((View) actionBar, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout timerGroup = new LinearLayout(activity);
        timerGroup.setGravity(Gravity.CENTER_VERTICAL);
        timerRemaining = text("", 13, accent);
        timerRemaining.setSingleLine(true);
        timerRemaining.setEllipsize(TextUtils.TruncateAt.END);
        timerRemaining.setMaxWidth(dp(96));
        timerRemaining.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        timerRemaining.setVisibility(View.GONE);
        timerRemaining.setOnClickListener(view -> player.openTimer(activity));
        timerGroup.addView(timerRemaining, new LinearLayout.LayoutParams(-2, -1));
        timer = hostControls.action("msg_autodelete", "VideoSleepTimer", () -> player.openTimer(activity));
        timerGroup.addView(timer, new LinearLayout.LayoutParams(dp(56), -1));
        header.addView(timerGroup, new FrameLayout.LayoutParams(-2, -1, Gravity.END));
        body.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));
        View divider = new View(activity);
        divider.setBackgroundColor(theme.alpha(secondary, 35));
        body.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = column();
        content.setPadding(dp(24), dp(24), dp(24), dp(12));
        content.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout cover = new FrameLayout(activity);
        cover.setBackground(theme.roundRect(activity, 12, coverColor));
        cover.setClipToOutline(true);
        int coverHeight = Math.min(dp(300), Math.min(activity.getResources().getDisplayMetrics().widthPixels * 9 / 16,
                activity.getResources().getDisplayMetrics().heightPixels / 2));
        coverThumbnail = playlist.createThumbnail(activity);
        playlist.prepareCover(coverThumbnail);
        cover.addView(coverThumbnail, new FrameLayout.LayoutParams(-1, -1));
        cover.setOnClickListener(view -> watch());
        cover.setContentDescription(strings.get(activity, "ListenVideoBack"));
        content.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight));

        title = text("", 21, primary);
        title.setTypeface(theme.bold());
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(-1, -2);
        titleSize.topMargin = dp(16);
        content.addView(title, titleSize);

        TextView watch = text(strings.get(activity, "ListenVideoBack"), 15, accent);
        watch.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        watch.setTypeface(theme.bold());
        watch.setBackground(theme.rippleTransparent(activity, 6, theme.alpha(accent, 35)));
        watch.setOnClickListener(view -> watch());
        content.addView(watch, new LinearLayout.LayoutParams(-1, dp(48)));
        scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout controls = column();
        controls.setPadding(dp(16), dp(8), dp(16), dp(12));
        progress = hostControls.seekBar(this);
        controls.addView(progress, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout times = new LinearLayout(activity);
        times.setPadding(dp(12), 0, dp(12), 0);
        elapsed = text("0:00", 13, secondary);
        total = text("0:00", 13, secondary);
        total.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(total, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER);
        View speed = hostControls.speed(player);
        buttons.addView(speed, new LinearLayout.LayoutParams(0, dp(56), 1));
        previous = hostControls.skip(false, () -> skip(false));
        buttons.addView(previous, new LinearLayout.LayoutParams(0, dp(56), 1));
        play = icon("play_mini_video", 0, strings.get(activity, "ListenVideoPlay"), primary);
        if (playIcon != null) play.setImageDrawable(playIcon);
        play.setBackground(null);
        play.setOnClickListener(view -> player.toggle());
        buttons.addView(play, new LinearLayout.LayoutParams(0, dp(56), 1));
        next = hostControls.skip(true, () -> skip(true));
        buttons.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1));
        View queue = hostControls.action("msg_list", "VideoPlaylist", this::showQueue);
        buttons.addView(queue, new LinearLayout.LayoutParams(0, dp(56), 1));
        controls.addView(buttons, new LinearLayout.LayoutParams(-1, dp(76)));
        body.addView(controls);
        bridge.invokeNamed(dialog, "setCustomView", body);
    }

    private void applySystemBars(Window window) {
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        boolean lightBars = Color.luminance(background) > 0.5;
        int icons = lightBars ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            attributes.setFitInsetsTypes(0);
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            icons |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (Build.VERSION.SDK_INT >= 28) {
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        window.setAttributes(attributes);
        if (Build.VERSION.SDK_INT < 30) window.getDecorView().setSystemUiVisibility(icons);
        WindowInsets activityInsets = activity.getWindow().getDecorView().getRootWindowInsets();
        if (activityInsets != null) applyContentInsets(activityInsets);
        window.getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
            applyContentInsets(insets);
            return view.onApplyWindowInsets(insets);
        });
    }

    private void applyContentInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            body.setPadding(0, safe.top, 0, safe.bottom);
        } else {
            body.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
        }
    }

    void show(Runnable closeSource) {
        Method setDelegate = bridge.findMethod(dialog.getClass(), "setDelegate", 1,
                method -> method.getParameterTypes()[0].isInterface());
        if (setDelegate != null) {
            Class<?> type = setDelegate.getParameterTypes()[0];
            Object delegate = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "Listen video sheet delegate";
                if ("onOpenAnimationEnd".equals(method.getName())) {
                    body.post(() -> onOpened(closeSource != null));
                }
                if (method.getReturnType() == boolean.class) return true;
                return null;
            });
            bridge.invoke(dialog, setDelegate, delegate);
        }
        dialog.show();
        if (!dialog.isShowing()) return;
        Window window = dialog.getWindow();
        if (window != null) {
            applySystemBars(window);
            window.setLayout(-1, -1);
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    int lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(Color.luminance(background) > 0.5 ? lightMask : 0, lightMask);
                }
            }
            window.getDecorView().requestApplyInsets();
        }
        if (closeSource != null) body.postOnAnimation(closeSource);
        if (setDelegate == null) body.post(() -> onOpened(closeSource != null));
        refresh();
    }

    private void onOpened(boolean loadQueue) {
        if (!dialog.isShowing()) return;
        opened = true;
        preloadAdjacentCovers();
        if (loadQueue) player.loadMore(success -> {
            if (success && dialog.isShowing()) preloadAdjacentCovers();
        });
    }

    private void preloadAdjacentCovers() {
        Object message = player.current();
        playlist.preloadCover(player.adjacent(message, false));
        playlist.preloadCover(player.adjacent(message, true));
    }

    boolean isShowing() { return dialog.isShowing(); }

    private void watch() {
        player.watch(() -> {
            bridge.invokeNamed(dialog, "skipDismissAnimation");
            dialog.dismiss();
        });
    }

    private void refresh() {
        if (!dialog.isShowing()) return;
        Object message = player.current();
        if (message == null) {
            dialog.dismiss();
            return;
        }
        if (!player.sameMessage(displayed, message)) {
            displayed = message;
            title.setText(playlist.title(activity, message));
            playlist.bindThumbnail(coverThumbnail, message, true);
            if (opened) preloadAdjacentCovers();
        }
        long duration = player.duration();
        long position = player.position();
        progress.setEnabled(duration > 0);
        if (seeking == null) {
            hostControls.updateProgress(progress, duration == 0 ? 0f : Math.min(1f, (float) position / duration));
            elapsed.setText(time(position));
        }
        total.setText(time(duration));
        boolean paused = player.paused();
        if (displayedPaused == null || displayedPaused != paused) {
            if (playIcon != null) bridge.invokeNamed(playIcon, setPause, !paused, displayedPaused != null);
            displayedPaused = paused;
            play.setContentDescription(strings.get(activity, paused ? "ListenVideoPlay" : "ListenVideoPause"));
        }
        float currentSpeed = player.speed();
        if (Float.compare(displayedSpeed, currentSpeed) != 0) {
            displayedSpeed = currentSpeed;
            hostControls.updateSpeed(currentSpeed);
        }
        String remaining = player.timerStatus(activity);
        if (!remaining.equals(displayedTimer)) {
            displayedTimer = remaining;
            timerRemaining.setText(remaining);
            timerRemaining.setVisibility(remaining.isEmpty() ? View.GONE : View.VISIBLE);
            timer.setContentDescription(remaining.isEmpty() ? strings.get(activity, "VideoSleepTimer")
                    : strings.format(activity, "ListenVideoTimerRemaining", remaining));
        }
        previous.setEnabled(player.canSkip(false));
        next.setEnabled(player.canSkip(true));
        previous.setAlpha(previous.isEnabled() ? 1f : 0.32f);
        next.setAlpha(next.isEnabled() ? 1f : 0.32f);
        main.postDelayed(update, UPDATE_MS);
    }

    private void skip(boolean next) {
        if (!player.skip(next)) {
            Toast.makeText(activity, strings.get(activity, "VideoPlaylistOpenFailed"), Toast.LENGTH_SHORT).show();
        }
    }

    private void showQueue() {
        if (player.current() != null) playlist.show(activity, player);
    }

    void onSeekPressed(boolean pressed) {
        seeking = pressed ? player.current() : null;
    }

    void onSeekDrag(boolean finished, float fraction) {
        if (player.duration() <= 0 || !Float.isFinite(fraction)) return;
        float bounded = Math.max(0f, Math.min(1f, fraction));
        elapsed.setText(time((long) (player.duration() * bounded)));
        if (finished && (seeking == null || player.sameMessage(seeking, player.current()))) {
            player.seek(Math.round(1000f * bounded));
        }
    }

    private ImageView icon(String resource, int fallback, String label, int tint) {
        ImageView button = new ImageView(activity);
        int id = resource == null ? 0 : activity.getResources().getIdentifier(resource, "drawable", activity.getPackageName());
        button.setImageResource(id == 0 ? fallback : id);
        button.setImageTintList(ColorStateList.valueOf(tint));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setContentDescription(label);
        button.setBackground(theme.rippleTransparent(activity, 28, theme.alpha(accent, 35)));
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private int dp(int value) { return theme.dp(activity, value); }

    static String time(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
