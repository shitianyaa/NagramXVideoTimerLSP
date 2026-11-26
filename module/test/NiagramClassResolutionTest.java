package com.shitianyaa.nagramx.videotimer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Regression coverage for R8 class identity changes and APK cache isolation. */
final class NiagramClassResolutionTest {
    private NiagramClassResolutionTest() {}

    static void run() {
        HostBridge bridge = new HostBridge(NiagramClassResolutionTest.class.getClassLoader(), (message, error) -> {});
        testTypeIdentity(bridge);
        testClassCandidates();
        try {
            testFingerprintCache();
        } catch (Exception e) {
            throw new AssertionError("Niagram 类缓存回归失败", e);
        }
    }

    private static void testTypeIdentity(HostBridge bridge) {
        TestMain.check("宿主识别接受任意 R8 类型名及改名的单例方法",
                HostProfile.hasObfuscatedPlayerTypes(bridge, ChangedViewer.class, ChangedController.class));
        TestMain.check("宿主识别拒绝基本类型播放器",
                !HostProfile.hasObfuscatedPlayerTypes(bridge, InvalidViewer.class, ChangedController.class));
        TestMain.check("宿主识别拒绝缺少真实单例的控制器",
                !HostProfile.hasObfuscatedPlayerTypes(bridge, ChangedViewer.class, WrongController.class));
        Map<String, String> names = new HashMap<>();
        names.put("org.telegram.messenger.MessageObject", ChangedMessage.class.getName());
        bridge.bindClasses(names);
        names.clear();
        TestMain.check("宿主类型映射持有独立快照",
                bridge.loadClass("org.telegram.messenger.MessageObject") == ChangedMessage.class);

        PhotoViewerMembers photo = new PhotoViewerMembers(bridge, ChangedViewer.class, null, true);
        MediaControllerMembers media = new MediaControllerMembers(bridge, ChangedController.class, true);
        MusicServiceMembers service = new MusicServiceMembers(bridge, ChangedService.class, true);
        TestMain.check("打开画面只匹配字段实际指向的消息类型",
                photo.openPhoto != null && photo.openPhoto.getParameterTypes()[0] == ChangedMessage.class);
        TestMain.check("媒体状态只匹配字段实际指向的消息类型",
                media.updateVideoState != null && media.updateVideoState.getParameterTypes()[0] == ChangedMessage.class);
        TestMain.check("通知只匹配字段实际指向的消息类型",
                service.createNotification != null && service.createNotification.getParameterTypes()[0] == ChangedMessage.class);
        TestMain.check("媒体单例按返回类型定位",
                media.getInstance != null && "renamedSingleton".equals(media.getInstance.getName()));
        NiagramDexClasses.Spec spec = new NiagramDexClasses.Spec("message", "java.lang.Object",
                new String[]{"messageOwner", "currentAccount"});
        TestMain.check("结构校验接受需要的字段", spec.accepts(ChangedMessage.class));
        TestMain.check("结构校验拒绝同名旧短名指向的无关类", !spec.accepts(WrongMessage.class));
    }

    private static void testClassCandidates() {
        List<String> logs = new ArrayList<>();
        HostBridge.Logger logger = (message, error) -> logs.add(message);
        Map<String, String> found = NiagramDexClasses.search(
                spec -> Collections.singletonList("changed." + spec.name), logger);
        TestMain.check("每个结构唯一命中时得到完整类型映射", found.size() == NiagramDexClasses.SPECS.size());
        Map<String, String> missing = NiagramDexClasses.search(spec -> Collections.emptyList(), logger);
        TestMain.check("类型缺失时停止使用不完整映射", missing.isEmpty());
        Map<String, String> ambiguous = NiagramDexClasses.search(
                spec -> java.util.Arrays.asList("first", "second"), logger);
        TestMain.check("多个结构候选时拒绝猜测", ambiguous.isEmpty());
        TestMain.check("类型缺失和歧义输出具体诊断", logs.size() == NiagramDexClasses.SPECS.size() * 2
                && logs.get(0).contains("候选数=0")
                && logs.get(NiagramDexClasses.SPECS.size()).contains("候选数=2"));
        String optional = "org.telegram.ui.Components.NumberPicker";
        Map<String, String> partial = NiagramDexClasses.search(spec -> optional.equals(spec.name)
                ? Collections.emptyList() : Collections.singletonList("changed." + spec.name), logger);
        TestMain.check("单个可选控件缺失仍解析其余功能的类型",
                partial.size() == NiagramDexClasses.SPECS.size() - 1 && !partial.containsKey(optional));
    }

