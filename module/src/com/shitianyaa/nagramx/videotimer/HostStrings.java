package com.shitianyaa.nagramx.videotimer;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主字符串与多语言解析，带内置中文与英文兜底。
 */
final class HostStrings {

    private final HostBridge bridge;
    private final HostBridge.Logger logger;
    private final Class<?> localeController;
    private final boolean chinese;
    private final ConcurrentHashMap<String, Integer> resourceIds = new ConcurrentHashMap<>();

    private static final Map<String, String[]> FALLBACKS = new HashMap<>();

    static {
        put("ListenVideo", "听视频", "Listen to video");
        put("ListenVideoBack", "返回画面", "Watch video");
        put("ListenVideoFailed", "暂时无法转入后台播放", "Could not start background playback");
        put("ListenVideoPrevious", "上一个视频", "Previous video");
        put("ListenVideoNext", "下一个视频", "Next video");
        put("ListenVideoPlay", "播放", "Play");
        put("ListenVideoPause", "暂停", "Pause");
        put("ListenVideoSpeed", "播放速度", "Playback speed");
        put("SpeedSlow", "慢", "Slow");
        put("SpeedNormal", "正常", "Normal");
        put("SpeedMedium", "稍快", "Medium");
        put("SpeedFast", "快", "Fast");
        put("SpeedVeryFast", "更快", "Very fast");
        put("SpeedSuperFast", "非常快", "Super fast");
        put("ListenVideoLoading", "正在加载…", "Loading…");
        put("ListenVideoLoadRetry", "加载失败，点此重试", "Could not load. Tap to retry");
        put("ListenVideoTimerRemaining", "定时关闭 %1$s", "Sleep timer %1$s");
        put("ListenVideoTimerAfterCurrent", "播完停止", "After this video");
        put("ListenVideoMore", "加载更多视频", "Load more videos");
        put("AccDescrPlayerSeekBar", "播放进度", "Playback progress");
        put("VideoPlaylist", "视频列表", "Video playlist");
        put("VideoPlaying", "正在播放", "Playing");
        put("VideoStoredLocally", "本地完整文件", "Complete local file");
        put("VideoNotStoredLocally", "未完整下载", "Not fully downloaded");
        put("VideoPlaylistOpenFailed", "无法打开所选视频", "Could not open this video");
        put("VideoSleepTimer", "定时关闭", "Sleep Timer");
        put("AudioSleepTimerCustomHint", "选择音频继续播放的时长，暂停和缓冲时停止计时", "Choose listening time; pauses and buffering do not count");
        put("AudioSleepTimerFinished", "已暂停音频", "Audio paused");
        put("VideoSleepTimerOff", "关闭", "Off");
        put("VideoSleepTimerAfterCurrent", "当前视频播放结束后", "After current video");
        put("VideoSleepTimerAfterCurrentSet", "将在当前视频播放结束后停止", "Stops after the current video");
        put("VideoSleepTimerSetFor", "将在 %1$s 后停止", "Stops after %1$s");
        put("VideoSleepTimerDisabled", "已关闭定时关闭", "Sleep timer disabled");
        put("VideoSleepTimerFinished", "已暂停视频", "Video paused");
        put("VideoSleepTimerQuickChoices", "快速选择", "Quick choices");
        put("VideoSleepTimerChoose", "选择视频停止时间", "Choose when to stop playback");
        put("VideoSleepTimerCustom", "自定义时长", "Custom duration");
        put("VideoSleepTimerCustomHint", "选择视频继续播放的时长", "Choose how long the video should continue playing");
        put("VideoSleepTimerApply", "应用", "Apply");
        put("VideoSleepTimerCancel", "取消定时关闭", "Cancel sleep timer");
        put("VideoSleepTimerHours", "小时", "hours");
        put("VideoSleepTimerMinutes", "分钟", "minutes");
        put("VideoSleepTimerDurationHoursMinutes", "%1$d 小时 %2$d 分钟", "%1$d h %2$d min");
        put("VideoSleepTimerDurationHours", "%1$d 小时", "%1$d h");
        put("VideoSleepTimerDurationMinutes", "%1$d 分钟", "%1$d min");
        put("Close", "关闭", "Close");
        put("Cancel", "取消", "Cancel");
        put("AttachVideo", "视频", "Video");
        put("FromYou", "你", "You");
        put("AudioUnknownTitle", "未知曲目", "Unknown Track");
    }

    private static void put(String key, String zh, String en) {
        FALLBACKS.put(key, new String[]{zh, en});
    }

    HostStrings(HostBridge bridge, HostBridge.Logger logger) {
        this.bridge = bridge;
        this.logger = logger;
        this.localeController = bridge.loadClass("org.telegram.messenger.LocaleController");
        boolean isZh = false;
        try {
            isZh = Locale.getDefault().getLanguage().equalsIgnoreCase("zh");
        } catch (Throwable t) {
            logger.log("读取当前语言失败，文案回退到英文", t);
        }
        this.chinese = isZh;
    }

    String get(Context context, String key) {
        Integer id = resourceIds.get(key);
        if (id == null) {
            id = context.getResources().getIdentifier(key, "string", context.getPackageName());
            resourceIds.put(key, id);
        }
        if (id != 0) {
            try {
                return context.getString(id);
            } catch (Throwable t) {
                logger.log("读取宿主字符串 " + key + " 失败", t);
            }
        }
        String[] fallback = FALLBACKS.get(key);
        if (fallback == null) return key;
        return chinese ? fallback[0] : fallback[1];
    }

    String format(Context context, String key, Object... args) {
        try {
            return String.format(get(context, key), args);
        } catch (Throwable t) {
            logger.log("格式化字符串 " + key + " 失败", t);
            return get(context, key);
        }
    }

    String minutes(Context context, int minutes) {
        Object formatted = bridge.invokeStatic(
                localeController,
                "formatPluralString",
                "Minutes",
                minutes
        );
        if (formatted instanceof CharSequence) {
            return formatted.toString();
        }
        return chinese ? minutes + " 分钟" : minutes + " min";
    }

    String duration(Context context, int minutes) {
        int hours = minutes / 60;
        int remaining = minutes % 60;
        if (hours > 0 && remaining > 0) {
            return format(context, "VideoSleepTimerDurationHoursMinutes", hours, remaining);
        } else if (hours > 0) {
            return format(context, "VideoSleepTimerDurationHours", hours);
        } else {
            return format(context, "VideoSleepTimerDurationMinutes", remaining);
        }
    }
}
