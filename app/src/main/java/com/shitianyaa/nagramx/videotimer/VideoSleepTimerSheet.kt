package com.shitianyaa.nagramx.videotimer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.reflect.Proxy

/** 当前定时状态，供面板渲染选中态与副标题。 */
internal data class TimerState(
    val active: Boolean,
    val mode: Int,
    val remainingMinutes: Int,
    val selectedMinutes: Int,
)

/**
 * 定时关闭面板。
 *
 * 用宿主的 `BottomSheet` + `NumberPicker` 复刻 fork 的 `VideoSleepTimerSheet`：
 * 快速选择、当前视频结束后、自定义时长三段。宿主控件缺失时由调用方回退到系统对话框。
 */
internal class VideoSleepTimerSheet(
    private val host: HostProfile,
    private val theme: HostTheme,
    private val strings: HostStrings,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val bridge = host.bridge
    private val bottomSheetClass: Class<*>? =
        bridge.loadClass("org.telegram.ui.ActionBar.BottomSheet")
    private val numberPickerClass: Class<*>? =
        bridge.loadClass("org.telegram.ui.Components.NumberPicker")

    val available: Boolean
        get() = bottomSheetClass != null

    /**
     * 弹出面板。
     *
     * @param onPick 用户确认时回调，`mode` 取 [VideoBackgroundSession.MODE_DURATION] 或
     *   [VideoBackgroundSession.MODE_AFTER_CURRENT]，`minutes` 仅在时长模式下有意义。
     * @return 是否成功弹出。false 表示宿主控件不可用，调用方应回退。
     */
    fun show(
        activity: Activity,
        state: TimerState,
        onPick: (mode: Int, minutes: Int) -> Unit,
        onCancel: () -> Unit,
    ): Boolean {
        val sheetClass = bottomSheetClass ?: return false
        return try {
            val sheet = bridge.newInstance(
                sheetClass,
                activity as Context,
                false,
                theme.darkProvider,
            ) as? Dialog ?: bridge.newInstance(sheetClass, activity as Context, false) as? Dialog
                ?: return false

            val setCustomView = bridge.findMethod(sheetClass, "setCustomView", 1)
            if (setCustomView == null) {
                logger("BottomSheet.setCustomView 不可用，放弃自定义面板", null)
                return false
            }
            val content = buildContent(activity, state, sheet, onPick, onCancel)
            bridge.invoke(sheet, setCustomView, content)
            bridge.invokeNamed(sheet, "fixNavigationBar")
            sheet.show()
            true
        } catch (t: Throwable) {
            logger("弹出定时面板失败", t)
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildContent(
        context: Context,
        state: TimerState,
        sheet: Dialog,
        onPick: (mode: Int, minutes: Int) -> Unit,
        onCancel: () -> Unit,
    ): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        root.addView(handle(context), linear(context, 36, 4, top = 10, gravity = Gravity.CENTER_HORIZONTAL))
        root.addView(
            title(context, strings.get(context, "VideoSleepTimer")),
            linear(context, MATCH, WRAP, top = 14, left = 22, right = 22),
        )
        subtitle(context, state)?.let {
            root.addView(it, linear(context, MATCH, WRAP, top = 4, left = 22, right = 22))
        }

        root.addView(
            sectionLabel(context, strings.get(context, "VideoSleepTimerQuickChoices")),
            linear(context, MATCH, WRAP, top = 18, left = 22, right = 22),
        )
        root.addView(presets(context, state, sheet, onPick), linear(context, MATCH, WRAP, top = 10))

        root.addView(
            afterCurrentRow(context, state, sheet, onPick),
            linear(context, MATCH, 50, top = 6, left = 12, right = 12),
        )

        root.addView(
            sectionLabel(context, strings.get(context, "VideoSleepTimerCustom")),
            linear(context, MATCH, WRAP, top = 14, left = 22, right = 22),
        )
        root.addView(
            hint(context, strings.get(context, "VideoSleepTimerCustomHint")),
            linear(context, MATCH, WRAP, top = 4, left = 22, right = 22),
        )

        val picker = PickerPair(context, state.selectedMinutes)
        picker.attach(root)

        root.addView(
            applyButton(context, sheet, picker, onPick),
            linear(context, MATCH, 48, top = 16, left = 16, right = 16),
        )
        if (state.active) {
            root.addView(
                cancelButton(context, sheet, onCancel),
                linear(context, MATCH, 48, top = 8, left = 16, right = 16, bottom = 8),
            )
        } else {
            root.addView(View(context), linear(context, MATCH, 12))
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(root, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        return scroll
    }

    private fun handle(context: Context): View = View(context).apply {
        background = theme.roundRect(context, 2f, theme.alpha(theme.color("sheet_scrollUp"), 0x66))
    }

    private fun title(context: Context, text: String): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        typeface = theme.bold
        setTextColor(theme.color("dialogTextBlack"))
        setText(text)
    }

    private fun subtitle(context: Context, state: TimerState): TextView? {
        if (!state.active) return null
        val text = when (state.mode) {
            VideoBackgroundSession.MODE_AFTER_CURRENT ->
                strings.get(context, "VideoSleepTimerAfterCurrentSet")

            else -> strings.format(
                context,
                "VideoSleepTimerSetFor",
                strings.duration(context, state.remainingMinutes.coerceAtLeast(1)),
            )
        }
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(theme.color("dialogTextGray2"))
            setText(text)
        }
    }

    private fun sectionLabel(context: Context, text: String): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        typeface = theme.bold
        setTextColor(theme.color("dialogTextBlue2"))
        setText(text)
    }

    private fun hint(context: Context, text: String): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        setTextColor(theme.color("dialogTextGray2"))
        setText(text)
    }

    /** 快速选择行：横向滚动的时长胶囊。 */
    private fun presets(
        context: Context,
        state: TimerState,
        sheet: Dialog,
        onPick: (Int, Int) -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(theme.dp(context, 16), 0, theme.dp(context, 16), 0)
        }
        PRESET_MINUTES.forEachIndexed { index, minutes ->
            val selected = state.active &&
                state.mode == VideoBackgroundSession.MODE_DURATION &&
                state.remainingMinutes == minutes
            val chip = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                typeface = theme.bold
                gravity = Gravity.CENTER
                setPadding(theme.dp(context, 16), 0, theme.dp(context, 16), 0)
                setText(strings.duration(context, minutes))
                if (selected) {
                    setTextColor(theme.color("featuredStickers_buttonText"))
                    background = theme.rippleRect(
                        context,
                        18f,
                        theme.color("featuredStickers_addButton"),
                        theme.alpha(HostTheme.WHITE, 0x30),
                    )
                } else {
                    setTextColor(theme.color("dialogTextBlack"))
                    background = theme.rippleRect(
                        context,
                        18f,
                        theme.alpha(HostTheme.WHITE, 0x14),
                        theme.color("dialogButtonSelector"),
                    )
                }
                setOnClickListener {
                    onPick(VideoBackgroundSession.MODE_DURATION, minutes)
                    sheet.dismiss()
                }
            }
            row.addView(
                chip,
                linear(context, WRAP, 36, left = if (index == 0) 0 else 8),
            )
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
        }
    }

    /** 「当前视频播放结束后」整行选项。 */
    private fun afterCurrentRow(
        context: Context,
        state: TimerState,
        sheet: Dialog,
        onPick: (Int, Int) -> Unit,
    ): View {
        val selected = state.active && state.mode == VideoBackgroundSession.MODE_AFTER_CURRENT
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(context, 10), 0, theme.dp(context, 10), 0)
            setText(strings.get(context, "VideoSleepTimerAfterCurrent"))
            setTextColor(
                if (selected) theme.color("dialogTextBlue2") else theme.color("dialogTextBlack"),
            )
            if (selected) typeface = theme.bold
            background = theme.rippleTransparent(context, 10f, theme.color("listSelector"))
            setOnClickListener {
                onPick(VideoBackgroundSession.MODE_AFTER_CURRENT, 0)
                sheet.dismiss()
            }
        }
    }

    private fun applyButton(
        context: Context,
        sheet: Dialog,
        picker: PickerPair,
        onPick: (Int, Int) -> Unit,
    ): View = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        typeface = theme.bold
        gravity = Gravity.CENTER
        setTextColor(theme.color("featuredStickers_buttonText"))
        setText(strings.get(context, "VideoSleepTimerApply"))
        background = theme.rippleRect(
            context,
            8f,
            theme.color("featuredStickers_addButton"),
            theme.alpha(HostTheme.WHITE, 0x30),
        )
        setOnClickListener {
            val minutes = picker.totalMinutes().coerceAtLeast(1)
            onPick(VideoBackgroundSession.MODE_DURATION, minutes)
            sheet.dismiss()
        }
    }

    /**
     * 小时 + 分钟两个宿主 [org.telegram.ui.Components.NumberPicker]。
     *
     * 单位直接由 formatter 写进滚轮文字（和宿主静音时长选择器一致），
     * 省掉额外的标签视图和居中对齐问题。
     */
    private inner class PickerPair(context: Context, initialMinutes: Int) {
        private val hours: View? = createPicker(
            context,
            max = 23,
            value = (initialMinutes / 60).coerceIn(0, 23),
        ) { strings.format(context, "VideoSleepTimerDurationHours", it) }

        private val minutes: View? = createPicker(
            context,
            max = 59,
            value = (initialMinutes % 60).coerceIn(0, 59),
        ) { strings.minutes(context, it) }

        val usable: Boolean get() = hours != null && minutes != null

        fun attach(root: LinearLayout) {
            val context = root.context
            if (!usable) {
                root.addView(
                    hint(context, strings.get(context, "VideoSleepTimerQuickChoices")),
                    linear(context, MATCH, WRAP, top = 8, left = 22, right = 22),
                )
                return
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.addView(hours, weighted(context))
            row.addView(minutes, weighted(context))
            root.addView(row, linear(context, MATCH, 180, top = 8, left = 12, right = 12))
        }

        fun totalMinutes(): Int {
            if (!usable) return DEFAULT_MINUTES
            val h = bridge.invokeNamed(hours, "getValue") as? Number ?: return DEFAULT_MINUTES
            val m = bridge.invokeNamed(minutes, "getValue") as? Number ?: return DEFAULT_MINUTES
            return h.toInt() * 60 + m.toInt()
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun createPicker(
            context: Context,
            max: Int,
            value: Int,
            format: (Int) -> String,
        ): View? {
            val type = numberPickerClass ?: return null
            val picker = (
                bridge.newInstance(type, context, 20, theme.darkProvider)
                    ?: bridge.newInstance(type, context, 20)
                    ?: bridge.newInstance(type, context)
                ) as? View ?: return null

            bridge.invokeNamed(picker, "setItemCount", 5)
            bridge.invokeNamed(picker, "setTextColor", theme.color("dialogTextBlack"))
            bridge.invokeNamed(picker, "setSelectorColor", theme.color("dialogButtonSelector"))
            bridge.invokeNamed(picker, "setMinValue", 0)
            bridge.invokeNamed(picker, "setMaxValue", max)
            // 宿主的 setWrapSelectorWheel 只是记下意愿，真正放行的条件是
            // `allItemsCount != null && (maxValue - minValue + 1) >= allItemsCount`。
            // 不先调 setAllItemsCount，滚轮会停在两端而不是循环 —— 宿主自己的时/分选择器
            // 也都是 setAllItemsCount(24) / setAllItemsCount(60) 配 setWrapSelectorWheel(true)。
            bridge.invokeNamed(picker, "setAllItemsCount", max + 1)
            bridge.invokeNamed(picker, "setWrapSelectorWheel", true)
            installFormatter(picker, format)
            bridge.invokeNamed(picker, "setValue", value)

            // 滚轮和外层 ScrollView 抢手势，按下时先禁止父级拦截。
            picker.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
            return picker
        }

        private fun installFormatter(picker: View, format: (Int) -> String) {
            val formatterClass =
                bridge.loadClass("org.telegram.ui.Components.NumberPicker\$Formatter") ?: return
            val proxy = try {
                Proxy.newProxyInstance(
                    formatterClass.classLoader,
                    arrayOf(formatterClass),
                ) { _, method, args ->
                    when {
                        method.name == "format" && args?.size == 1 ->
                            format((args[0] as? Number)?.toInt() ?: 0)

                        method.name == "hashCode" -> System.identityHashCode(picker)
                        method.name == "equals" -> false
                        method.name == "toString" -> "NumberPickerFormatter"
                        else -> null
                    }
                }
            } catch (t: Throwable) {
                logger("创建 NumberPicker.Formatter 代理失败", t)
                return
            }
            bridge.findMethod(numberPickerClass, "setFormatter", 1)?.let { setter ->
                bridge.invoke(picker, setter, proxy)
            }
        }

        private fun weighted(context: Context): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    }

    private fun linear(
        context: Context,
        width: Int,
        height: Int,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
        gravity: Int = -1,
    ): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            if (width > 0) theme.dp(context, width) else width,
            if (height > 0) theme.dp(context, height) else height,
        )
        params.setMargins(
            theme.dp(context, left),
            theme.dp(context, top),
            theme.dp(context, right),
            theme.dp(context, bottom),
        )
        if (gravity != -1) params.gravity = gravity
        return params
    }

    private fun cancelButton(context: Context, sheet: Dialog, onCancel: () -> Unit): View =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            gravity = Gravity.CENTER
            setTextColor(theme.color("text_RedRegular"))
            setText(strings.get(context, "VideoSleepTimerCancel"))
            background = theme.rippleTransparent(context, 8f, theme.color("listSelector"))
            setOnClickListener {
                onCancel()
                sheet.dismiss()
            }
        }

    private companion object {
        val PRESET_MINUTES = intArrayOf(15, 30, 45, 60, 90)
        const val DEFAULT_MINUTES = 30
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
