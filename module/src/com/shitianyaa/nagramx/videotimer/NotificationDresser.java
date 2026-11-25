package com.shitianyaa.nagramx.videotimer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 让后台播放的视频在通知栏与媒体会话里表现得像音乐。
 */
final class NotificationDresser {

    static final class MusicServiceHandle {
        static volatile Object current = null;
    }

    private final HostProfile host;
    private final VideoBackgroundSession session;
    private final HostStrings strings;
    private final HostBridge.Logger logger;
    private final HostBridge bridge;

    NotificationDresser(
            HostProfile host,
            VideoBackgroundSession session,
            HostStrings strings,
            HostBridge.Logger logger
    ) {
        this.host = host;
        this.session = session;
        this.strings = strings;
        this.logger = logger != null ? logger : (msg, t) -> {};
        this.bridge = host.bridge;
    }

    private String attachVideo() {
        if (host.getAppContext() == null) return null;
        return strings.get(host.getAppContext(), "AttachVideo");
    }

    private Object maskTarget() {
        Object controller = host.mediaController();
        if (controller == null) return null;
        Object playing = bridge.invoke(controller, host.media.getPlayingMessageObject);
        if (playing != null && host.isVideoMessage(playing)) {
            return playing;
        }
        return null;
    }

    <T> T around(MessageIdentityMask.Action<T> body) {
        return MessageIdentityMask.around(MessageIdentityMask.Spec.asMusic(maskTarget()), body);
    }

    void aroundVoid(MessageIdentityMask.VoidAction body) {
        MessageIdentityMask.around(MessageIdentityMask.Spec.asMusic(maskTarget()), body);
    }

    String videoTitleFallback(Object messageObject, String hostResult) {
        String caption = captionFirstLine(host, messageObject);
        if (caption != null && !caption.trim().isEmpty()) {
            return caption;
        }
        return attachVideo();
    }

    private boolean isUnknownTitle(String value) {
        if (host.getAppContext() == null) return false;
        return value.equals(strings.get(host.getAppContext(), "AudioUnknownTitle"));
    }

    String videoAuthorFallback(Object messageObject, boolean unknown) {
        int account = host.getSelectedAccount();
        Object accObj = bridge.getField(messageObject, host.message.currentAccountField);
        if (accObj instanceof Number) {
            account = ((Number) accObj).intValue();
        }

        Object owner = bridge.getField(messageObject, host.message.messageOwnerField);
        Object fwdFrom = bridge.getField(owner, host.tl.fwdFrom);

        if (isFromSelf(messageObject, fwdFrom, account)) {
            if (host.getAppContext() != null) {
                return strings.get(host.getAppContext(), "FromYou");
            }
        }

        Object fwdNameObj = bridge.getField(fwdFrom, host.tl.fwdFromName);
        if (fwdNameObj instanceof String && !((String) fwdNameObj).trim().isEmpty()) {
            return (String) fwdNameObj;
        }

        Object fwdIdObj = bridge.getField(fwdFrom, host.tl.fwdFromId);
        String name = host.peerNameOf(account, fwdIdObj);
        if (name != null) return name;

        Object fromId = bridge.getField(owner, host.tl.fromId);
        name = host.peerNameOf(account, fromId);
        if (name != null) return name;

        if (fromId == null) {
            Object peerId = bridge.getField(owner, host.tl.peerId);
            name = host.peerNameOf(account, peerId);
            if (name != null) return name;
        }

        Object senderIdObj = bridge.invoke(messageObject, host.message.getSenderId);
        long senderId = (senderIdObj instanceof Number) ? ((Number) senderIdObj).longValue() : 0L;
        name = host.peerName(account, senderId);
        if (name != null) return name;

        return unknown ? attachVideo() : null;
    }

    private boolean isFromSelf(Object messageObject, Object fwdFrom, int account) {
        Object isOut = bridge.invoke(messageObject, host.message.isOutOwner);
        if (Boolean.TRUE.equals(isOut)) return true;
        if (fwdFrom == null) return false;
        long self = host.clientUserId(account);
        if (self == 0L) return false;
        Object fwdPeer = bridge.getField(fwdFrom, host.tl.fwdFromId);
        if (fwdPeer == null) return false;
        Object userIdObj = bridge.getField(fwdPeer, "user_id");
        if (userIdObj instanceof Number) {
            return ((Number) userIdObj).longValue() == self;
        }
        return false;
    }

    static String captionFirstLine(HostProfile host, Object messageObject) {
        if (messageObject == null) return null;
        HostBridge bridge = host.bridge;
        Object capObj = bridge.getField(messageObject, host.message.captionField);
        if (capObj instanceof CharSequence) {
            String text = capObj.toString().trim();
            if (!text.isEmpty()) {
                int newline = text.indexOf('\n');
                String firstLine = newline >= 0 ? text.substring(0, newline).trim() : text;
                if (!firstLine.isEmpty()) return firstLine;
            }
        }

        Object owner = bridge.getField(messageObject, host.message.messageOwnerField);
        if (owner == null) return null;
        Object textObj = bridge.getField(owner, host.tl.messageText);
        if (!(textObj instanceof String)) return null;
        String text = (String) textObj;
        if (text.trim().isEmpty()) return null;
        int newline = text.indexOf('\n');
        String firstLine = newline >= 0 ? text.substring(0, newline).trim() : text.trim();
        return firstLine.isEmpty() ? null : firstLine;
    }