    private static void testFingerprintCache() throws Exception {
        File output = new File(System.getProperty("test.output", "module/build"));
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("测试输出目录不可用");
        File directory = Files.createTempDirectory(output.toPath(), "niagram-cache-test-").toFile();
        File apk = new File(directory, "host-1322.apk");
        File split = new File(directory, "split.apk");
        File cache = new File(directory, "bindings.properties");
        try {
            Files.write(apk.toPath(), "old-content".getBytes(StandardCharsets.UTF_8));
            Files.write(split.toPath(), new byte[]{1});
            String first = NiagramDexClasses.fingerprint(Collections.singletonList(apk));
            Map<String, String> found = NiagramDexClasses.search(
                    spec -> Collections.singletonList("changed." + spec.name), (message, error) -> {});
            NiagramDexClasses.writeCache(cache, first, found);
            TestMain.check("相同 APK 指纹复用完整缓存", found.equals(NiagramDexClasses.readCache(cache, first)));
            Map<String, String> partial = new HashMap<>(found);
            partial.remove("org.telegram.ui.Components.NumberPicker");
            NiagramDexClasses.writeCache(cache, first, partial);
            TestMain.check("缓存保留明确缺失的可选类型且不填入旧短名",
                    partial.equals(NiagramDexClasses.readCache(cache, first)));
            long timestamp = apk.lastModified();
            Files.write(apk.toPath(), "new-content".getBytes(StandardCharsets.UTF_8));
            Files.setLastModifiedTime(apk.toPath(), java.nio.file.attribute.FileTime.fromMillis(timestamp));
            String second = NiagramDexClasses.fingerprint(Collections.singletonList(apk));
            TestMain.check("版本号长度时间均相同时仍检测 APK 内容变化", !first.equals(second));
            TestMain.check("另一构建拒绝复用旧短名缓存", NiagramDexClasses.readCache(cache, second).isEmpty());
            String withSplit = NiagramDexClasses.fingerprint(java.util.Arrays.asList(apk, split));
            Files.write(split.toPath(), new byte[]{2});
            TestMain.check("拆分 APK 内容变化使缓存失效", !withSplit.equals(
                    NiagramDexClasses.fingerprint(java.util.Arrays.asList(apk, split))));
            Properties stale = new Properties();
            stale.setProperty("rules", "old-rules");
            stale.setProperty("apk", second);
            try (FileOutputStream stream = new FileOutputStream(cache)) {
                stale.store(stream, "stale");
            }
            TestMain.check("解析规则变化拒绝旧缓存", NiagramDexClasses.readCache(cache, second).isEmpty());
            Files.write(cache.toPath(), "broken=\\uZZZZ".getBytes(StandardCharsets.UTF_8));
            TestMain.check("损坏的缓存触发重新解析", NiagramDexClasses.readCache(cache, second).isEmpty());
        } finally {
            Files.deleteIfExists(apk.toPath());
            Files.deleteIfExists(split.toPath());
            Files.deleteIfExists(cache.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    static final class ChangedMessage {
        Object messageOwner;
        int currentAccount;
    }

    static final class WrongMessage {}

    static final class ChangedPlayer {
        int lastReportedPlaybackState;
        boolean lastReportedPlayWhenReady;
    }

    static final class ChangedViewer {
        ChangedPlayer videoPlayer;
        ChangedMessage currentMessageObject;
        boolean Sa(ChangedMessage message, long dialog, long topic, long merged, Object provider, boolean flag) { return true; }
        boolean Sa(WrongMessage message, long dialog, long topic, long merged, Object provider, boolean flag) { return false; }
    }

    static final class InvalidViewer {
        int videoPlayer;
        ChangedMessage currentMessageObject;
    }

    static final class ChangedController {
        static ChangedController renamedSingleton() { return null; }
        void L0(ChangedMessage message, int[] sizes, boolean ready, boolean force, int state) {}
        void L0(WrongMessage message, int[] sizes, boolean ready, boolean force, int state) {}
    }

    static final class WrongController {
        static String getInstance() { return "unrelated"; }
    }

    static final class ChangedService {
        void a(ChangedMessage message, boolean paused) {}
        void a(WrongMessage message, boolean paused) {}
    }
}
