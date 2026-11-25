package com.shitianyaa.nagramx.videotimer;

/**
 * NiagramX 被 R8 改名后的成员名表（按 versionCode 门控）。
 *
 * <p>证据链：</p>
 * <ul>
 *   <li>源码 {@code D:\Project\TG\NiagramX} HEAD {@code bc99e5e9}，
 *       {@code TMessagesProj/build.gradle} 中 {@code def verCode = 1276}，
 *       versionName 由 {@code APP_VERSION_NAME + "-" + commitId[0..7]} 拼成
 *       {@code 13.0.3-bc99e5e}，与设备安装包一致。</li>
 *   <li>APK 指纹 {@code ae798d9f4fcce17721a39ad0cec80e46cc5509c1876a759c541ae2d1ba0e7dbc}。</li>
 *   <li>混淆规则 {@code TMessagesProj/proguard-rules.pro}：
 *       <pre>
 * -keep class org.telegram.messenger.* { *; }        // messenger 包成员名全保留
 * -keepclassmembernames,allowshrinking class org.telegram.ui.* { &lt;fields&gt;; }
 * -keepclassmembernames,allowshrinking class org.telegram.ui.Components.* { &lt;fields&gt;; }
 * -repackageclasses ''                               // 未 keep 的类搬到默认包
 *       </pre>
 *       因此 ui 包字段名可读、方法名被改，Components/Cells 下未 keep 的类变成短名
 *       （{@code VideoPlayer} → {@code t6b}）。</li>
 *   <li>每个条目都用 ddc 反编译方法体与源码逐行对照确认，理由见各行注释。</li>
 * </ul>
 *
 * <p>名字表只覆盖已实测版本；未匹配的版本仍走原名字查找，并回退到形状指纹。</p>
 */
final class NiagramXNames {

    /** 已验证的 NiagramX 版本（BuildConfig.VERSION_CODE）。 */
    static final int VERSION_CODE_13_0_3 = 1276;

    static boolean matches(int versionCode) {
        return versionCode == VERSION_CODE_13_0_3;
    }

    /** org.telegram.ui.PhotoViewer。 */
    static final class PhotoViewer {
        /** h7: synchronized(PhotoViewer.class) { Instance == null ? new PhotoViewer() : Instance } */
        static final String getInstance = "h7";
        /** ob(Activity, BaseFragment, ResourcesProvider)V：activityVisibilityController.destroy() + LaunchActivity。 */
        static final String setParentActivity = "ob";
        /** k7()Activity：该签名在 PhotoViewer 内唯一。 */
        static final String getParentActivity = "k7";
        /** o6(ZZ)V：1323 行，含 currentEditMode / stickerMakerView / photoCropView / containerView / doneButtonPressed。 */
        static final String closePhoto = "o6";
        /** Ia()V：videoPlayer.U() / photoViewerWebView.K() 二分支（U = play）。 */
        static final String playVideoOrWeb = "Ia";
        /** Fa()V：videoPlayer.T() / photoViewerWebView.J() 二分支（T = pause）。 */
        static final String pauseVideoOrWeb = "Fa";
        /** u7()Lt6b;：该签名在 PhotoViewer 内唯一。 */
        static final String getVideoPlayer = "u7";
        /** F7()V：MediaController.getInstance().injectVideoPlayer(videoPlayer, currentMessageObject)。 */
        static final String injectVideoPlayerToMediaController = "F7";
        /** Ba(MessageObject, ChatActivity, J, J, J, Provider)V：转发到 16 参 Da。 */
        static final String openPhotoMessage = "Ba";
        /** za(ArrayList, I, J, J, J, Provider)V。 */
        static final String openPhotoList = "za";
        /** eb(I)V：this.fb(p1, true, false, false)（fb 为 setImageIndex 四参版）。 */
        static final String setImageIndex = "eb";
        /** ja(Z)V：currentBotInlineResult + canStreamVideo + alt_documents 分支。 */
        static final String onActionClick = "ja";
        /** Ha(Z)V：遍历 editState.mediaEntities 后 start/stop RLottie。 */
        static final String playOrStopAnimatedStickers = "Ha";
        /** Ua(J)V：遍历 editState.mediaEntities 后 setProgressMs。 */
        static final String seekAnimatedStickersTo = "Ua";
        /**
         * mc(IZ)V：ads.setWaitingPaused(...) + shouldSavePositionForCurrentVideo 分支。
         * 源码签名是 (boolean, int)，R8 把原始类型参数重排成 (int, boolean)。
         */
        static final String updatePlayerState = "mc";

    }

    /** org.telegram.ui.Components.VideoPlayer（混淆后为默认包短名）。 */
    static final class VideoPlayer {
        /** g0(u6b)V：唯一「单参且参数为接口」的方法；字段 delegate 的类型正是 u6b。 */
        static final String setDelegate = "g0";
        /** s0(TextureView)V：参数类型唯一。 */
        static final String setTextureView = "s0";
        /** r0(SurfaceView)V：参数类型唯一。 */
        static final String setSurfaceView = "r0";
        /** p0(I)V：player.setAudioAttributes(new AudioAttributes(...), handleAudioFocus)。 */
        static final String setStreamType = "p0";
        /** i0(Z)V：this.looping = p1; player.setRepeatMode(...)。 */
        static final String setLooping = "i0";
        /** J()Z：return this.looping。 */
        static final String isLooping = "J";
        /** L()Z：mixedAudio && mixedPlayWhenReady || player.getPlayWhenReady()。 */
        static final String isPlaying = "L";
        /** x()J：fallbackDuration == TIME_UNSET ? player.getDuration() : fallbackDuration。 */
        static final String getDuration = "x";
        /** t()J：fallbackPosition == TIME_UNSET ? player.getCurrentPosition() : fallbackPosition。 */
        static final String getCurrentPosition = "t";
        /** U()V：mixedPlayWhenReady = true。 */
        static final String play = "U";
        /** T()V：mixedPlayWhenReady = false + audioVisualizerDelegate.onVisualizerUpdate。 */
        static final String pause = "T";
        /** c0(J)V：this.d0(p1, false)。 */
        static final String seekTo = "c0";

    }

    /** org.telegram.ui.Components.FragmentContextView。 */
    static final class FragmentContextView {
        /** Y(Z)V：MediaController.getPlayingMessageObject + VoIPService.getSharedInstance + GroupCallPip。 */
        static final String checkPlayer = "Y";
        /** A0(Z)V：speedIcon.setValue(MediaController.getPlaybackSpeed(isMusic), animated)。 */
        static final String updatePlaybackButton = "A0";

    }

    static final class ChatActivity {
        static final String updateTextureViewPosition = "er";
        static final String updateMessagesVisiblePart = "Lq";
    }

    private NiagramXNames() {}
}
