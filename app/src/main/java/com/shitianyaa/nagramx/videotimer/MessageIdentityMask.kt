package com.shitianyaa.nagramx.videotimer

import java.util.concurrent.atomic.AtomicInteger

/**
 * 作用域限定的 [MessageObject] 身份欺骗。
 *
 * fork 里大量改动的形式是把宿主内部的 `isMusic() || isVoice()` 分支扩展成包含视频，而 Hook 只能
 * 拦方法边界、拦不到分支。替代做法是在调用宿主某个方法期间，让指定 MessageObject 的身份查询返回
 * 伪造值，使宿主自己走进音乐分支。
 *
 * 注意原版 `isMusic()` 的实现是 `isMusicMessage(messageOwner) && !isVideo() && !isRoundVideo()`，
 * 所以只把 `isVideo()` 伪造成 false 并不会让视频变成音乐，必须直接伪造 `isMusic()`。
 *
 * 未激活时 [resolve] 只做一次 [AtomicInteger.get]，不触碰 ThreadLocal，因此挂在
 * `isVideo()` 这类全 App 热路径上的开销可以忽略。
 */
internal object MessageIdentityMask {
    /**
     * 可伪造的身份查询。
     *
     * 只保留真正会被 [Spec] 设值的两项：`isMusic` 让宿主走音乐分支，`isVideo` 用来绕过
     * `FragmentContextView.checkPlayer` 的早退守卫。`isVoice` / `isRoundVideo` 没有任何
     * Spec 会设，就不挂 Hook —— 它们和这两个一样是全 App 热路径，白挂只有开销没有收益。
     */
    enum class Kind { MUSIC, VIDEO }

    /**
     * @param targets 以引用相等匹配的 MessageObject；空数组不匹配任何对象。
     * 各 `is*` 字段为 null 表示该项不伪造，交还宿主原值。
     */
    class Spec(
        private val targets: Array<Any>,
        private val isMusic: Boolean? = null,
        private val isVideo: Boolean? = null,
    ) {
        fun matches(target: Any): Boolean {
            for (candidate in targets) {
                if (candidate === target) return true
            }
            return false
        }

        fun valueOf(kind: Kind): Boolean? = when (kind) {
            Kind.MUSIC -> isMusic
            Kind.VIDEO -> isVideo
        }

        companion object {
            /** 让这些视频在宿主眼里变成音乐，用于通知栏、迷你播放器、播放列表。 */
            fun asMusic(vararg targets: Any?): Spec? {
                val present = targets.filterNotNull()
                if (present.isEmpty()) return null
                return Spec(present.toTypedArray(), isMusic = true)
            }

            /**
             * 除伪装成音乐外还要否认自己是视频，用于绕过
             * `FragmentContextView.checkPlayer` 开头 `messageObject.isVideo()` 的早退守卫。
             */
            fun asMusicHidingVideo(vararg targets: Any?): Spec? {
                val present = targets.filterNotNull()
                if (present.isEmpty()) return null
                return Spec(present.toTypedArray(), isMusic = true, isVideo = false)
            }

            /**
             * 只否认自己是视频，不声称是音乐。
             *
             * 用于 `ChatActivity.updateMessagesVisiblePart`：那里既有
             * `checkTextureViewPosition && isVideo()` 这种「是视频就清掉播放器」的判断，
             * 也有 `isVideo() || isRoundVideo() || isVoice() || isMusic()` 这种四选一。
             * 如果顺手把 `isMusic` 也说成 true，后者会被误判成命中，改变宿主行为；
             * 这里只把视频身份摘掉，让所有分支都当它不存在。
             */
            fun hidingVideo(vararg targets: Any?): Spec? {
                val present = targets.filterNotNull()
                if (present.isEmpty()) return null
                return Spec(present.toTypedArray(), isVideo = false)
            }
        }
    }

    private val depth = AtomicInteger()
    private val stack = ThreadLocal.withInitial { ArrayList<Spec>(4) }

    /** 在 [body] 执行期间应用 [spec]；[spec] 为 null 时直接执行，不产生任何开销。 */
    fun <T> around(spec: Spec?, body: () -> T): T {
        if (spec == null) return body()
        val frames = stack.get() ?: return body()
        frames.add(spec)
        depth.incrementAndGet()
        try {
            return body()
        } finally {
            depth.decrementAndGet()
            frames.removeAt(frames.size - 1)
        }
    }

    /** 返回伪造值，或 null 表示应沿用宿主原值。 */
    fun resolve(target: Any?, kind: Kind): Boolean? {
        if (target == null || depth.get() == 0) return null
        val frames = stack.get() ?: return null
        for (index in frames.indices.reversed()) {
            val spec = frames[index]
            if (!spec.matches(target)) continue
            spec.valueOf(kind)?.let { return it }
        }
        return null
    }
}