    void dressNotification(android.app.Notification notification, Object messageObject) {
        if (notification == null || messageObject == null) return;
        String title = videoTitleFallback(messageObject, null);
        String author = videoAuthorFallback(messageObject, false);
        if (notification.extras != null) {
            if (title != null && !title.isEmpty()) {
                notification.extras.putCharSequence(android.app.Notification.EXTRA_TITLE, title);
            }
            if (author != null && !author.isEmpty()) {
                notification.extras.putCharSequence(android.app.Notification.EXTRA_TEXT, author);
            }
        }
    }

    static void updateMediaMetadata(HostProfile host, Object service, String title, String author) {
        if (service == null || title == null) return;
        try {
            java.lang.reflect.Field sessionField = host.bridge.findField(service.getClass(), "mediaSession");
            if (sessionField == null) return;
            Object mediaSession = host.bridge.getField(service, sessionField);
            if (mediaSession == null) return;
            java.lang.reflect.Method getController = host.bridge.findMethod(mediaSession.getClass(), "getController", 0);
            Object controller = (getController != null) ? host.bridge.invoke(mediaSession, getController) : null;
            Object oldMeta = (controller != null) ? host.bridge.invokeNamed(controller, "getMetadata") : null;

            Class<?> metaBuilderClass = host.bridge.loadClass("android.support.v4.media.MediaMetadataCompat$Builder");
            if (metaBuilderClass == null) {
                metaBuilderClass = host.bridge.loadClass("android.media.MediaMetadata$Builder");
            }
            if (metaBuilderClass == null) return;
            Object builder = (oldMeta != null)
                    ? host.bridge.newInstance(metaBuilderClass, oldMeta)
                    : host.bridge.newInstance(metaBuilderClass);
            if (builder == null) return;

            host.bridge.invokeNamed(builder, "putString", "android.media.metadata.TITLE", title);
            if (author != null) {
                host.bridge.invokeNamed(builder, "putString", "android.media.metadata.ARTIST", author);
                host.bridge.invokeNamed(builder, "putString", "android.media.metadata.ALBUM_ARTIST", author);
            }
            Object newMeta = host.bridge.invokeNamed(builder, "build");
            if (newMeta != null) {
                host.bridge.invokeNamed(mediaSession, "setMetadata", newMeta);
            }
        } catch (Throwable ignored) {
        }
    }

    void refresh(String reason) {
        Object service = MusicServiceHandle.current;
        if (service == null) return;
        Object playing = maskTarget();
        if (playing == null) return;
        try {
            aroundVoid(() -> {
                bridge.invoke(service, host.musicService.createNotification, playing, false);
                Object posObj = bridge.invoke(host.mediaController(), host.media.getProgressMs, playing);
                long positionMs = (posObj instanceof Number) ? ((Number) posObj).longValue() : -1L;
                bridge.invoke(service, host.musicService.updatePlaybackState, positionMs);
            });
        } catch (Throwable t) {
            logger.log("刷新通知失败（" + reason + "）", t);
        }
    }

    String getDressedNotificationGaps() {
        StringBuilder sb = new StringBuilder();
        sb.append("无封面（AudioInfo 为抽象类，无法注入 cover）");
        if (host.media.getProgressMs == null) {
            sb.append("；无精确进度（缺 getProgressMs）");
        }
        return sb.toString();
    }

    static PendingIntent createJumpPendingIntent(HostProfile host, Object messageObject) {
        Context context = host.getAppContext();
        if (context == null || messageObject == null) return null;
        int messageId = host.messageIdOf(messageObject);
        if (messageId == 0) return null;

        Class<?> launchActivityClass = host.bridge.loadClass("org.telegram.ui.LaunchActivity");
        if (launchActivityClass == null) return null;

        Intent intent = createJumpIntent(host, context, launchActivityClass, messageObject);
        if (intent == null) return null;
        return PendingIntent.getActivity(context, messageId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Intent createJumpIntent(HostProfile host, Context context, Class<?> activityClass, Object message) {
        int messageId = host.messageIdOf(message);
        long dialogId = host.dialogIdOf(message);
        if (messageId == 0 || dialogId == 0L) return null;
        Object accountValue = host.bridge.getField(message, host.message.currentAccountField);
        if (!(accountValue instanceof Number)) return null;
        int account = ((Number) accountValue).intValue();
        Intent intent = new Intent(context, activityClass);
        // The host notification branch consumes account and exact message extras.
        // Distinct actions prevent PendingIntent collisions across accounts/chats.
        intent.setAction("com.tmessages.openchat.video." + account + "." + dialogId + "." + messageId);
        intent.putExtra("currentAccount", account);
        intent.putExtra("message_id", messageId);
        intent.putExtra(dialogId > 0L ? "userId" : "chatId", Math.abs(dialogId));
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    static void bindMediaSessionActivity(HostProfile host, Object service, PendingIntent jump) {
        if (host == null || service == null || jump == null) return;
        Object mediaSession = host.bridge.getField(service, "mediaSession");
        if (mediaSession != null) {
            host.bridge.invokeNamed(mediaSession, "setSessionActivity", jump);
        }
    }
}
