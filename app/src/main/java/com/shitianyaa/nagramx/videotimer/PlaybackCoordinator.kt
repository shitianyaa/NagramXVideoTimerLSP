package com.shitianyaa.nagramx.videotimer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

internal class PlaybackCoordinator(
    private val module: XposedModule,
    private val profile: HostProfile,
) {
    private val bridge = profile.bridge
    private val mainHandler = Handler(Looper.getMainLooper())
    private val injectedMenus = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val injectionAttempts = Collections.synchronizedMap(WeakHashMap<Any, Int>())
    private val viewerSessions = Collections.synchronizedMap(
        IdentityHashMap<Any, ViewerPlaybackSession>(),
    )
    private val controllerSessions = Collections.synchronizedMap(
        IdentityHashMap<Any, ControllerPlaybackSession>(),
    )

    fun scheduleMenuInjection(photoViewer: Any) {
        mainHandler.post { injectMenuIfNeeded(photoViewer) }
    }

    fun onMediaControllerCleanup(controller: Any, transferToViewer: Boolean) {
        if (!transferToViewer) {
            synchronized(controllerSessions) {
                controllerSessions.remove(controller)?.stopTimerOnly()
            }
        }
    }

    private fun injectMenuIfNeeded(photoViewer: Any) {
        synchronized(injectedMenus) {
            if (injectedMenus.containsKey(photoViewer)) return
        }

        val videoMenu = bridge.getField(photoViewer, profile.videoMenuField)
        val activity = bridge.invoke(photoViewer, profile.getParentActivity) as? Activity
        if (videoMenu == null || activity == null) {
            retryMenuInjection(photoViewer)
            return
        }

        val hostTimerMenu = if (profile.nativeTimerSheet == null) {
            createHostStyleTimerMenu(photoViewer, videoMenu, activity)
        } else {
            null
        }
        val item = hostTimerMenu?.item ?: createSimpleTimerMenuItem(
            photoViewer = photoViewer,
            videoMenu = videoMenu,
            activity = activity,
        )
        if (item == null) {
            log("创建定时菜单项失败")
            return
        }

        synchronized(injectedMenus) {
            injectedMenus[photoViewer] = true
        }
        synchronized(injectionAttempts) {
            injectionAttempts.remove(photoViewer)
        }
        log("已注入 PhotoViewer 定时播放菜单")
    }

    private fun createSimpleTimerMenuItem(
        photoViewer: Any,
        videoMenu: Any,
        activity: Activity,
    ): View? {
        val addSubItem = bridge.findMethod(videoMenu.javaClass, "addSubItem", 3) {
            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                CharSequence::class.java.isAssignableFrom(it.parameterTypes[2])
        } ?: return null
        val item = bridge.invoke(
            videoMenu,
            addSubItem,
            MENU_ID,
            resolveHostTimerIcon(activity),
            timerTitle(activity).toString(),
        ) as? View ?: return null
        styleMenuItem(item)
        item.setOnClickListener {
            bridge.invokeNamed(videoMenu, "closeSubMenu")
            showTimerDialog(photoViewer, activity)
        }
        return item
    }

    /**
     * Prefer the exact timer layout shipped by compatible NagramX builds. If it is unavailable,
     * reproduce the original ActionBar swipe-back page with the same host primitives.
     */
    private fun createHostStyleTimerMenu(
        photoViewer: Any,
        videoMenu: Any,
        activity: Activity,
    ): HostTimerMenu? {
        return createHostProvidedTimerMenu(photoViewer, videoMenu, activity)
            ?: createReplicatedTimerMenu(photoViewer, videoMenu, activity)
    }

    private fun createHostProvidedTimerMenu(
        photoViewer: Any,
        videoMenu: Any,
        activity: Activity,
    ): HostTimerMenu? {
        val layoutClass = profile.legacyTimerLayoutClass ?: return null
        val callbackClass = layoutClass.declaredClasses.firstOrNull {
            it.simpleName == "Callback" && it.isInterface
        } ?: return null
        val parentPopup = bridge.invokeNamed(videoMenu, "getPopupLayout") ?: return null
        val parentSwipeBack = bridge.invokeNamed(parentPopup, "getSwipeBack") ?: return null
        val callback = Proxy.newProxyInstance(
            layoutClass.classLoader,
            arrayOf(callbackClass),
        ) { proxy, method, args ->
            when (method.name) {
                "onTimerSelected" -> {
                    val mode = (args?.getOrNull(0) as? Number)?.toInt()
                        ?: return@newProxyInstance null
                    val minutes = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                    onTimerChoice(
                        photoViewer = photoViewer,
                        activity = activity,
                        videoMenu = videoMenu,
                        choice = TimerChoice("", mode, minutes),
                    )
                    null
                }

                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NagramXVideoTimerCallback"
                else -> null
            }
        }
        val timerLayout = bridge.newInstance(
            layoutClass,
            activity,
            parentSwipeBack,
            callback,
        ) ?: return null
        val layoutView = bridge.getField(timerLayout, "layout") as? View ?: return null
        val addSwipeBackItem = findAddSwipeBackItem(videoMenu) ?: return null
        val item = bridge.invoke(
            videoMenu,
            addSwipeBackItem,
            resolveHostTimerIcon(activity),
            null,
            timerTitle(activity).toString(),
            layoutView,
        ) as? View ?: return null
        styleMenuItem(item)
        val update = bridge.findMethod(layoutClass, "update", 2) {
            it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType }
        }
        item.setOnClickListener {
            val state = currentTimerState(photoViewer)
            bridge.invoke(timerLayout, update, state.mode, state.minutes)
            bridge.invokeNamed(item, "openSwipeBack")
        }
        return HostTimerMenu(item)
    }

    private fun createReplicatedTimerMenu(
        photoViewer: Any,
        videoMenu: Any,
        activity: Activity,
    ): HostTimerMenu? {
        val parentPopup = bridge.invokeNamed(videoMenu, "getPopupLayout") ?: return null
        val parentSwipeBack = bridge.invokeNamed(parentPopup, "getSwipeBack") ?: return null
        val popupClass = bridge.loadClass(
            "org.telegram.ui.ActionBar.ActionBarPopupWindow\$ActionBarPopupWindowLayout",
        ) ?: return null
        // The original VideoSleepTimerLayout is a bare secondary page. It deliberately has no
        // popup background and no nested swipe-back container of its own.
        val secondaryPage = bridge.newInstance(popupClass, activity, 0, null)
            ?: bridge.newInstance(popupClass, activity, 0, null, 0)
            ?: return null
        bridge.invokeNamed(secondaryPage, "setFitItems", true)
        val page = secondaryPage as? ViewGroup ?: return null

        val addSwipeBackItem = findAddSwipeBackItem(videoMenu) ?: return null
        val item = bridge.invoke(
            videoMenu,
            addSwipeBackItem,
            resolveHostTimerIcon(activity),
            null,
            timerTitle(activity).toString(),
            page,
        ) as? View ?: return null
        styleMenuItem(item)

        val addPopupItem = findPopupItemAdder() ?: return null
        fun addRow(icon: Int, text: CharSequence, checked: Boolean): View? {
            val row = bridge.invoke(null, addPopupItem, page, icon, text, checked, null) as? View
            if (row != null) styleMenuItem(row)
            return row
        }

        val backRow = addRow(
            resolveHostResource(activity, "drawable", "msg_arrow_back"),
            hostString(activity, "Back", "返回"),
            false,
        ) ?: return null
        if (!addTimerGap(page, activity)) return null

        val choices = timerChoices(activity)
        val choiceRows = choices.mapNotNull { choice ->
            addRow(0, choice.label, true)?.let { row -> PopupTimerRow(row, choice) }
        }
        if (choiceRows.size != choices.size) return null

        backRow.setOnClickListener {
            bridge.invokeNamed(parentSwipeBack, "closeForeground")
        }
        item.setOnClickListener {
            updateTimerRows(choiceRows, currentTimerState(photoViewer))
            bridge.invokeNamed(item, "openSwipeBack")
        }
        choiceRows.forEach { row ->
            row.view.setOnClickListener {
                onTimerChoice(
                    photoViewer = photoViewer,
                    activity = activity,
                    videoMenu = videoMenu,
                    choice = row.choice,
                )
            }
        }
        return HostTimerMenu(item)
    }

    private fun findAddSwipeBackItem(videoMenu: Any): Method? {
        return bridge.findMethod(videoMenu.javaClass, "addSwipeBackItem", 4) {
            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                !it.parameterTypes[1].isPrimitive &&
                CharSequence::class.java.isAssignableFrom(it.parameterTypes[2]) &&
                View::class.java.isAssignableFrom(it.parameterTypes[3])
        }
    }

    private fun findPopupItemAdder(): Method? {
        val actionBarMenuItem = bridge.loadClass("org.telegram.ui.ActionBar.ActionBarMenuItem")
            ?: return null
        return bridge.findMethod(actionBarMenuItem, "addItem", 5) {
            Modifier.isStatic(it.modifiers) &&
                ViewGroup::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                CharSequence::class.java.isAssignableFrom(it.parameterTypes[2]) &&
                it.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                !it.parameterTypes[4].isPrimitive
        }
    }

    private fun addTimerGap(parent: ViewGroup, activity: Activity): Boolean {
        return try {
            val gap = FrameLayout(activity)
            gap.minimumWidth = dp(activity, 196)
            gap.setBackgroundColor(0xff181818.toInt())
            parent.addView(gap)
            val params = gap.layoutParams
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = dp(activity, 8)
            gap.layoutParams = params
            true
        } catch (t: Throwable) {
            log("创建宿主风格定时菜单分隔条失败", t)
            false
        }
    }

    private fun updateTimerRows(rows: List<PopupTimerRow>, state: TimerState) {
        rows.forEach { row ->
            val selected = when (row.choice.mode) {
                HostProfile.MODE_OFF -> state.mode == HostProfile.MODE_OFF
                profile.timerAfterCurrentMode -> state.mode == profile.timerAfterCurrentMode
                profile.timerDurationMode -> state.mode == profile.timerDurationMode &&
                    state.minutes == row.choice.minutes
                else -> false
            }
            bridge.invokeNamed(row.view, "setChecked", selected)
        }
    }

    private fun onTimerChoice(
        photoViewer: Any,
        activity: Activity,
        videoMenu: Any?,
        choice: TimerChoice,
    ) {
        bridge.invokeNamed(videoMenu, "closeSubMenu")
        if (choice.mode == HostProfile.MODE_OFF) {
            cancelTimer(activity)
            return
        }
        if (!start(photoViewer, activity, choice.mode, choice.minutes)) {
            Toast.makeText(
                activity,
                "当前视频尚未创建播放器，请先点一下播放按钮",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun retryMenuInjection(photoViewer: Any) {
        val attempt = synchronized(injectionAttempts) {
            val next = (injectionAttempts[photoViewer] ?: 0) + 1
            injectionAttempts[photoViewer] = next
            next
        }
        if (attempt > MAX_MENU_INJECTION_ATTEMPTS) {
            synchronized(injectionAttempts) {
                injectionAttempts.remove(photoViewer)
            }
            log("PhotoViewer 菜单在重试后仍未准备好，停止本次注入")
            return
        }
        mainHandler.postDelayed(
            { injectMenuIfNeeded(photoViewer) },
            MENU_INJECTION_RETRY_MS * attempt,
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun resolveHostTimerIcon(activity: Activity): Int {
        return listOf("baseline_timer_24", "msg_timer", "menu_video_loop")
            .firstNotNullOfOrNull { name ->
                resolveHostResource(activity, "drawable", name).takeIf { it != 0 }
            } ?: 0
    }

    private fun resolveHostResource(activity: Activity, type: String, name: String): Int {
        return activity.resources.getIdentifier(name, type, activity.packageName)
    }

    private fun hostString(activity: Activity, name: String, fallback: String): CharSequence {
        val id = resolveHostResource(activity, "string", name)
        return if (id != 0) activity.getText(id) else fallback
    }

    private fun timerTitle(activity: Activity): CharSequence {
        return hostString(activity, "VideoSleepTimer", MENU_TITLE)
    }

    private fun timerChoices(activity: Activity): List<TimerChoice> = buildList {
        add(
            TimerChoice(
                hostString(activity, "VideoSleepTimerOff", "关闭定时播放").toString(),
                HostProfile.MODE_OFF,
                0,
            ),
        )
        add(
            TimerChoice(
                hostString(
                    activity,
                    "VideoSleepTimerAfterCurrent",
                    "当前视频结束后停止",
                ).toString(),
                profile.timerAfterCurrentMode,
                0,
            ),
        )
        listOf(10, 20, 30, 60, 90).forEach { minutes ->
            add(
                TimerChoice(
                    formatHostMinutes(minutes),
                    profile.timerDurationMode,
                    minutes,
                ),
            )
        }
    }

    private fun formatHostMinutes(minutes: Int): String {
        val localeController = bridge.loadClass("org.telegram.messenger.LocaleController")
        val formatted = bridge.invokeStatic(
            localeController,
            "formatPluralString",
            "Minutes",
            minutes,
        ) as? CharSequence
        return formatted?.toString() ?: "$minutes 分钟"
    }

    private fun showTimerDialog(photoViewer: Any, activity: Activity) {
        profile.nativeTimerSheet?.let { sheet ->
            bridge.invoke(photoViewer, sheet)
            return
        }

        val choices = timerChoices(activity)
        if (showHostAlertDialog(photoViewer, activity, choices)) return

        AlertDialog.Builder(activity)
            .setTitle(timerTitle(activity))
            .setItems(choices.map { it.label }.toTypedArray()) { _, which ->
                val choice = choices.getOrNull(which) ?: return@setItems
                onTimerChoice(photoViewer, activity, null, choice)
            }
            .setNegativeButton(hostString(activity, "Cancel", "取消"), null)
            .show()
    }

    private fun showHostAlertDialog(
        photoViewer: Any,
        activity: Activity,
        choices: List<TimerChoice>,
    ): Boolean {
        val builderClass = bridge.loadClass("org.telegram.ui.ActionBar.AlertDialog\$Builder")
            ?: return false
        val builder = bridge.newInstance(builderClass, activity) ?: return false
        val setItems = bridge.findMethod(builderClass, "setItems", 2) {
            it.parameterTypes[0].isArray &&
                DialogInterface.OnClickListener::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: return false
        val show = bridge.findMethod(builderClass, "show", 0) ?: return false
        val listener = DialogInterface.OnClickListener { _, which ->
            val choice = choices.getOrNull(which) ?: return@OnClickListener
            if (choice.mode == HostProfile.MODE_OFF) {
                cancelTimer(activity)
            } else if (!start(photoViewer, activity, choice.mode, choice.minutes)) {
                Toast.makeText(
                    activity,
                    "当前视频尚未创建播放器，请先点一下播放按钮",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        bridge.invokeNamed(builder, "setTitle", timerTitle(activity))
        bridge.invoke(builder, setItems, choices.map { it.label }.toTypedArray(), listener)
        bridge.invokeNamed(
            builder,
            "setNegativeButton",
            hostString(activity, "Cancel", "取消"),
            null,
        )
        bridge.invoke(builder, show)
        return true
    }

    private fun currentTimerState(photoViewer: Any): TimerState {
        activeViewerSession(photoViewer)?.let { session ->
            return TimerState(session.mode, session.selectedMinutes)
        }

        val controller = profile.currentMediaController()
        val nativeMode = (bridge.invoke(controller, profile.nativeTimerMode) as? Number)?.toInt()
        val nativeRemaining = (bridge.invoke(
            controller,
            profile.nativeTimerRemaining,
        ) as? Number)?.toLong() ?: 0L
        val resolvedNativeMode = nativeMode
            ?: profile.timerDurationMode.takeIf { nativeRemaining > 0L }
        if (resolvedNativeMode != null && resolvedNativeMode != HostProfile.MODE_OFF) {
            val minutes = if (resolvedNativeMode == profile.timerDurationMode) {
                ((nativeRemaining + 59_999L) / 60_000L).coerceAtLeast(1L).toInt()
            } else {
                0
            }
            return TimerState(resolvedNativeMode, minutes)
        }

        if (controller != null) {
            synchronized(controllerSessions) {
                controllerSessions[controller]?.takeIf { it.isActive }?.let { session ->
                    return TimerState(session.mode, session.selectedMinutes)
                }
            }
        }
        return TimerState(HostProfile.MODE_OFF, 0)
    }

    private fun activeViewerSession(photoViewer: Any): ViewerPlaybackSession? {
        return synchronized(viewerSessions) {
            viewerSessions[photoViewer]?.takeIf { it.isActive }
                ?: viewerSessions.values.firstOrNull { it.isActive }
        }
    }

    private fun cancelTimer(activity: Activity) {
        var cancelled = false
        val controller = profile.currentMediaController()
        if (controller != null && profile.isNativeTimerActive(controller)) {
            profile.nativeTimerClear?.let { clearMethod ->
                bridge.invoke(controller, clearMethod)
                cancelled = true
            }
        }

        val viewerToCancel = synchronized(viewerSessions) {
            val active = viewerSessions.values.toList()
            viewerSessions.clear()
            active
        }
        viewerToCancel.forEach { session ->
            session.stopTimerOnly(restoreLooping = true)
            cancelled = true
        }

        val controllerToCancel = synchronized(controllerSessions) {
            val active = controllerSessions.values.toList()
            controllerSessions.clear()
            active
        }
        controllerToCancel.forEach { session ->
            session.stopTimerOnly()
            cancelled = true
        }

        Toast.makeText(
            activity,
            if (cancelled) "已取消定时器，视频继续播放" else "当前没有活动定时器",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun start(photoViewer: Any, activity: Activity, mode: Int, minutes: Int): Boolean {
        if (profile.canKeepPhotoViewerForTimer &&
            startPhotoViewerTimer(photoViewer, activity, mode, minutes)
        ) {
            return true
        }

        val nativeStart = profile.nativeBackgroundStart
        if (nativeStart != null) {
            val started = bridge.invoke(photoViewer, nativeStart, mode, minutes) as? Boolean == true
            if (started) {
                closePhoto(photoViewer)
                Toast.makeText(activity, "已开启后台播放定时器", Toast.LENGTH_SHORT).show()
            }
            return started
        }
        return startControllerFallback(photoViewer, activity, mode, minutes)
    }

    /**
     * Old NagramX builds already have a complete PhotoViewer -> PipVideoOverlay path. Keep that
     * player as the sole owner so the original pinned playback remains available. BUFFERING is a
     * valid state: the timer is accepted immediately and PiP opens after the first texture arrives.
     */
    private fun startPhotoViewerTimer(
        photoViewer: Any,
        activity: Activity,
        mode: Int,
        minutes: Int,
    ): Boolean {
        if (mode == profile.timerDurationMode && minutes <= 0) return false
        val player = profile.currentVideoPlayer(photoViewer) ?: return false
        val message = profile.currentMessage(photoViewer) ?: return false
        val isVideo = bridge.invokeNamed(message, "isVideo") as? Boolean ?: false
        val isLivePhoto = bridge.invokeNamed(message, "isLivePhoto") as? Boolean ?: false
        if (!isVideo || isLivePhoto) return false

        val playbackState = (bridge.invokeNamed(player, "getPlaybackState") as? Number)?.toInt()
        if (playbackState == EXOPLAYER_STATE_ENDED) {
            bridge.invokeNamed(player, "seekTo", 0L)
        }
        val previous = synchronized(viewerSessions) {
            val active = viewerSessions.values.toList()
            viewerSessions.clear()
            active
        }
        previous.forEach { it.stopTimerOnly(restoreLooping = true) }

        val wasLooping = bridge.invokeNamed(player, "isLooping") as? Boolean == true
        val changedLooping = mode == profile.timerAfterCurrentMode && wasLooping
        if (changedLooping) {
            bridge.invokeNamed(player, "setLooping", false)
        }

        val session = ViewerPlaybackSession(
            coordinator = this,
            activity = activity,
            photoViewer = photoViewer,
            message = message,
            player = player,
            mode = mode,
            selectedMinutes = minutes,
            remainingMs = if (mode == profile.timerDurationMode) minutes * 60_000L else 0L,
            restoreLooping = changedLooping,
        )
        synchronized(viewerSessions) {
            viewerSessions[photoViewer] = session
        }

        bridge.setField(photoViewer, profile.photoManuallyPausedField, false)
        if (profile.photoPlayMethod != null) {
            bridge.invoke(photoViewer, profile.photoPlayMethod)
        } else {
            bridge.invokeNamed(player, "play")
        }
        session.start()

        val ready = playbackState == EXOPLAYER_STATE_READY ||
            bridge.invokeNamed(player, "isPlaying") == true
        Toast.makeText(
            activity,
            if (ready) "已开启定时播放，正在切换置顶播放" else "已开启定时播放，视频就绪后自动置顶",
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun tryOpenPip(session: ViewerPlaybackSession) {
        if (!session.isActive || session.pipOpened || profile.isViewerInPip(session.photoViewer)) {
            session.pipOpened = profile.isViewerInPip(session.photoViewer)
            return
        }
        if (session.pipAttempts >= MAX_PIP_ATTEMPTS) return

        val playbackState = (bridge.invokeNamed(
            session.player,
            "getPlaybackState",
        ) as? Number)?.toInt()
        val textureState = bridge.getField(
            session.photoViewer,
            profile.photoTextureUploadedField,
        ) as? Boolean
        if (textureState != true &&
            (profile.photoTextureUploadedField != null || playbackState != EXOPLAYER_STATE_READY)
        ) {
            return
        }

        val permission = profile.hasPipPermission(session.activity)
        if (permission == false) {
            if (!session.permissionPrompted) {
                session.permissionPrompted = true
                bridge.invoke(session.photoViewer, profile.photoSwitchToPipMethod, false)
            }
            return
        }

        session.pipAttempts++
        bridge.invoke(session.photoViewer, profile.photoSwitchToPipMethod, false)
        if (profile.isViewerInPip(session.photoViewer)) {
            session.pipOpened = true
            log("定时播放已沿用 PhotoViewer 原生 PiP 链路")
        }
    }

    private fun finishViewerSession(session: ViewerPlaybackSession) {
        session.stopTimerOnly(restoreLooping = true)
        synchronized(viewerSessions) {
            if (viewerSessions[session.photoViewer] === session) {
                viewerSessions.remove(session.photoViewer)
            }
        }
        bridge.setField(session.photoViewer, profile.photoManuallyPausedField, true)
        if (profile.photoPauseMethod != null) {
            bridge.invoke(session.photoViewer, profile.photoPauseMethod)
        } else {
            bridge.invokeNamed(session.player, "pause")
        }
        Toast.makeText(session.activity, "定时结束，视频已暂停", Toast.LENGTH_SHORT).show()
    }

    private fun abandonViewerSession(session: ViewerPlaybackSession) {
        session.stopTimerOnly(restoreLooping = true)
        synchronized(viewerSessions) {
            if (viewerSessions[session.photoViewer] === session) {
                viewerSessions.remove(session.photoViewer)
            }
        }
    }

    private fun isViewerPlayerOwned(session: ViewerPlaybackSession): Boolean {
        return profile.currentVideoPlayer(session.photoViewer) === session.player
    }

    private fun isSameMessage(first: Any?, second: Any?): Boolean {
        if (first === second) return first != null
        if (first == null || second == null) return false
        val firstId = (bridge.invokeNamed(first, "getId") as? Number)?.toInt() ?: return false
        val secondId = (bridge.invokeNamed(second, "getId") as? Number)?.toInt() ?: return false
        if (firstId == 0 || firstId != secondId) return false
        val firstDialog = (bridge.invokeNamed(first, "getDialogId") as? Number)?.toLong()
            ?: return false
        val secondDialog = (bridge.invokeNamed(second, "getDialogId") as? Number)?.toLong()
            ?: return false
        return firstDialog == secondDialog
    }

    /** Last-resort path for builds that have no PhotoViewer PiP entry. */
    private fun startControllerFallback(
        photoViewer: Any,
        activity: Activity,
        mode: Int,
        minutes: Int,
    ): Boolean {
        val player = profile.currentVideoPlayer(photoViewer) ?: return false
        val message = profile.currentMessage(photoViewer) ?: return false
        val isVideo = bridge.invokeNamed(message, "isVideo") as? Boolean ?: false
        val isLivePhoto = bridge.invokeNamed(message, "isLivePhoto") as? Boolean ?: false
        if (!isVideo || isLivePhoto) return false

        val controller = profile.currentMediaController() ?: return false
        val directInject = profile.legacyInjectPlayerMethod
        if (directInject == null && profile.transferPlayerMethod == null) return false

        val wasLooping = bridge.invokeNamed(player, "isLooping") as? Boolean == true
        val changedLooping = wasLooping
        if (changedLooping) {
            bridge.invokeNamed(player, "setLooping", false)
        }
        val transferred = if (directInject != null) {
            transferPlayerDirectly(photoViewer, controller, player, message, directInject)
        } else {
            transferPlayerWithHostEntry(photoViewer, player)
        }
        if (!transferred) {
            if (changedLooping) {
                bridge.invokeNamed(player, "setLooping", true)
            }
            log("兼容播放器转移未生效，保留 PhotoViewer 当前状态")
            return false
        }

        val transferredPlayer = bridge.getField(controller, profile.controllerVideoPlayerField)
            ?: player
        val session = ControllerPlaybackSession(
            coordinator = this,
            controller = controller,
            message = message,
            player = transferredPlayer,
            mode = mode,
            selectedMinutes = minutes,
            remainingMs = if (mode == profile.timerDurationMode) minutes * 60_000L else 0L,
            restoreLooping = changedLooping,
        )
        synchronized(controllerSessions) {
            controllerSessions.remove(controller)?.stopTimerOnly()
            controllerSessions[controller] = session
        }
        startMusicPlayerService(activity)
        session.start()
        closePhoto(photoViewer)
        Toast.makeText(activity, "已开启后台兼容定时播放", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun transferPlayerDirectly(
        photoViewer: Any,
        controller: Any,
        player: Any,
        message: Any,
        injectMethod: Method,
    ): Boolean {
        bridge.setField(photoViewer, profile.photoPlayerTransferredField, true)
        bridge.invoke(controller, injectMethod, player, message)
        val transferredPlayer = bridge.getField(controller, profile.controllerVideoPlayerField)
        val transferredMessage = bridge.invokeNamed(controller, "getPlayingMessageObject")
        val accepted = transferredPlayer === player || transferredMessage === message
        if (!accepted) {
            bridge.setField(photoViewer, profile.photoPlayerTransferredField, false)
            return false
        }

        bridge.setField(photoViewer, profile.photoVideoPlayerField, null)
        bridge.setField(photoViewer, profile.photoPlayerInjectedField, false)
        bridge.setField(photoViewer, profile.photoIsPlayingField, false)
        return true
    }

    private fun transferPlayerWithHostEntry(photoViewer: Any, player: Any): Boolean {
        val transferMethod = profile.transferPlayerMethod ?: return false
        val isPlaying = bridge.invokeNamed(player, "isPlaying") as? Boolean ?: false
        if (!isPlaying) return false
        bridge.invoke(photoViewer, transferMethod)
        return bridge.getField(
            profile.currentMediaController(),
            profile.controllerVideoPlayerField,
        ) === player
    }

    private fun startMusicPlayerService(activity: Activity) {
        val serviceClass = profile.musicPlayerService ?: return
        try {
            activity.startForegroundService(Intent(activity, serviceClass))
        } catch (t: Throwable) {
            log("启动宿主 MusicPlayerService 失败", t)
        }
    }

    private fun closePhoto(photoViewer: Any) {
        bridge.invoke(photoViewer, profile.closePhoto, false, true)
    }

    private fun stopControllerSession(session: ControllerPlaybackSession) {
        session.stopTimerOnly()
        bridge.invokeNamed(session.player, "pause")
        bridge.invokeNamed(session.controller, "pauseMessage", session.message, false)
        synchronized(controllerSessions) {
            if (controllerSessions[session.controller] === session) {
                controllerSessions.remove(session.controller)
            }
        }
    }

    private fun styleMenuItem(item: View) {
        bridge.invokeNamed(item, "setColors", 0xfffafafa.toInt(), 0xfffafafa.toInt())
        bridge.invokeNamed(item, "setSelectorColor", 0x0fffffff)
    }

    private fun dp(activity: Activity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun log(message: String, throwable: Throwable? = null) {
        module.log(android.util.Log.INFO, TAG, message, throwable)
    }

    private data class TimerChoice(
        val label: String,
        val mode: Int,
        val minutes: Int,
    )

    private data class TimerState(
        val mode: Int,
        val minutes: Int,
    )

    private data class PopupTimerRow(
        val view: View,
        val choice: TimerChoice,
    )

    private data class HostTimerMenu(
        val item: View,
    )

    internal class ViewerPlaybackSession(
        private val coordinator: PlaybackCoordinator,
        val activity: Activity,
        val photoViewer: Any,
        val message: Any,
        val player: Any,
        val mode: Int,
        val selectedMinutes: Int,
        private var remainingMs: Long,
        private val restoreLooping: Boolean,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var lastTick = SystemClock.elapsedRealtime()
        var isActive = false
            private set
        var pipOpened = false
        var pipAttempts = 0
        var permissionPrompted = false

        private val tick = object : Runnable {
            override fun run() {
                if (!isActive) return
                if (!coordinator.isViewerPlayerOwned(this@ViewerPlaybackSession)) {
                    coordinator.abandonViewerSession(this@ViewerPlaybackSession)
                    return
                }

                coordinator.tryOpenPip(this@ViewerPlaybackSession)
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastTick).coerceAtLeast(0L)
                lastTick = now
                val playing = coordinator.bridge.invokeNamed(player, "isPlaying") as? Boolean == true
                if (playing && mode == coordinator.profile.timerDurationMode) {
                    remainingMs = (remainingMs - elapsed).coerceAtLeast(0L)
                }

                if (mode == coordinator.profile.timerAfterCurrentMode) {
                    val currentMessage = coordinator.profile.currentMessage(photoViewer)
                    if (!coordinator.isSameMessage(currentMessage, message)) {
                        coordinator.abandonViewerSession(this@ViewerPlaybackSession)
                        return
                    }
                }

                val duration = (coordinator.bridge.invokeNamed(player, "getDuration") as? Number)
                    ?.toLong() ?: 0L
                val position = (coordinator.bridge.invokeNamed(
                    player,
                    "getCurrentPosition",
                ) as? Number)?.toLong() ?: 0L
                val playbackState = (coordinator.bridge.invokeNamed(
                    player,
                    "getPlaybackState",
                ) as? Number)?.toInt()
                val ended = mode == coordinator.profile.timerAfterCurrentMode &&
                    (playbackState == EXOPLAYER_STATE_ENDED ||
                        duration > 0L && position >= duration - END_THRESHOLD_MS)
                if ((mode == coordinator.profile.timerDurationMode && remainingMs <= 0L) || ended) {
                    coordinator.finishViewerSession(this@ViewerPlaybackSession)
                    return
                }
                handler.postDelayed(this, TICK_MS)
            }
        }

        fun start() {
            isActive = true
            lastTick = SystemClock.elapsedRealtime()
            handler.post(tick)
        }

        fun stopTimerOnly(restoreLooping: Boolean) {
            isActive = false
            handler.removeCallbacks(tick)
            if (restoreLooping && this.restoreLooping &&
                coordinator.profile.currentVideoPlayer(photoViewer) === player
            ) {
                coordinator.bridge.invokeNamed(player, "setLooping", true)
            }
        }
    }

    internal class ControllerPlaybackSession(
        private val coordinator: PlaybackCoordinator,
        val controller: Any,
        val message: Any,
        val player: Any,
        val mode: Int,
        val selectedMinutes: Int,
        private var remainingMs: Long,
        private val restoreLooping: Boolean,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var lastTick = SystemClock.elapsedRealtime()
        var isActive = false
            private set

        private val tick = object : Runnable {
            override fun run() {
                if (!isActive) return
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastTick).coerceAtLeast(0L)
                lastTick = now
                val playing = coordinator.bridge.invokeNamed(player, "isPlaying") as? Boolean == true
                if (playing && mode == coordinator.profile.timerDurationMode) {
                    remainingMs = (remainingMs - elapsed).coerceAtLeast(0L)
                }

                val duration = (coordinator.bridge.invokeNamed(player, "getDuration") as? Number)
                    ?.toLong() ?: 0L
                val position = (coordinator.bridge.invokeNamed(
                    player,
                    "getCurrentPosition",
                ) as? Number)?.toLong() ?: 0L
                val playbackState = (coordinator.bridge.invokeNamed(
                    player,
                    "getPlaybackState",
                ) as? Number)?.toInt()
                val ended = mode == coordinator.profile.timerAfterCurrentMode &&
                    (playbackState == EXOPLAYER_STATE_ENDED ||
                        playing && duration > 0L && position >= duration - END_THRESHOLD_MS)
                if ((mode == coordinator.profile.timerDurationMode && remainingMs <= 0L) || ended) {
                    coordinator.stopControllerSession(this@ControllerPlaybackSession)
                    return
                }
                handler.postDelayed(this, TICK_MS)
            }
        }

        fun start() {
            isActive = true
            lastTick = SystemClock.elapsedRealtime()
            handler.post(tick)
        }

        fun stopTimerOnly() {
            isActive = false
            handler.removeCallbacks(tick)
            if (restoreLooping) {
                coordinator.bridge.invokeNamed(player, "setLooping", true)
            }
        }
    }

    companion object {
        private const val TAG = "NagramXVideoTimer"
        private const val MENU_ID = 0x4E585654
        private const val MENU_TITLE = "后台定时播放"
        private const val MAX_MENU_INJECTION_ATTEMPTS = 5
        private const val MENU_INJECTION_RETRY_MS = 200L
        private const val MAX_PIP_ATTEMPTS = 20
        private const val TICK_MS = 500L
        private const val END_THRESHOLD_MS = 250L
        private const val EXOPLAYER_STATE_READY = 3
        private const val EXOPLAYER_STATE_ENDED = 4
    }
}
