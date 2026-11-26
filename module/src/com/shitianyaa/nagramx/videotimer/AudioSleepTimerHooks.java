package com.shitianyaa.nagramx.videotimer;

import com.shitianyaa.nagramx.videotimer.core.Xp;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/** 音频宿主适配：原生菜单、播放生命周期及结束回调。 */
final class AudioSleepTimerHooks implements AudioSleepTimerAgent.Playback {
    private final HostProfile host;
    private final HostBridge bridge;
    private final HostBridge.Logger logger;
    private final MediaControllerAgent media;
    private final AudioSleepTimerAgent agent;
    private final AudioTimerMenu menu;
    private final Class<?> audioAttribute;
    private final Field delegateField;
    private final Set<Method> hookedDelegates = new HashSet<>();
    private int playDepth;
    private int stateDepth;
    private boolean installed;
    private volatile boolean disposed;

    AudioSleepTimerHooks(HostProfile host, MediaControllerAgent media, HostTheme theme,
                        HostStrings strings, HostBridge.Logger logger) {
        this.host = host;
        bridge = host.bridge;
        this.media = media;
        this.logger = logger;
        audioAttribute = bridge.loadClass("org.telegram.tgnet.TLRPC$TL_documentAttributeAudio");
        delegateField = findDelegateField();
        agent = new AudioSleepTimerAgent(this, logger);
        menu = new AudioTimerMenu(host, theme, strings, logger, agent, this::prepareCurrent);
    }

