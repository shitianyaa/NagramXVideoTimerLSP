package com.shitianyaa.nagramx.videotimer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class ListenVideoPage {
    private static final long PLAYING_UPDATE_MS = 80L;
    private static final long PAUSED_UPDATE_MS = 350L;
    private static final long SEEK_LOCK_MS = 650L;

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
    private final FrameLayout cover;
    private final FrameLayout header;
    private final LinearLayout content;
    private final LinearLayout controls;
    private final LinearLayout buttons;
    private boolean appliedWide;
    private int appliedWidthPx;
    private int appliedHeightPx;
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
    private Object seekLockMessage;
    private float seekLockFraction;
    private long seekLockUntilMs;
    private float displayedSpeed = Float.NaN;
    private Boolean displayedPaused;
    private Boolean displayedCanPrev;
    private Boolean displayedCanNext;
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
        background = theme.activeColor("dialogBackground");
        primary = theme.activeColor("dialogTextBlack");
        secondary = theme.activeColor("dialogTextGray2");
        accent = theme.accentColor(background);
        coverColor = theme.activeColor("windowBackgroundGray");
        hostControls = new HostListenControls(activity, host, theme, strings, primary, accent);

        Class<?> sheetClass = bridge.loadClass("org.telegram.ui.ActionBar.BottomSheet");
        Object sheet = bridge.newInstance(sheetClass, (Context) activity, false, null);
        if (!(sheet instanceof Dialog)) throw new IllegalStateException("Host BottomSheet unavailable");
        dialog = (Dialog) sheet;
        bridge.invokeNamed(dialog, "setApplyTopPadding", false);
        bridge.invokeNamed(dialog, "setApplyBottomPadding", false);
        bridge.invokeNamed(dialog, "setOpenNoDelay", true);
        bridge.invokeNamed(dialog, "transitionFromRight", false);
        bridge.setField(dialog, "openDuration", 260);
        bridge.invokeNamed(dialog, "setCanDismissWithSwipe", true);
        bridge.invokeNamed(dialog, "setCanDismissWithTouchOutside", true);
        bridge.setField(dialog, "fullWidth", true);
        bridge.invokeNamed(dialog, "setBackgroundColor", background);
        if (bridge.findMethod(sheetClass, "fixNavigationBar", 1) != null) {
            bridge.invokeNamed(dialog, "fixNavigationBar", background);
        } else {
            bridge.invokeNamed(dialog, "fixNavigationBar");
        }
        bridge.invokeNamed(dialog, "setUseLightStatusBar", false);
        dialog.setOnDismissListener(d -> {
            main.removeCallbacks(update);
            cancelRunningAnimations();
            player.pageClosed(this);
        });
        // Wrap content up to max sheet height so dimmed top backdrop remains visible and tappable.
        body = new LinearLayout(activity) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int availW = MeasureSpec.getSize(widthSpec);
                int availH = MeasureSpec.getSize(heightSpec);
                boolean wide = availH > 0 && availW > availH;
                int maxH = sheetMaxHeight(availH, dp(wide ? 24 : 64), dp(240));
                if (availH > 0 && getChildCount() >= 4) {
                    if (availW > 0 && (wide != appliedWide || availW != appliedWidthPx || availH != appliedHeightPx)) {
                        applyResponsiveLayout(wide, availW, availH);
                    }
                    int maxHSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                    View headerView = getChildAt(0);
                    View dividerView = getChildAt(1);
                    View scrollView = getChildAt(2);
                    View controlsView = getChildAt(3);
                    measureChildWithMargins(headerView, widthSpec, 0, maxHSpec, 0);
                    measureChildWithMargins(dividerView, widthSpec, 0, maxHSpec, 0);
                    measureChildWithMargins(controlsView, widthSpec, 0, maxHSpec, 0);
                    int fixed = getPaddingTop() + getPaddingBottom()
                            + childHeightWithMargins(headerView)
                            + childHeightWithMargins(dividerView)
                            + childHeightWithMargins(controlsView);
                    int scrollBudget = Math.max(dp(96), maxH - fixed);
                    measureChildWithMargins(scrollView, widthSpec, 0,
                            MeasureSpec.makeMeasureSpec(scrollBudget, MeasureSpec.AT_MOST), 0);
                    int desiredScroll = resolveScrollHeight(scrollView.getMeasuredHeight(), scrollBudget,
                            fixed + verticalMargins(scrollView), availH, maxH, dp(560), wide);
                    if (desiredScroll != scrollView.getMeasuredHeight()) {
                        measureChildWithMargins(scrollView, widthSpec, 0,
                                MeasureSpec.makeMeasureSpec(desiredScroll, MeasureSpec.EXACTLY), 0);
                    }
                    int measuredW = getPaddingLeft() + getPaddingRight() + maxChildWidthWithMargins(this);
                    int measuredH = Math.min(maxH, fixed + childHeightWithMargins(scrollView));
                    setMeasuredDimension(resolveSize(measuredW, widthSpec), resolveSize(measuredH, maxHSpec));
                    return;
                }
                super.onMeasure(widthSpec, maxH > 0
                        ? MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                        : heightSpec);
            }
        };
        body.setOrientation(LinearLayout.VERTICAL);

        header = new FrameLayout(activity);
        Object actionBar = bridge.newInstance("org.telegram.ui.ActionBar.ActionBar", (Context) activity);
        if (!(actionBar instanceof View)) throw new IllegalStateException("Host ActionBar unavailable");
        bridge.invokeNamed(actionBar, "setOccupyStatusBar", false);
        bridge.invokeNamed(actionBar, "setCastShadows", false);
        bridge.invokeNamed(actionBar, "setTitle", strings.get(activity, "ListenVideo"));
        bridge.invokeNamed(actionBar, "setTitleColor", primary);
        bridge.invokeNamed(actionBar, "setItemsColor", primary, false);
        bridge.invokeNamed(actionBar, "setItemsBackgroundColor", theme.alpha(primary, 22), false);
        int collapseIcon = activity.getResources().getIdentifier(
                "baseline_keyboard_arrow_down_24", "drawable", activity.getPackageName());
        boolean rotateBackIcon = collapseIcon == 0;
        if (rotateBackIcon) {
            collapseIcon = activity.getResources().getIdentifier("ic_ab_back", "drawable", activity.getPackageName());
        }
        bridge.invokeNamed(actionBar, "setBackButtonImage", collapseIcon);
        Object backButton = bridge.invokeNamed(actionBar, "getBackButton");
        if (backButton instanceof View) {
            View backView = (View) backButton;
            if (rotateBackIcon) backView.setRotation(-90f);
            backView.setContentDescription(strings.get(activity, "Close"));
            backView.setOnClickListener(view -> {
                HostTheme.tapHaptic(view);
                dialog.dismiss();
            });
        }
        header.addView((View) actionBar, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout timerGroup = new LinearLayout(activity);
        timerGroup.setGravity(Gravity.CENTER_VERTICAL);
        timerRemaining = text("", 13, accent);
        timerRemaining.setTypeface(theme.bold());
        timerRemaining.setSingleLine(true);
        timerRemaining.setFontFeatureSettings("tnum");
        timerRemaining.setEllipsize(TextUtils.TruncateAt.END);
        timerRemaining.setMaxWidth(dp(124));
        timerRemaining.setPadding(dp(8), 0, dp(2), 0);
        timerRemaining.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        timerRemaining.setVisibility(View.GONE);
        timerRemaining.setOnClickListener(view -> {
            HostTheme.tapHaptic(view);
            player.openTimer(activity);
        });
        timerGroup.addView(timerRemaining, new LinearLayout.LayoutParams(-2, -1));
        Drawable timerIcon = theme.createTimerIcon(activity, HostTheme.WHITE);
        timer = hostControls.action(timerIcon, "VideoSleepTimer", () -> player.openTimer(activity));
        timerGroup.addView(timer, new LinearLayout.LayoutParams(dp(48), -1));
        header.addView(timerGroup, new FrameLayout.LayoutParams(-2, -1, Gravity.END));
        int widthPx = activity.getResources().getDisplayMetrics().widthPixels;
        int heightPx = activity.getResources().getDisplayMetrics().heightPixels;
        boolean landscape = widthPx > heightPx;
        appliedWide = landscape;
        appliedWidthPx = widthPx;
        appliedHeightPx = heightPx;

        body.addView(header, new LinearLayout.LayoutParams(-1, dp(landscape ? 48 : 54)));
        View divider = new View(activity);
        divider.setBackgroundColor(theme.alpha(secondary, 35));
        body.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView scroll = HostTheme.pullDownScrollView(activity);
        content = column();
        content.setPadding(dp(24), dp(landscape ? 10 : 20), dp(24), dp(landscape ? 4 : 12));
        content.setGravity(Gravity.TOP);
        cover = new FrameLayout(activity);
        cover.setBackground(theme.roundRect(activity, 12, coverColor));
        cover.setClipToOutline(true);
        int coverHeight = computeCoverHeight(landscape, widthPx, heightPx, dp(180), dp(110), dp(340));
        coverThumbnail = playlist.createThumbnail(activity, 12);
        playlist.prepareCover(coverThumbnail);
        cover.addView(coverThumbnail, new FrameLayout.LayoutParams(-1, -1));
        View coverBorder = new View(activity);
        android.graphics.drawable.GradientDrawable borderDrawable = theme.roundRect(activity, 12, HostTheme.TRANSPARENT);
        borderDrawable.setStroke(Math.max(1, dp(1) / 2), theme.alpha(primary, 22));
        coverBorder.setBackground(borderDrawable);
        cover.addView(coverBorder, new FrameLayout.LayoutParams(-1, -1));
        cover.setOnClickListener(view -> {
            HostTheme.tapHaptic(view);
            watch();
        });
        cover.setContentDescription(strings.get(activity, "ListenVideoBack"));
        content.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight));

        title = text("", landscape ? 17 : 19, primary);
        title.setTypeface(theme.bold());
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(-1, -2);
        titleSize.topMargin = dp(landscape ? 8 : 18);
        content.addView(title, titleSize);

        TextView watch = text(strings.get(activity, "ListenVideoBack"), 15, accent);
        watch.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        watch.setTypeface(theme.bold());
        watch.setBackground(theme.rippleTransparent(activity, 6, theme.alpha(accent, 35)));
        watch.setOnClickListener(view -> {
            HostTheme.tapHaptic(view);
            watch();
        });
        LinearLayout.LayoutParams watchSize = new LinearLayout.LayoutParams(-1, dp(36));
        watchSize.topMargin = dp(4);
        content.addView(watch, watchSize);
        scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));

        controls = column();
        controls.setPadding(dp(16), dp(landscape ? 4 : 10), dp(16), dp(landscape ? 6 : 22));
        progress = hostControls.seekBar(this);
        FrameLayout seekWrap = new FrameLayout(activity) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                ViewParent parent = getParent();
                if (parent != null) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        // Lock horizontal seekbar scrubbing so downward thumb drift does not dismiss the sheet.
                        parent.requestDisallowInterceptTouchEvent(true);
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
                return super.dispatchTouchEvent(event);
            }
        };
        seekWrap.addView(progress, new FrameLayout.LayoutParams(-1, dp(40)));
        controls.addView(seekWrap, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout times = new LinearLayout(activity);
        times.setPadding(dp(16), 0, dp(16), 0);
        elapsed = text("0:00", 13, secondary);
        total = text("0:00", 13, secondary);
        total.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(total, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(times, new LinearLayout.LayoutParams(-1, -2));

        buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER);
        View speed = hostControls.speed(player);
        buttons.addView(speed, new LinearLayout.LayoutParams(0, dp(56), 1));
        previous = hostControls.skip(false, () -> skip(false));
        buttons.addView(previous, new LinearLayout.LayoutParams(0, dp(56), 1));
        play = icon("play_mini_video", 0, strings.get(activity, "ListenVideoPlay"), primary);
        if (playIcon != null) play.setImageDrawable(playIcon);
        play.setBackground(theme.rippleTransparent(activity, 28, theme.alpha(primary, 24)));
        play.setOnClickListener(view -> {
            HostTheme.tapHaptic(view);
            bounceView(view);
            player.toggle();
        });
        buttons.addView(play, new LinearLayout.LayoutParams(0, dp(56), 1));
        next = hostControls.skip(true, () -> skip(true));
        buttons.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1));
        View queue = hostControls.action("msg_list", "VideoPlaylist", this::showQueue);
        buttons.addView(queue, new LinearLayout.LayoutParams(0, dp(56), 1));
        controls.addView(buttons, new LinearLayout.LayoutParams(-1, dp(landscape ? 58 : 64)));
        body.addView(controls);
        bridge.invokeNamed(dialog, "setCustomView", body);
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
        main.removeCallbacks(update);
        if (!dialog.isShowing()) return;
        Object message = player.current();
        if (message == null) {
            dialog.dismiss();
            return;
        }
        boolean trackChanged = !player.sameMessage(displayed, message);
        boolean animateTrack = trackChanged && displayed != null;
        if (trackChanged) {
            if (seekLockMessage != null && !player.sameMessage(seekLockMessage, message)) {
                seekLockMessage = null;
            }
            displayed = message;
            updateTrackTitle(playlist.title(activity, message), animateTrack);
            playlist.bindThumbnail(coverThumbnail, message, true);
            if (opened) preloadAdjacentCovers();
        }
        long duration = player.duration();
        long position = player.position();
        progress.setEnabled(duration > 0);
        if (seeking == null) {
            float fraction = resolveSeekAwareFraction(message, duration, position);
            hostControls.updateProgress(progress, fraction, animateTrack);
            long shownMs = seekLockMessage != null ? Math.round(duration * fraction) : position;
            elapsed.setText(time(shownMs));
        }
        total.setText(time(duration));
        boolean paused = player.paused();
        if (displayedPaused == null || displayedPaused != paused) {
            boolean animateState = displayedPaused != null;
            if (playIcon != null) bridge.invokeNamed(playIcon, setPause, !paused, animateState);
            animateCoverScale(paused, animateState);
            displayedPaused = paused;
            play.setContentDescription(strings.get(activity, paused ? "ListenVideoPlay" : "ListenVideoPause"));
        }
        float currentSpeed = player.speed();
        if (Float.compare(displayedSpeed, currentSpeed) != 0) {
            boolean animateSpeed = !Float.isNaN(displayedSpeed);
            displayedSpeed = currentSpeed;
            hostControls.updateSpeed(currentSpeed, animateSpeed);
        }
        String remaining = player.timerStatus(activity);
        if (!remaining.equals(displayedTimer)) {
            boolean animateTimer = displayedTimer != null;
            displayedTimer = remaining;
            updateTimerRemaining(remaining, animateTimer);
        }
        boolean canPrev = player.canSkip(false);
        if (displayedCanPrev == null || displayedCanPrev != canPrev) {
            updateSkipAlpha(previous, canPrev, displayedCanPrev != null);
            displayedCanPrev = canPrev;
        }
        boolean canNext = player.canSkip(true);
        if (displayedCanNext == null || displayedCanNext != canNext) {
            updateSkipAlpha(next, canNext, displayedCanNext != null);
            displayedCanNext = canNext;
        }
        main.postDelayed(update, (!paused && duration > 0) ? PLAYING_UPDATE_MS : PAUSED_UPDATE_MS);
    }

    private float resolveSeekAwareFraction(Object message, long duration, long position) {
        float actual = duration <= 0L ? 0f : Math.max(0f, Math.min(1f, (float) position / duration));
        if (seekLockMessage != null) {
            if (!player.sameMessage(seekLockMessage, message) || SystemClock.elapsedRealtime() >= seekLockUntilMs
                    || Math.abs(actual - seekLockFraction) < 0.025f) {
                seekLockMessage = null;
            } else {
                return seekLockFraction;
            }
        }
        return actual;
    }

    private void updateTrackTitle(String nextTitle, boolean animated) {
        title.animate().cancel();
        if (!animated) {
            title.setAlpha(1f);
            title.setTranslationY(0f);
            title.setText(nextTitle);
            return;
        }
        title.setAlpha(0f);
        title.setTranslationY(dp(6));
        title.setText(nextTitle);
        title.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200L)
                .setInterpolator(HostTheme.easeOutQuint())
                .start();
    }

    private void animateCoverScale(boolean paused, boolean animated) {
        float target = paused ? 0.94f : 1f;
        cover.animate().cancel();
        if (!animated) {
            cover.setScaleX(target);
            cover.setScaleY(target);
            return;
        }
        cover.animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(200L)
                .setInterpolator(HostTheme.easeOutQuint())
                .start();
    }

    private void updateTimerRemaining(String remaining, boolean animated) {
        timerRemaining.animate().cancel();
        boolean show = !remaining.isEmpty();
        hostControls.setActionIconColor(timer, show ? accent : primary);
        timer.setContentDescription(show
                ? strings.format(activity, "ListenVideoTimerRemaining", remaining)
                : strings.get(activity, "VideoSleepTimer"));
        if (show) {
            if (!TextUtils.equals(timerRemaining.getText(), remaining)) {
                timerRemaining.setText(remaining);
            }
            if (timerRemaining.getVisibility() != View.VISIBLE) {
                if (animated) {
                    timerRemaining.setAlpha(0f);
                    timerRemaining.setVisibility(View.VISIBLE);
                    timerRemaining.animate().alpha(1f).setDuration(180L).setInterpolator(HostTheme.easeOutQuint()).start();
                } else {
                    timerRemaining.setAlpha(1f);
                    timerRemaining.setVisibility(View.VISIBLE);
                }
            } else {
                timerRemaining.setAlpha(1f);
            }
        } else if (timerRemaining.getVisibility() == View.VISIBLE) {
            if (animated) {
                timerRemaining.animate()
                        .alpha(0f)
                        .setDuration(150L)
                        .setInterpolator(HostTheme.easeOutQuint())
                        .withEndAction(() -> {
                            if (displayedTimer != null && displayedTimer.isEmpty()) {
                                timerRemaining.setVisibility(View.GONE);
                                timerRemaining.setAlpha(1f);
                            }
                        })
                        .start();
            } else {
                timerRemaining.setVisibility(View.GONE);
                timerRemaining.setAlpha(1f);
            }
        }
    }

    private void updateSkipAlpha(View button, boolean enabled, boolean animated) {
        button.setEnabled(enabled);
        float alpha = enabled ? 1f : 0.32f;
        button.animate().cancel();
        if (animated) {
            button.animate().alpha(alpha).setDuration(180L).setInterpolator(HostTheme.easeOutQuint()).start();
        } else {
            button.setAlpha(alpha);
        }
    }

    private void bounceView(View view) {
        view.animate().cancel();
        view.animate()
                .scaleX(0.90f)
                .scaleY(0.90f)
                .setDuration(75L)
                .withEndAction(() -> view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150L)
                        .setInterpolator(HostTheme.easeOutQuint())
                        .start())
                .start();
    }

    private void cancelRunningAnimations() {
        title.animate().cancel();
        cover.animate().cancel();
        play.animate().cancel();
        timerRemaining.animate().cancel();
        previous.animate().cancel();
        next.animate().cancel();
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
            seekLockMessage = player.current();
            seekLockFraction = bounded;
            seekLockUntilMs = SystemClock.elapsedRealtime() + SEEK_LOCK_MS;
            HostTheme.tickHaptic(progress);
            hostControls.updateProgress(progress, bounded, false);
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
        return HostStrings.formatDurationClock(ms);
    }

    private void applyResponsiveLayout(boolean wide, int widthPx, int heightPx) {
        boolean wideChanged = wide != appliedWide;
        appliedWide = wide;
        appliedWidthPx = widthPx;
        appliedHeightPx = heightPx;
        if (header == null || content == null || controls == null || buttons == null) {
            return;
        }
        setHeightIfChanged(cover.getLayoutParams(),
                computeCoverHeight(wide, widthPx, heightPx, dp(180), dp(110), dp(340)));
        if (!wideChanged) {
            return;
        }
        setHeightIfChanged(header.getLayoutParams(), dp(wide ? 48 : 54));
        content.setPadding(dp(24), dp(wide ? 10 : 20), dp(24), dp(wide ? 4 : 12));
        title.setTextSize(wide ? 17 : 19);
        ViewGroup.LayoutParams titleLp = title.getLayoutParams();
        if (titleLp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) titleLp).topMargin = dp(wide ? 8 : 18);
        }
        controls.setPadding(dp(16), dp(wide ? 4 : 10), dp(16), dp(wide ? 6 : 22));
        setHeightIfChanged(buttons.getLayoutParams(), dp(wide ? 58 : 64));
    }

    private static void setHeightIfChanged(ViewGroup.LayoutParams params, int height) {
        if (params != null && params.height != height) {
            params.height = height;
        }
    }

    private static int childHeightWithMargins(View child) {
        return child.getMeasuredHeight() + verticalMargins(child);
    }

    private static int verticalMargins(View child) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            return mlp.topMargin + mlp.bottomMargin;
        }
        return 0;
    }

    private static int maxChildWidthWithMargins(ViewGroup parent) {
        int max = 0;
        for (int i = 0, count = parent.getChildCount(); i < count; i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            int horizontalMargin = 0;
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                horizontalMargin = mlp.leftMargin + mlp.rightMargin;
            }
            max = Math.max(max, child.getMeasuredWidth() + horizontalMargin);
        }
        return max;
    }

    static int computeCoverHeight(boolean landscape, int widthPx, int heightPx,
                                  int maxLandscapePx, int minLandscapePx, int maxPortraitPx) {
        if (landscape) {
            return Math.min(maxLandscapePx, Math.max(minLandscapePx, heightPx / 3));
        }
        return Math.min(maxPortraitPx, Math.min(widthPx * 2 / 3, heightPx * 2 / 5));
    }

    static int resolveScrollHeight(int naturalScroll, int scrollBudget, int fixedHeight,
                                   int availHeight, int maxSheetHeight, int minPortraitHeight, boolean wide) {
        if (naturalScroll > scrollBudget) {
            return scrollBudget;
        }
        if (!wide) {
            int minSheetH = sheetPortraitMinHeight(availHeight, maxSheetHeight, minPortraitHeight);
            if (minSheetH > fixedHeight) {
                return Math.max(naturalScroll, Math.min(scrollBudget, minSheetH - fixedHeight));
            }
        }
        return naturalScroll;
    }

    static int sheetMaxHeight(int availableHeight, int topReserve, int minHeight) {
        if (availableHeight <= 0) return minHeight;
        return Math.max(Math.min(availableHeight, minHeight), availableHeight - Math.max(0, topReserve));
    }

    static int sheetPortraitMinHeight(int availableHeight, int maxHeight, int targetMinPx) {
        if (availableHeight <= 0 || maxHeight <= 0) return 0;
        int desired = Math.max(targetMinPx, Math.round(availableHeight * 0.84f));
        return Math.min(maxHeight, desired);
    }
}
