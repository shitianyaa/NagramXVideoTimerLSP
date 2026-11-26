package com.shitianyaa.nagramx.videotimer;

import com.shitianyaa.nagramx.videotimer.core.Xp;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 执行生产音频 Hook，模拟原宿主的播放、清理及自动续播顺序。 */
final class AudioTimerHostTest {
    private final Map<Executable, XposedInterface.Hooker> hooks = new HashMap<>();
    private final Controller controller = new Controller(this);
    private final Viewer viewer = new Viewer();
    private final HostProfile host;
    private final AudioSleepTimerHooks adapter;
    private final AudioSleepTimerAgent agent;

    private AudioTimerHostTest() throws Exception {
        Controller.instance = controller;
        HostBridge bridge = new HostBridge(getClass().getClassLoader(), (message, error) -> {});
        Map<String, String> names = new HashMap<>();
        names.put("org.telegram.messenger.MessageObject", Message.class.getName());
        names.put("org.telegram.tgnet.TLRPC$TL_documentAttributeAudio", Attribute.class.getName());
        bridge.bindClasses(names);
        Constructor<?> constructor = HostProfile.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        host = (HostProfile) constructor.newInstance(bridge, 1260, false, "test",
                Viewer.class, Controller.class, Message.class, Player.class,
                null, null, null, new PhotoViewerMembers(bridge, Viewer.class, null),
                new MediaControllerMembers(bridge, Controller.class),
                new VideoPlayerMembers(bridge, Player.class, null), new MessageObjectMembers(bridge, Message.class),
                new MusicServiceMembers(bridge, null), new PlayerUiMembers(bridge, null, Alert.class, null));
        // 原宿主按包级 MessageObject 名称匹配，本夹具使用嵌套类型，显式绑定相同签名。
        for (String name : new String[]{"playMessage", "pauseMessage"}) {
            Field member = MediaControllerMembers.class.getDeclaredField(name);
            member.setAccessible(true);
            Method method = name.equals("playMessage")
                    ? Controller.class.getDeclaredMethod(name, Message.class, boolean.class)
                    : Controller.class.getDeclaredMethod(name, Message.class);
            method.setAccessible(true);
            member.set(host.media, method);
        }
        XposedModule module = new XposedModule() {};
        module.attachFramework((XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    Executable executable = (Executable) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, operation, values) -> {
                                if (operation.getName().equals("intercept")) {
                                    hooks.put(executable, (XposedInterface.Hooker) values[0]);
                                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                                            new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, parameters) -> null);
                                }
                                return builder;
                            });
                }), () -> {});
        Xp.bind(module);
        VideoBackgroundSession video = new VideoBackgroundSession((message, error) -> {});
        adapter = new AudioSleepTimerHooks(host, new MediaControllerAgent(host, video, (message, error) -> {}),
                new HostTheme(bridge, (message, error) -> {}), new HostStrings(bridge, (message, error) -> {}),
                (message, error) -> {});
        Field field = AudioSleepTimerHooks.class.getDeclaredField("agent");
        field.setAccessible(true);
        agent = (AudioSleepTimerAgent) field.get(adapter);
        adapter.install();
    }

    static void run() {
        try {
            new AudioTimerHostTest().exercise();
        } catch (Throwable failure) {
            throw new AssertionError("音频宿主接入回归失败", failure);
        }
    }

    private void exercise() throws Throwable {
        minuteRange();
        TestMain.check("更多菜单构造通过类型和字段解析，不依赖混淆短名",
                AudioTimerMenu.findOptionsBuilder(host).getName().equals("buildSaveOptions"));
        Message first = new Message();
        TestMain.check("普通音频文件通过类型及属性校验", adapter.musicTrack(first) != null);
        first.document.attributes.clear();
        TestMain.check("缺少音频属性时不提供入口", adapter.musicTrack(first) == null);
        first.document.attributes.add(new Attribute(false));
        first.document.attributes.add(new Attribute(true));
        TestMain.check("即使宿主误判音乐仍严格排除聊天语音", adapter.musicTrack(first) == null);
        first.document.ttlSeconds = Integer.MAX_VALUE;
        TestMain.check("一次性语音同样由 voice 属性排除", adapter.musicTrack(first) == null);
        first.document.attributes.clear();
        first.document.attributes.add(new Attribute(false));
        first.video = true;
        TestMain.check("音乐身份伪装不能纳入普通视频", adapter.musicTrack(first) == null);
        first.video = false;
        first.round = true;
        TestMain.check("圆形视频排除音频定时", adapter.musicTrack(first) == null);
        first.round = false;

        play(first);
        TestMain.check("播放成功后安装实际音频 delegate 的 Hook", hooks.containsKey(Delegate.class.getDeclaredMethod("onStateChanged", boolean.class, int.class)));
        agent.arm(10);
        Delegate delegate = (Delegate) controller.audioPlayer.delegate;
        event(delegate, true, 4);
        event(delegate, false, 4);
        TestMain.check("音频自然结束透传原生播放一次及循环逻辑", delegate.originalCalls == 2 && controller.pauses == 0);
        TestMain.check("曲目结束保留按分钟定时", agent.isArmed());
        play(first);
        event(controller.audioPlayer.delegate, false, 3);
        TestMain.check("用户重新播放后 delegate 恢复透传", ((Delegate) controller.audioPlayer.delegate).originalCalls == 1);

        agent.arm(10);
        Message second = new Message();
        second.id = 2;
        play(second);
        TestMain.check("原生换曲 cleanup(false) 不清除按分钟音频定时", agent.isArmed());
        play(first);
        TestMain.check("生产播放 Hook 成功手动切歌保留按分钟定时", agent.isArmed() && controller.pauses == 0);
        agent.arm(10);
        second.reject = true;
        play(second);
        TestMain.check("生产播放 Hook 保留切歌失败的定时", agent.isArmed());

        Delegate oldDelegate = (Delegate) controller.audioPlayer.delegate;
        controller.audioPlayer = new Player();
        event(oldDelegate, true, 4);
        TestMain.check("旧 delegate 结束事件透传且不会暂停新播放器", agent.isArmed() && controller.pauses == 0);
        agent.cancel();

        play(first);
        agent.arm(10);
        delegate = (Delegate) controller.audioPlayer.delegate;
        delegate.exhausted = true;
        event(delegate, true, 4);
        TestMain.check("自然歌单耗尽保留消息但释放播放器时取消定时", !agent.isArmed() && controller.playingMessageObject != null);

        play(first);
        agent.arm(10);
        Message voice = new Message();
        voice.document.attributes.add(new Attribute(true));
        play(voice);
        TestMain.check("生产播放 Hook 切到聊天语音取消音频定时", !agent.isArmed());

        play(first);
        agent.arm(10);
        viewer.currentMessageObject = new Message();
        viewer.currentMessageObject.video = true;
        viewer.isVisible = true;
        call(viewer, "updatePlayerState", new Class<?>[]{boolean.class, int.class}, true, 3);
        TestMain.check("前台视频首次 READY 用事件参数取消音频定时", !agent.isArmed());

        play(first);
        agent.arm(10);
        call(controller, "cleanupPlayer", new Class<?>[]{boolean.class, boolean.class, boolean.class, boolean.class}, true, true, false, false);
        TestMain.check("用户关闭播放会话清除音频定时", !agent.isArmed());
        play(first);
        agent.arm(10);
        adapter.dispose();
        event(controller.audioPlayer.delegate, true, 4);
        TestMain.check("重载后旧音频 Hook 透传且不会再暂停", !agent.isArmed() && controller.pauses == 0);
    }

    private void minuteRange() {
        Picker hours = new Picker();
        Picker minutes = new Picker();
        VideoSleepTimerSheet.syncMinuteRange(host.bridge, hours, minutes);
        TestMain.check("零小时分钟从一开始且自动修正零分钟", minutes.minimum == 1 && minutes.value == 1 && minutes.count == 59);
        hours.value = 1;
        VideoSleepTimerSheet.syncMinuteRange(host.bridge, hours, minutes);
        TestMain.check("非零小时恢复零到五十九分钟", minutes.minimum == 0 && minutes.count == 60 && minutes.value == 1);
        minutes.value = 0;
        VideoSleepTimerSheet.syncMinuteRange(host.bridge, hours, minutes);
        TestMain.check("整小时允许零分钟", minutes.value == 0);
        hours.value = 0;
        VideoSleepTimerSheet.syncMinuteRange(host.bridge, hours, minutes);
        TestMain.check("整小时切回零小时自动改为一分钟", minutes.value == 1 && minutes.minimum == 1);
        minutes.value = 59;
        VideoSleepTimerSheet.syncMinuteRange(host.bridge, hours, minutes);
        TestMain.check("分钟范围联动保留有效选择", minutes.value == 59);
    }

    static final class Picker {
        int value, minimum, count;
        int getValue() { return value; }
        void setValue(int value) { this.value = value; }
        void setMinValue(int minimum) { this.minimum = minimum; }
        void setAllItemsCount(int count) { this.count = count; }
    }

    private void play(Message message) throws Throwable {
        call(controller, "playMessage", new Class<?>[]{Message.class, boolean.class}, message, false);
    }

    private void event(DelegateInterface delegate, boolean playing, int state) throws Throwable {
        call(delegate, "onStateChanged", new Class<?>[]{boolean.class, int.class}, playing, state);
    }

    private Object call(Object target, String name, Class<?>[] types, Object... arguments) throws Throwable {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        XposedInterface.Hooker hook = hooks.get(method);
        if (hook == null) return invoke(method, target, arguments);
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, operation, values) -> {
                    switch (operation.getName()) {
                        case "getThisObject": return target;
                        case "getExecutable": return method;
                        case "getArg": return arguments[(Integer) values[0]];
                        case "getArgs": return Arrays.asList(arguments);
                        case "proceed": return invoke(method, target, arguments);
                        default: throw new UnsupportedOperationException(operation.getName());
                    }
                });
        return hook.intercept(chain);
    }

    private Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    static final class Attribute {
        final boolean voice;
        Attribute(boolean voice) { this.voice = voice; }
    }
    static final class Document {
        final List<Attribute> attributes = new ArrayList<>();
        int ttlSeconds;
        Document() { attributes.add(new Attribute(false)); }
    }
    static final class Message {
        int id = 1, currentAccount;
        long dialogId = 10;
        float audioProgress;
        boolean video, round, reject;
        final Document document = new Document();
        boolean isMusic() { return true; }
        boolean isVideo() { return video; }
        boolean isRoundVideo() { return round; }
        int getId() { return id; }
        long getDialogId() { return dialogId; }
        Document getDocument() { return document; }
    }
    interface DelegateInterface { void onStateChanged(boolean playing, int state); }
    final class Delegate implements DelegateInterface {
        int originalCalls;
        boolean exhausted;
        @Override public void onStateChanged(boolean playing, int state) {
            originalCalls++;
            if (exhausted) controller.audioPlayer = null;
        }
    }
    final class Player {
        DelegateInterface delegate = new Delegate();
        boolean isPlaying() { return true; }
        int getPlaybackState() { return 3; }
        void setDelegate(DelegateInterface delegate) { this.delegate = delegate; }
    }
    static final class Controller {
        static Controller instance;
        final AudioTimerHostTest test;
        Player audioPlayer;
        Message playingMessageObject;
        boolean isPaused;
        int pauses;
        Controller(AudioTimerHostTest test) { this.test = test; }
        static Controller getInstance() { return instance; }
        boolean playMessage(Message message, boolean silent) throws Throwable {
            if (message.reject) return false;
            test.call(this, "cleanupPlayer", new Class<?>[]{boolean.class, boolean.class, boolean.class, boolean.class}, false, false, false, false);
            playingMessageObject = message;
            audioPlayer = test.new Player();
            isPaused = false;
            return true;
        }
        void cleanupPlayer(boolean notify, boolean stopService, boolean voiceEnd, boolean transfer) throws Throwable {
            playingMessageObject = null;
            if (audioPlayer != null) test.event(audioPlayer.delegate, false, 1);
            audioPlayer = null;
        }
        void pauseMessage(Message message) {
            pauses++;
            isPaused = true;
        }
    }
    static final class Viewer {
        Message currentMessageObject;
        boolean isVisible;
        void updatePlayerState(boolean playing, int state) {}
    }
    static final class Alert {
        Alert(android.content.Context context, Object provider) {}
        Options buildSaveOptions(Message message, Options options) { return options; }
        Object unrelated(Message message, Object options) { return options; }
    }
    static final class Options {
        Object scrimView;
        Object actionBarPopupWindow;
    }
}
