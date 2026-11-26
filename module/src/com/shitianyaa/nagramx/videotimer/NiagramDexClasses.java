package com.shitianyaa.nagramx.videotimer;

import android.content.pm.ApplicationInfo;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Resolves class identities independently of R8's short-name allocation. */
final class NiagramDexClasses {
    private static final String RULE_VERSION = "3";
    private static final int BUFFER_SIZE = 64 * 1024;
    static final String PLATFORM_MEDIA_CALLBACK =
            "android.support.v4.media.session.MediaSessionCompat$Callback$MediaSessionCallbackApi21";

    static final class Spec {
        final String name;
        final String parent;
        final String[] fields;
        final String[] strings;
        final String[] methods;

        Spec(String name, String parent, String[] fields, String... strings) {
            this(name, parent, fields, strings, new String[0]);
        }

        private Spec(String name, String parent, String[] fields, String[] strings, String[] methods) {
            this.name = name;
            this.parent = parent;
            this.fields = fields.clone();
            this.strings = strings.clone();
            this.methods = methods.clone();
        }

        static Spec forMethods(String name, String parent, String... methods) {
            return new Spec(name, parent, new String[0], new String[0], methods);
        }

        boolean accepts(Class<?> type) {
            if (type == null || type.isInterface()) return false;
            if (parent != null && (type.getSuperclass() == null
                    || !parent.equals(type.getSuperclass().getName()))) return false;
            try {
                for (String field : fields) type.getDeclaredField(field);
                for (String method : methods) {
                    if (type.getDeclaredMethod(method).getReturnType() != void.class) return false;
                }
                return true;
            } catch (NoSuchFieldException | NoSuchMethodException e) {
                return false;
            }
        }
    }

    static final List<Spec> SPECS = Collections.unmodifiableList(Arrays.asList(
            new Spec("org.telegram.messenger.FileLoader", null, new String[0],
                    "fileUploadQueue", "sent_.*_([0-9]+)_([0-9]+)_([0-9]+)_([0-9]+)"),
            new Spec("org.telegram.messenger.MediaDataController", null, new String[0],
                    "Invalid type of the given stickerSetObject: ", "lastStickersLoadTimeGreet"),
            new Spec("org.telegram.messenger.SharedConfig", null, new String[0],
                    "passcodeHash1", "passcodeRetryInMs", "view_animations"),
            new Spec("org.telegram.messenger.NotificationCenter", null, new String[0],
                    "postNotificationName allowed only from MAIN thread",
                    "addObserver allowed only from MAIN thread"),
            new Spec("org.telegram.ui.Components.NumberPicker", "android.widget.LinearLayout",
                    new String[]{"mMinValue", "mMaxValue", "mSelectorIndices", "mValue"},
                    "maxValue must be >= 0", "minValue must be >= 0"),
            new Spec("org.telegram.ui.Components.PlayPauseDrawable", "android.graphics.drawable.Drawable",
                    new String[]{"pause", "progress", "duration", "lastUpdateTime", "size"}),
            new Spec("org.telegram.ui.Components.RLottieImageView", "android.widget.ImageView",
                    new String[]{"autoRepeat", "layerColors", "startOnAttach"}),
            new Spec("org.telegram.ui.Components.SeekBarView", "android.widget.FrameLayout",
                    new String[]{"bufferedProgress", "captured", "innerPaint1"}),
            new Spec("org.telegram.ui.Components.SpeedIconDrawable", "android.graphics.drawable.Drawable",
                    new String[]{"outlinePaint", "textDrawable"}),
            new Spec("org.telegram.ui.Components.AudioPlayerAlert", "org.telegram.ui.ActionBar.BottomSheet",
                    new String[]{"bigAlbumConver", "backwardButton", "speeds"}),
            new Spec("org.telegram.ui.Components.BackupImageView", "android.view.View",
                    new String[]{"imageReceiver", "blurImageReceiver", "roundRadiusAnimator"}),
            Spec.forMethods(PLATFORM_MEDIA_CALLBACK, "android.media.session.MediaSession$Callback",
                    "onSkipToNext", "onSkipToPrevious")
    ));

    interface Search {
        List<String> find(Spec spec);
    }

    private NiagramDexClasses() {}

