package com.shitianyaa.nagramx.videotimer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Video rows use the host's thumbnail loader, rather than audio player cells. */
final class VideoPlaylistSheet {
    // 阿里规约 01-(七)4：显式限制工作线程和待处理文件检查，避免无界队列。
    private static final int MAX_FILE_CHECKS = 64;
    private static final ThreadPoolExecutor FILE_CHECKS = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_FILE_CHECKS), task -> {
                Thread worker = Executors.defaultThreadFactory().newThread(task);
                worker.setName("video-file-check");
                worker.setDaemon(true);
                return worker;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final HostProfile host;
    private final HostBridge bridge;
    private final HostTheme theme;
    private final HostStrings strings;
    private final HostBridge.Logger logger;
    private final Class<?> thumbnailClass;

    VideoPlaylistSheet(HostProfile host, HostTheme theme, HostStrings strings, HostBridge.Logger logger) {
        this.host = host;
        this.bridge = host.bridge;
        this.theme = theme;
        this.strings = strings;
        this.logger = logger;
        thumbnailClass = bridge.loadClass("org.telegram.ui.Components.BackupImageView");
    }

    void show(Activity activity, ListenVideoAgent player) {
        try {
            Class<?> type = bridge.loadClass("org.telegram.ui.ActionBar.BottomSheet");
            Object object = bridge.newInstance(type, (Context) activity, false, null);
            if (!(object instanceof Dialog)) throw new IllegalStateException("Host BottomSheet unavailable");
            Dialog sheet = (Dialog) object;
            bridge.invokeNamed(sheet, "setCanDismissWithSwipe", true);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(0, dp(activity, 10), 0, dp(activity, 16));
            View handle = theme.createDragHandle(activity, theme.alpha(theme.activeColor("dialogTextGray2"), 0x70));
            LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4));
            handleSize.gravity = Gravity.CENTER_HORIZONTAL;
            handleSize.bottomMargin = dp(activity, 10);
            content.addView(handle, handleSize);
            TextView heading = text(activity, strings.get(activity, "VideoPlaylist"), 20, "dialogTextBlack");
            heading.setTypeface(theme.bold());
            heading.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
            content.addView(heading, new LinearLayout.LayoutParams(-1, dp(activity, 44)));

            int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
            int maxHeight = Math.min(dp(activity, 440), Math.max(dp(activity, 220), (int) (screenHeight * 0.62f)));
            QueueViewport viewport = new QueueViewport(activity, player, sheet, maxHeight);
            content.addView(viewport.scroll, new LinearLayout.LayoutParams(-1, maxHeight));
            viewport.updateQueue();
            sheet.setOnDismissListener(dialog -> viewport.clear());
            if (bridge.findMethod(type, "setCustomView", 1) == null) throw new IllegalStateException("Host setCustomView unavailable");
            bridge.invokeNamed(sheet, "setCustomView", content);
            bridge.invokeNamed(sheet, "fixNavigationBar");
            sheet.show();
            if (player.isLoadingMedia()) viewport.loadNext();
        } catch (Throwable error) {
            logger.log("打开视频列表失败", error);
            Toast.makeText(activity, strings.get(activity, "VideoPlaylistOpenFailed"), Toast.LENGTH_SHORT).show();
        }
    }

    private final class QueueViewport {
        final ScrollView scroll;
        private final FrameLayout content;
        private final TextView more;
        private final Dialog sheet;
        private final ListenVideoAgent player;
        private final int rowHeight;
        private final int footerHeight;
        private final int maxHeight;
        private final SparseArray<VideoRow> visible = new SparseArray<>();
        private final ArrayDeque<VideoRow> available = new ArrayDeque<>();
        private final Map<Integer, Boolean> localCache = new HashMap<>();
        private List<Object> queue = Collections.emptyList();
        private int viewportHeight;
        private int pendingScroll = -1;
        private boolean initialScrolled;

        QueueViewport(Activity activity, ListenVideoAgent player, Dialog sheet, int maxHeight) {
            this.player = player;
            this.sheet = sheet;
            rowHeight = dp(activity, 88);
            footerHeight = dp(activity, 48);
            this.maxHeight = maxHeight;
            scroll = HostTheme.pullDownScrollView(activity);
            scroll.setNestedScrollingEnabled(false);
            scroll.setVerticalScrollBarEnabled(true);
            content = new FrameLayout(activity);
            scroll.addView(content);
            more = text(activity, strings.get(activity, "ListenVideoMore"), 15, "dialogTextBlue2");
            more.setGravity(Gravity.CENTER);
            more.setBackground(theme.rippleTransparent(activity, 6, theme.activeColor("listSelector")));
            FrameLayout.LayoutParams moreParams = new FrameLayout.LayoutParams(-1, footerHeight, Gravity.TOP);
            moreParams.leftMargin = dp(activity, 16);
            moreParams.rightMargin = dp(activity, 16);
            content.addView(more, moreParams);
            more.setOnClickListener(view -> {
                HostTheme.tickHaptic(view);
                loadNext();
            });
            scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> renderAt(Math.max(0, y)));
        }

        private int scrollPosition() {
            return pendingScroll >= 0 ? pendingScroll : Math.max(0, scroll.getScrollY());
        }

        void updateQueue() {
            int top = scrollPosition();
            int index = top / rowHeight;
            int anchor = index < queue.size() ? host.messageIdOf(queue.get(index)) : 0;
            queue = player.queue();
            boolean canLoad = player.canLoadMore();
            int tail = queue.size() * rowHeight;
            int height = tail + (canLoad ? footerHeight : 0);
            viewportHeight = Math.min(maxHeight, height);
            if (!initialScrolled) {
                initialScrolled = true;
                Object current = player.current();
                int playingIndex = -1;
                for (int i = 0; i < queue.size(); i++) {
                    if (player.sameMessage(queue.get(i), current)) {
                        playingIndex = i;
                        break;
                    }
                }
                top = centeredScrollTop(playingIndex, rowHeight, height, viewportHeight);
            } else if (anchor != 0) {
                for (int i = 0; i < queue.size(); i++) {
                    if (host.messageIdOf(queue.get(i)) == anchor) {
                        top = i * rowHeight + top % rowHeight;
                        break;
                    }
                }
            }

            content.setMinimumHeight(height);
            more.setVisibility(canLoad ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams footer = (FrameLayout.LayoutParams) more.getLayoutParams();
            footer.topMargin = tail;
            more.setLayoutParams(footer);
            if (scroll.getLayoutParams().height != viewportHeight) {
                scroll.getLayoutParams().height = viewportHeight;
                scroll.requestLayout();
            }
            top = Math.max(0, Math.min(top, height - viewportHeight));
            renderAt(top);
            if (top != scroll.getScrollY() && pendingScroll < 0) {
                scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                        int position = pendingScroll;
                        pendingScroll = -1;
                        if (position >= 0) scroll.scrollTo(0, position);
                        return true;
                    }
                });
            }
            if (top != scroll.getScrollY() || pendingScroll >= 0) pendingScroll = top;
        }

        private void renderAt(int top) {
            int first = firstVisibleIndex(top, rowHeight);
            int end = endVisibleIndex(top, viewportHeight, rowHeight, queue.size());
            for (int i = visible.size() - 1; i >= 0; i--) {
                int index = visible.keyAt(i);
                if (index >= first && index < end) continue;
                VideoRow row = visible.valueAt(i);
                row.clear();
                content.removeView(row);
                visible.removeAt(i);
                available.add(row);
            }
            Object current = player.current();
            for (int index = first; index < end; index++) {
                VideoRow row = visible.get(index);
                if (row == null) {
                    row = available.poll();
                    if (row == null) row = new VideoRow(content.getContext(), player, sheet, localCache);
                    FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, rowHeight, Gravity.TOP);
                    position.topMargin = index * rowHeight;
                    content.addView(row, position);
                    visible.put(index, row);
                }
                Object message = queue.get(index);
                row.bind(message, player.sameMessage(message, current));
            }
        }

        void loadNext() {
            more.setEnabled(false);
            more.setText(strings.get(scroll.getContext(), "ListenVideoLoading"));
            player.loadMore(success -> {
                if (!sheet.isShowing()) return;
                if (success) updateQueue();
                more.setText(strings.get(scroll.getContext(), success ? "ListenVideoMore" : "ListenVideoLoadRetry"));
                more.setEnabled(true);
            });
        }

        void clear() {
            scroll.setOnScrollChangeListener(null);
            pendingScroll = -1;
            localCache.clear();
            for (int i = 0; i < visible.size(); i++) visible.valueAt(i).clear();
        }
    }

    private final class VideoRow extends LinearLayout {
        private final Map<Integer, Boolean> localCache;
        private final View indicator;
        private final View thumbnail;
        private final TextView title;
        private final TextView details;
        private final Drawable normalBackground;
        private final Drawable playingBackground;
        private final int accentColor;
        private Object message;
        private boolean playing;
        private Future<?> fileCheck;

        VideoRow(Context context, ListenVideoAgent player, Dialog sheet, Map<Integer, Boolean> localCache) {
            super(context);
            this.localCache = localCache;
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(context, 8), dp(context, 16), dp(context, 8));
            int sheetBackground = theme.activeColor("dialogBackground");
            accentColor = theme.accentColor(sheetBackground);
            int ripple = theme.activeColor("listSelector");
            normalBackground = theme.rippleTransparent(context, 0, ripple);
            playingBackground = theme.rippleRect(context, 0, theme.alpha(accentColor, 0x16), ripple);
            setBackground(normalBackground);
            indicator = new View(context);
            indicator.setBackground(theme.roundRect(context, 1.5f, accentColor));
            indicator.setVisibility(View.INVISIBLE);
            LayoutParams indicatorSize = new LayoutParams(dp(context, 3), dp(context, 36));
            indicatorSize.leftMargin = dp(context, 5);
            indicatorSize.rightMargin = dp(context, 8);
            addView(indicator, indicatorSize);
            thumbnail = createThumbnail(context);
            addView(thumbnail, new LayoutParams(dp(context, 96), dp(context, 64)));
            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(context, 14), 0, 0, 0);
            title = text(context, "", 16, "dialogTextBlack");
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title, new LayoutParams(-1, -2));
            details = text(context, "", 13, "dialogTextGray2");
            labels.addView(details, new LayoutParams(-1, -2));
            addView(labels, new LayoutParams(0, -2, 1));
            setOnClickListener(view -> {
                if (message == null) return;
                HostTheme.tickHaptic(view);
                if (player.select(message)) sheet.dismiss();
                else Toast.makeText(context, strings.get(context, "VideoPlaylistOpenFailed"), Toast.LENGTH_SHORT).show();
            });
        }

        void bind(Object target, boolean playing) {
            if (message == target && this.playing == playing) return;
            clear();
            message = target;
            this.playing = playing;
            Context context = getContext();
            setBackground(playing ? playingBackground : normalBackground);
            indicator.setVisibility(playing ? View.VISIBLE : View.INVISIBLE);
            bindThumbnail(thumbnail, target, false);
            title.setTypeface(playing ? theme.bold() : Typeface.DEFAULT);
            title.setText(title(context, target));
            String time = duration(target);
            String prefix = playing ? strings.get(context, "VideoPlaying") + " · " + time : time;
            details.setTextColor(playing ? accentColor : theme.activeColor("dialogTextGray2"));
            setContentDescription(title.getText() + ", " + time);
            int messageId = host.messageIdOf(target);
            Boolean known = knownLocalStatus(localCache, messageId,
                    bridge.getField(target, "mediaExists"), bridge.getField(target, "attachPathExists"));
            if (known != null) {
                if (messageId != 0) localCache.put(messageId, known);
                details.setText(prefix + " · " + strings.get(context,
                        known ? "VideoStoredLocally" : "VideoNotStoredLocally"));
                return;
            }
            details.setText(prefix);
            try {
                fileCheck = FILE_CHECKS.submit(() -> {
                    boolean local = isLocal(target);
                    details.post(() -> {
                        if (messageId != 0) localCache.put(messageId, local);
                        if (message == target && this.playing == playing) {
                            details.setAlpha(0.65f);
                            details.setText(prefix + " · " + strings.get(context,
                                    local ? "VideoStoredLocally" : "VideoNotStoredLocally"));
                            details.animate().alpha(1f).setDuration(140)
                                    .setInterpolator(HostTheme.easeOutQuint()).start();
                        }
                    });
                });
            } catch (RejectedExecutionException e) {
                logger.log("文件检查队列已满，保留当前时长文案", e);
            }
        }

        void clear() {
            message = null;
            details.animate().cancel();
            details.setAlpha(1f);
            if (fileCheck != null) {
                fileCheck.cancel(false);
                fileCheck = null;
            }
        }
    }

    static int firstVisibleIndex(int top, int rowHeight) {
        if (rowHeight <= 0) return 0;
        return Math.max(0, Math.max(0, top) / rowHeight - 2);
    }

    static int endVisibleIndex(int top, int viewportHeight, int rowHeight, int size) {
        if (rowHeight <= 0 || size <= 0) return 0;
        return Math.min(size, (Math.max(0, top) + Math.max(0, viewportHeight) + rowHeight - 1) / rowHeight + 2);
    }

    static int centeredScrollTop(int index, int rowHeight, int totalHeight, int viewportHeight) {
        if (index < 0 || rowHeight <= 0 || totalHeight <= viewportHeight) return 0;
        int maxTop = Math.max(0, totalHeight - viewportHeight);
        int desired = index * rowHeight - Math.max(0, viewportHeight - rowHeight) / 2;
        return Math.max(0, Math.min(desired, maxTop));
    }

    static Boolean knownLocalStatus(Map<Integer, Boolean> cache, int messageId,
                                    Object mediaExists, Object attachPathExists) {
        if (Boolean.TRUE.equals(mediaExists) || Boolean.TRUE.equals(attachPathExists)) {
            return Boolean.TRUE;
        }
        return cache != null && messageId != 0 ? cache.get(messageId) : null;
    }

    View createThumbnail(Context context) {
        return createThumbnail(context, 8);
    }

    View createThumbnail(Context context, int radiusDp) {
        Object thumbnail = bridge.newInstance(thumbnailClass, context);
        if (thumbnail instanceof View) {
            bridge.invokeNamed(thumbnail, "setRoundRadius", dp(context, radiusDp));
            return (View) thumbnail;
        }
        ImageView fallback = new ImageView(context);
        fallback.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        fallback.setImageDrawable(placeholder(context));
        return fallback;
    }

    void bindThumbnail(View thumbnail, Object message, boolean cover) {
        if (thumbnailClass == null || !thumbnailClass.isInstance(thumbnail)) return;
        Object location = thumbnailLocation(message, cover);
        if (location == null) {
            bridge.invokeNamed(thumbnail, "setImageDrawable", placeholder(thumbnail.getContext()));
            return;
        }
        String size = cover ? "640_360" : "96_64";
        if (!cover) bridge.invokeNamed(thumbnail, "setImageDrawable", (Object) null);
        if (host.niagram1322) bridge.invokeNamed(thumbnail, "t", location, size, null, message);
        else bridge.invokeNamed(thumbnail, "setImage", location, size, null, 0, message);
    }

    private Object thumbnailLocation(Object message, boolean cover) {
        if (host.niagram1322) return bridge.invokeStatic(host.playerUi.alertClass, "c2", message);
        Object document = bridge.invoke(message, host.message.getDocument);
        Object thumbs = bridge.getField(document, "thumbs");
        Object thumb = bridge.invokeStatic(host.fileLoaderClass, "getClosestPhotoSizeWithSize", thumbs, cover ? 640 : 160);
        return bridge.invokeStatic(bridge.loadClass("org.telegram.messenger.ImageLocation"),
                "getForDocument", thumb, document);
    }

    void prepareCover(View cover) {
        Object receiver = bridge.invokeNamed(cover, "getImageReceiver");
        if (receiver == null) return;
        Object hostAnimations = bridge.invokeStatic(bridge.loadClass("org.telegram.messenger.SharedConfig"),
                host.niagram1322 ? "c" : "animationsEnabled");
        boolean enabled = android.animation.ValueAnimator.areAnimatorsEnabled() && !Boolean.FALSE.equals(hostAnimations);
        if (host.niagram1322) bridge.setField(receiver, "crossfadeWithOldImage", enabled);
        else bridge.invokeNamed(receiver, "setCrossfadeWithOldImage", enabled);
    }

    void preloadCover(Object message) {
        if (message == null) return;
        Object location = thumbnailLocation(message, true);
        if (location != null && bridge.getField(location, host.niagram1322 ? "g" : "path") == null) {
            bridge.invokeNamed(fileLoader(message), host.niagram1322 ? "Z" : "loadFile",
                    location, message, null, 0, 1);
        }
    }

    private Drawable placeholder(Context context) {
        int resource = context.getResources().getIdentifier("msg_video", "drawable", context.getPackageName());
        Drawable icon = context.getDrawable(resource == 0 ? android.R.drawable.ic_media_play : resource);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(theme.color("windowBackgroundWhiteGrayText", 0xFF707579));
        }
        return icon;
    }

    String title(Context context, Object message) {
        Object document = bridge.invoke(message, host.message.getDocument);
        Object caption = bridge.getField(message, host.message.captionField);
        if (caption instanceof CharSequence && ((CharSequence) caption).length() > 0) return caption.toString();
        Object name = bridge.invokeStatic(bridge.loadClass("org.telegram.messenger.FileLoader"), "getDocumentFileName", document);
        if (name instanceof String && !((String) name).isEmpty()) return (String) name;
        return strings.get(context, "AttachVideo") + " · " + host.messageIdOf(message);
    }

    private String duration(Object message) {
        Object value = bridge.invoke(message, host.message.getDuration);
        int seconds = value instanceof Number ? Math.max(0, ((Number) value).intValue()) : 0;
        return HostStrings.formatDurationClock(seconds * 1000L);
    }

    private boolean isLocal(Object message) {
        if (Boolean.TRUE.equals(bridge.getField(message, "mediaExists")) ||
                Boolean.TRUE.equals(bridge.getField(message, "attachPathExists"))) return true;
        Object owner = bridge.getField(message, host.message.messageOwnerField);
        Object attachPath = bridge.getField(owner, "attachPath");
        if (attachPath instanceof String && !((String) attachPath).isEmpty()
                && new java.io.File((String) attachPath).isFile()) return true;
        Object loader = fileLoader(message);
        Object file = owner == null ? null : bridge.invokeNamed(loader, host.niagram1322 ? "Q" : "getPathToMessage",
                owner, false, false);
        if (file instanceof java.io.File && ((java.io.File) file).isFile()) return true;
        Object document = bridge.invoke(message, host.message.getDocument);
        for (boolean cache : new boolean[]{false, true}) {
            Object documentFile = bridge.invokeNamed(loader, host.niagram1322 ? "N" : "getPathToAttach", document, cache);
            if (documentFile instanceof java.io.File && ((java.io.File) documentFile).isFile()) return true;
        }
        return false;
    }

    private Object fileLoader(Object message) {
        Object account = bridge.getField(message, host.message.currentAccountField);
        return account instanceof Number ? bridge.invokeStatic(host.fileLoaderClass,
                host.niagram1322 ? "H" : "getInstance", ((Number) account).intValue()) : null;
    }

    private TextView text(Context context, String value, int size, String color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(theme.activeColor(color));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(Context context, int value) { return theme.dp(context, value); }
}