    void install() {
        if (installed || disposed) return;
        if (host.media.audioPlayerField == null || host.media.playMessage == null
                || host.media.pauseMessage == null || host.message.isMusic == null
                || host.message.getDocument == null || audioAttribute == null
                || delegateField == null || host.playerUi.alertConstructor == null) {
            logger.log("音频定时入口不可用：缺少音频播放器、类型校验、delegate 或原生播放器菜单", null);
            return;
        }
        if (!menu.install()) {
            logger.log("音频定时入口不可用：更多菜单 Hook 安装失败", null);
            return;
        }
        installed = true;
        Xp.hook(host.media.playMessage, chain -> {
            playDepth++;
            try {
                Object result = chain.proceed();
                if (Boolean.TRUE.equals(result) && playDepth == 1) {
                    prepareCurrent();
                    agent.played(true);
                }
                return result;
            } finally {
                playDepth--;
            }
        });
        Xp.hook(host.media.pauseMessage, chain -> {
            Object result = chain.proceed();
            if (Boolean.TRUE.equals(result) || host.media.pauseMessage.getReturnType() == void.class) {
                agent.observe();
            }
            return result;
        });
        if (host.media.cleanupPlayer4 != null) Xp.hook(host.media.cleanupPlayer4, chain -> {
            Object result = chain.proceed();
            if (playDepth == 0 && stateDepth == 0 && media.playingMessage() == null) agent.cancel();
            return result;
        });
        if (host.player.setDelegate != null) Xp.hook(host.player.setDelegate, chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() == audioPlayer()) attachDelegate(chain.getArg(0));
            return result;
        });
        // 前台视频可能由 PhotoViewer 独立播放，不经过 MediaController.playMessage。
        if (host.photo.updatePlayerState != null) Xp.hook(host.photo.updatePlayerState, chain -> {
            Object result = chain.proceed();
            Object viewer = chain.getThisObject();
            if (host.isPhotoVisible(viewer) && host.isVideoMessage(host.messageOf(viewer))
                    && Boolean.TRUE.equals(chain.getArg(0))
                    && Integer.valueOf(MediaControllerAgent.EXO_STATE_READY).equals(chain.getArg(1))) {
                agent.cancel();
            }
            return result;
        });
        Xp.hook(host.playerUi.alertConstructor, io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE,
                chain -> {
                    Object result = chain.proceed();
                    menu.attach(chain.getThisObject());
                    return result;
                });
        Method show = bridge.findMethod(host.playerUi.alertClass, "show", 0);
        if (show != null) Xp.hook(show, chain -> {
            if (host.playerUi.alertClass.isInstance(chain.getThisObject())) menu.attach(chain.getThisObject());
            return chain.proceed();
        });
        logger.log("音频定时 Hook 已安装（排除聊天语音）", null);
    }

    private Object audioPlayer() {
        return bridge.getField(media.controller(), host.media.audioPlayerField);
    }

    private Field findDelegateField() {
        if (host.player.setDelegate == null) return null;
        Class<?> type = host.player.setDelegate.getParameterTypes()[0];
        Field named = bridge.findField(host.videoPlayerClass, "delegate");
        if (named != null && named.getType() == type) return named;
        Field found = null;
        for (Field field : host.videoPlayerClass.getDeclaredFields()) {
            if (field.getType() != type || Modifier.isStatic(field.getModifiers())) continue;
            if (found != null) return null;
            found = field;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    private boolean attachDelegate(Object delegate) {
        if (disposed || delegate == null) return false;
        Method callback = bridge.findMethod(delegate.getClass(), "onStateChanged", boolean.class, int.class);
        if (callback == null || callback.getReturnType() != void.class || Modifier.isStatic(callback.getModifiers())) return false;
        if (hookedDelegates.contains(callback)) return true;
        try {
            io.github.libxposed.api.XposedInterface.HookHandle handle = Xp.hook(callback, chain -> {
                Object player = audioPlayer();
                if (disposed || player == null || bridge.getField(player, delegateField) != chain.getThisObject()) {
                    return chain.proceed();
                }
                boolean playing = Boolean.TRUE.equals(chain.getArg(0));
                int state = ((Number) chain.getArg(1)).intValue();
                if (agent.beforeState(player, playing, state)) return null;
                stateDepth++;
                try {
                    Object result = chain.proceed();
                    // playMessage 内释放旧播放器会同步通知空消息，交给外层成功播放后结算。
                    if (playDepth == 0) agent.afterState(player, playing, state);
                    return result;
                } finally {
                    stateDepth--;
                }
            });
            if (handle == null) {
                logger.log("音频定时入口不可用：结束回调 Hook 未安装", null);
                return false;
            }
            hookedDelegates.add(callback);
            return true;
        } catch (RuntimeException failure) {
            hookedDelegates.remove(callback);
            logger.log("音频结束回调安装失败", failure);
            return false;
        }
    }

    private boolean prepareCurrent() {
        AudioSleepTimerAgent.State state = read();
        boolean ready = installed && state != null && state.track != null && state.player != null
                && attachDelegate(bridge.getField(state.player, delegateField));
        return ready;
    }

    @Override public AudioSleepTimerAgent.State read() {
        Object message = media.playingMessage();
        if (message == null) return null;
        AudioSleepTimerAgent.Track track = musicTrack(message);
        Object player = audioPlayer();
        boolean playing = !media.isPaused() && player != null
                && Boolean.TRUE.equals(bridge.invoke(player, host.player.isPlaying))
                && host.playbackStateOf(player) == MediaControllerAgent.EXO_STATE_READY;
        boolean downloading = Boolean.TRUE.equals(bridge.getField(media.controller(), host.media.downloadingCurrentMessageField));
        return new AudioSleepTimerAgent.State(track, player, playing, downloading);
    }

    AudioSleepTimerAgent.Track musicTrack(Object message) {
        if (message == null || !Boolean.TRUE.equals(bridge.invoke(message, host.message.isMusic))
                || host.isVideoMessage(message)
                || Boolean.TRUE.equals(bridge.invoke(message, host.message.isRoundVideo))) return null;
        Object document = bridge.invoke(message, host.message.getDocument);
        Object attributes = bridge.getField(document, "attributes");
        if (!(attributes instanceof Iterable)) return null;
        boolean audio = false;
        for (Object attribute : (Iterable<?>) attributes) {
            if (audioAttribute != null && audioAttribute.isInstance(attribute)) {
                audio = true;
                Object voice = bridge.getField(attribute, "voice");
                if (!(voice instanceof Boolean) || (Boolean) voice) return null;
            }
        }
        if (!audio) return null;
        Object account = bridge.getField(message, host.message.currentAccountField);
        long dialog = host.dialogIdOf(message);
        int id = host.messageIdOf(message);
        return account instanceof Number && dialog != 0 && id != 0
                ? new AudioSleepTimerAgent.Track(((Number) account).intValue(), dialog, id) : null;
    }

    @Override public void pause(AudioSleepTimerAgent.State expected) {
        AudioSleepTimerAgent.State current = read();
        if (disposed || current == null || current.player != expected.player
                || !expected.track.equals(current.track)) return;
        media.pausePlayback();
        logger.log("音频定时到期，已请求宿主暂停", null);
        menu.finished();
    }

    @Override public void changed() { menu.refresh(); }

    void dispose() {
        disposed = true;
        agent.dispose();
        menu.dispose();
        hookedDelegates.clear();
    }
}