    static Map<String, String> resolve(ApplicationInfo app, ClassLoader loader, HostBridge.Logger logger) {
        if (app == null || app.sourceDir == null || app.dataDir == null) {
            logger.log("缺少宿主 APK 或数据目录，无法解析 Niagram 混淆类", null);
            return Collections.emptyMap();
        }
        File cache = new File(app.dataDir, "cache/nagramx-video-timer/dex-classes.properties");
        try {
            List<File> apks = new ArrayList<>();
            apks.add(new File(app.sourceDir));
            if (app.splitSourceDirs != null) {
                for (String split : app.splitSourceDirs) apks.add(new File(split));
            }
            String fingerprint = fingerprint(apks);
            Map<String, String> cached = readCache(cache, fingerprint);
            if (!cached.isEmpty() && loadable(cached, loader)) {
                logger.log("命中 Niagram APK 指纹类缓存", null);
                return cached;
            }
            System.loadLibrary("dexkit");
            // 阿里规约 02-(三)：原生解析资源在每次解析结束后释放。
            try (DexKitBridge dex = DexKitBridge.create(loader, false)) {
                Map<String, String> found = search(spec -> {
                    ClassMatcher matcher = ClassMatcher.create();
                    if (spec.parent != null) matcher.superClass(spec.parent);
                    for (String field : spec.fields) matcher.addFieldForName(field);
                    for (String method : spec.methods) {
                        matcher.addMethod(MethodMatcher.create().name(method).paramTypes(new String[0]).returnType("void"));
                    }
                    if (spec.strings.length > 0) {
                        matcher.usingStrings(Arrays.asList(spec.strings), StringMatchType.Equals);
                    }
                    List<String> names = new ArrayList<>();
                    for (ClassData candidate : dex.findClass(FindClass.create().matcher(matcher))) {
                        names.add(candidate.getName());
                    }
                    return names;
                }, logger);
                if (!loadable(found, loader)) {
                    logger.log("Niagram 类候选未通过反射结构校验", null);
                    return Collections.emptyMap();
                }
                try {
                    writeCache(cache, fingerprint, found);
                } catch (IOException e) {
                    logger.log("保存 Niagram 类缓存失败，继续使用本次解析结果", e);
                }
                return found;
            }
        } catch (IOException | NoSuchAlgorithmException | RuntimeException | LinkageError e) {
            logger.log("解析 Niagram DEX 类失败", e);
            return Collections.emptyMap();
        }
    }

    static Map<String, String> search(Search search, HostBridge.Logger logger) {
        Map<String, String> result = new HashMap<>();
        for (Spec spec : SPECS) {
            List<String> candidates = search.find(spec);
            if (candidates.size() != 1) {
                logger.log("Niagram 类解析候选数=" + candidates.size() + "：" + spec.name, null);
                continue;
            }
            result.put(spec.name, candidates.get(0));
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean loadable(Map<String, String> classes, ClassLoader loader) {
        if (classes.isEmpty()) return false;
        try {
            for (Spec spec : SPECS) {
                String name = classes.get(spec.name);
                if (name != null && !spec.accepts(Class.forName(name, false, loader))) return false;
            }
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static String fingerprint(List<File> apks) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        for (File apk : apks) {
            digest.update(apk.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            MessageDigest content = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(apk)) {
                int count;
                while ((count = input.read(buffer)) != -1) content.update(buffer, 0, count);
            }
            digest.update(content.digest());
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }

    static Map<String, String> readCache(File file, String fingerprint) {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        } catch (IOException | IllegalArgumentException e) {
            return Collections.emptyMap();
        }
        if (!RULE_VERSION.equals(properties.getProperty("rules"))
                || !fingerprint.equals(properties.getProperty("apk"))) return Collections.emptyMap();
        Map<String, String> result = new HashMap<>();
        for (Spec spec : SPECS) {
            String name = properties.getProperty(spec.name);
            if (name == null) return Collections.emptyMap();
            if (!name.isEmpty()) result.put(spec.name, name);
        }
        return Collections.unmodifiableMap(result);
    }

    static void writeCache(File file, String fingerprint, Map<String, String> classes) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("无法创建 Niagram 类缓存目录");
        }
        Properties properties = new Properties();
        properties.setProperty("rules", RULE_VERSION);
        properties.setProperty("apk", fingerprint);
        for (Spec spec : SPECS) properties.setProperty(spec.name, classes.getOrDefault(spec.name, ""));
        File temporary = File.createTempFile("dex-classes-", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                properties.store(output, "Niagram class identities");
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }
}
