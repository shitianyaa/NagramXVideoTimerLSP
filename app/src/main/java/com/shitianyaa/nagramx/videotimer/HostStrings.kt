package com.shitianyaa.nagramx.videotimer

import android.content.Context

/**
 * 文案解析。
 *
 * 原版宿主没有 `VideoSleepTimer*` 系列字符串（那是 fork 才加的），所以先查宿主资源，
 * 查不到再用模块内置字面量。内置文案与 fork 的 `strings_na.xml` 逐条对齐，
 * 这样无论装的是原版还是 fork，用户看到的措辞一致。
 */
internal class HostStrings(
    private val bridge: HostBridge,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val localeController: Class<*>? =
        bridge.loadClass("org.telegram.messenger.LocaleController")

    private val chinese: Boolean by lazy {
        try {
            java.util.Locale.getDefault().language.equals("zh", ignoreCase = true)
        } catch (t: Throwable) {
            logger("读取当前语言失败，文案回退到英文", t)
            false
        }
    }

    /**
     * key 到宿主资源 id 的缓存。
     *
     * `Resources.getIdentifier` 走的是按名字查资源表的慢路径，而 [get] 现在挂在
     * `getMusicTitle` 的返回值上，播放列表每渲染一行都会问一次。id 对固定包名恒定，可以放心缓存；
     * 真正的取值仍每次走 `getString`，宿主运行中切换语言依然即时生效。0 表示宿主没有这个 key。
     */
    private val resourceIds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun get(context: Context, key: String): String {
        val id = resourceIds.getOrPut(key) { context.hostResource("string", key) }
        if (id != 0) {
            runCatching { return context.getString(id) }
                .onFailure { logger("读取宿主字符串 $key 失败", it) }
        }
        val fallback = FALLBACKS[key] ?: return key
        return if (chinese) fallback.first else fallback.second
    }

    fun format(context: Context, key: String, vararg args: Any): String {
        return try {
            String.format(get(context, key), *args)
        } catch (t: Throwable) {
            logger("格式化字符串 $key 失败", t)
            get(context, key)
        }
    }

    /** 复用宿主的复数规则，宿主不可用时退回「N 分钟」。 */
    fun minutes(context: Context, minutes: Int): String {
        val formatted = bridge.invokeStatic(
            localeController,
            "formatPluralString",
            "Minutes",
            minutes,
        ) as? CharSequence
        if (formatted != null) return formatted.toString()
        return if (chinese) "$minutes 分钟" else "$minutes min"
    }

    /** 与 fork 的 `formatVideoSleepTimerDuration` 一致：只有小时、只有分钟、或两者兼有。 */
    fun duration(context: Context, minutes: Int): String {
        val hours = minutes / 60
        val remaining = minutes % 60
        return when {
            hours > 0 && remaining > 0 ->
                format(context, "VideoSleepTimerDurationHoursMinutes", hours, remaining)

            hours > 0 -> format(context, "VideoSleepTimerDurationHours", hours)
            else -> format(context, "VideoSleepTimerDurationMinutes", remaining)
        }
    }

    private companion object {
        /** key 到（中文, 英文）的映射，取自 fork 的 `strings_na.xml`。 */
        val FALLBACKS: Map<String, Pair<String, String>> = mapOf(
            "VideoSleepTimer" to ("定时关闭" to "Sleep Timer"),
            "VideoSleepTimerOff" to ("关闭" to "Off"),
            "VideoSleepTimerAfterCurrent" to ("当前视频播放结束后" to "After current video"),
            "VideoSleepTimerAfterCurrentSet" to
                ("将在当前视频播放结束后停止" to "Stops after the current video"),
            "VideoSleepTimerSetFor" to ("将在 %1\$s 后停止" to "Stops after %1\$s"),
            "VideoSleepTimerDisabled" to ("已关闭定时关闭" to "Sleep timer disabled"),
            "VideoSleepTimerFinished" to ("已暂停视频" to "Video paused"),
            "VideoSleepTimerQuickChoices" to ("快速选择" to "Quick choices"),
            "VideoSleepTimerChoose" to ("选择视频停止时间" to "Choose when to stop playback"),
            "VideoSleepTimerCustom" to ("自定义时长" to "Custom duration"),
            "VideoSleepTimerCustomHint" to
                ("选择视频继续播放的时长" to "Choose how long the video should continue playing"),
            "VideoSleepTimerApply" to ("应用" to "Apply"),
            "VideoSleepTimerCancel" to ("取消定时关闭" to "Cancel sleep timer"),
            "VideoSleepTimerHours" to ("小时" to "hours"),
            "VideoSleepTimerMinutes" to ("分钟" to "minutes"),
            "VideoSleepTimerDurationHoursMinutes" to ("%1\$d 小时 %2\$d 分钟" to "%1\$d h %2\$d min"),
            "VideoSleepTimerDurationHours" to ("%1\$d 小时" to "%1\$d h"),
            "VideoSleepTimerDurationMinutes" to ("%1\$d 分钟" to "%1\$d min"),
            // 以下键原版宿主就有，回退只是防御。
            "Close" to ("关闭" to "Close"),
            "Cancel" to ("取消" to "Cancel"),
            "AttachVideo" to ("视频" to "Video"),
            "FromYou" to ("你" to "You"),
            "AudioUnknownTitle" to ("未知曲目" to "Unknown Track"),
        )
    }
}
