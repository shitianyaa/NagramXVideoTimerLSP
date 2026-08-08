package com.shitianyaa.nagramx.videotimer

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import kotlin.math.ceil

/**
 * 取色与度量。
 *
 * 定时面板始终使用宿主的 `DarkThemeResourceProvider`（PhotoViewer 里的弹窗都用它），
 * 这样无论用户主题是亮还是暗，面板都跟视频播放器保持一致的深色外观。
 * 拿不到 provider 时逐级回退到 `Theme.getColor`，最后回退到与 provider 同值的字面量。
 */
internal class HostTheme(
    private val bridge: HostBridge,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val themeClass: Class<*>? = bridge.loadClass("org.telegram.ui.ActionBar.Theme")
    private val androidUtilities: Class<*>? =
        bridge.loadClass("org.telegram.messenger.AndroidUtilities")

    val resourcesProviderClass: Class<*>? =
        bridge.loadClass("org.telegram.ui.ActionBar.Theme\$ResourcesProvider")

    /** 传给 BottomSheet / NumberPicker 的 provider，可能为 null（宿主会当作默认主题）。 */
    val darkProvider: Any? by lazy {
        bridge.newInstance("org.telegram.ui.Stories.DarkThemeResourceProvider")
    }

    private val keyCache = HashMap<String, Int?>()
    private val colorCache = HashMap<String, Int>()

    private fun keyOf(name: String): Int? = keyCache.getOrPut(name) {
        (bridge.getStaticField(themeClass, "key_$name") as? Number)?.toInt()
    }

    fun color(name: String): Int = colorCache.getOrPut(name) {
        val key = keyOf(name)
        if (key != null) {
            val fromProvider = bridge.invokeNamed(darkProvider, "getColor", key) as? Number
            if (fromProvider != null) return@getOrPut fromProvider.toInt()
            val fromTheme = bridge.invokeStatic(themeClass, "getColor", key) as? Number
            if (fromTheme != null) return@getOrPut fromTheme.toInt()
        }
        DEFAULTS[name] ?: WHITE
    }

    /** 与宿主 `AndroidUtilities.dp` 相同的取整规则，避免和宿主控件差半像素。 */
    fun dp(context: Context, value: Float): Int {
        if (value == 0f) return 0
        val density = context.resources.displayMetrics.density
        return ceil(density * value).toInt()
    }

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    val bold: Typeface by lazy {
        bridge.invokeStatic(androidUtilities, "bold") as? Typeface ?: Typeface.DEFAULT_BOLD
    }

    fun alpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun multAlpha(color: Int, factor: Float): Int =
        alpha(color, ((color ushr 24) * factor).toInt().coerceIn(0, 255))

    /** 圆角实心块，用于按钮和选项底色。 */
    fun roundRect(context: Context, radiusDp: Float, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(color)
        }

    /** 圆角块 + 水波纹，等价于宿主的 `Theme.createSimpleSelectorRoundRectDrawable`。 */
    fun rippleRect(
        context: Context,
        radiusDp: Float,
        background: Int,
        ripple: Int,
    ): Drawable = RippleDrawable(
        ColorStateList.valueOf(ripple),
        roundRect(context, radiusDp, background),
        roundRect(context, radiusDp, WHITE),
    )

    /** 透明底 + 水波纹，用于列表项。 */
    fun rippleTransparent(context: Context, radiusDp: Float, ripple: Int): Drawable =
        rippleRect(context, radiusDp, TRANSPARENT, ripple)

    internal companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TRANSPARENT = 0

        /** 取自 `DarkThemeResourceProvider` 构造函数，作为宿主取色失败时的兜底。 */
        private val DEFAULTS: Map<String, Int> = mapOf(
            "dialogBackground" to 0xFF1F1F1F.toInt(),
            "dialogTextBlack" to -592138,
            "dialogTextGray2" to -8553091,
            "dialogTextGray3" to -8553091,
            "dialogTextBlue2" to 0xFF1A9CFF.toInt(),
            "dialogButton" to -10177041,
            "dialogButtonSelector" to 436207615,
            "dialogTextHint" to -8553091,
            "listSelector" to 0x16FFFFFF,
            "divider" to 0xFF000000.toInt(),
            "sheet_scrollUp" to 0xFF333333.toInt(),
            "featuredStickers_addButton" to 0xFF1A9CFF.toInt(),
            "featuredStickers_buttonText" to WHITE,
            "windowBackgroundWhiteBlackText" to WHITE,
            "windowBackgroundWhiteGrayText" to 0x7FFFFFFF,
            "text_RedRegular" to -1152913,
            "player_actionBarTitle" to WHITE,
            "graySection" to 0xFF292929.toInt(),
            "graySectionText" to -8158332,
        )
    }
}
