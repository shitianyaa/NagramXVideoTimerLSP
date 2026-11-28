package tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Pure-JDK dependency syncer, unit-test runner, and Android APK packager for NagramTimer.
 */
public final class ModuleBuild {
    private static final String DEFAULT_MAVEN_REPO = "https://repo1.maven.org/maven2";
    private static final String DEFAULT_BUILD_TOOLS = "37.0.0";
    private static final String DEFAULT_COMPILE_SDK = "34";
    private static final String DEBUG_KEYSTORE_PASS = "android";
    private static final String DEBUG_KEY_ALIAS = "mod";
    private static final String DEBUG_KEY_DNAME = "CN=nagramx-videotimer, O=local";
    private static final String ENTRY_JAVA_INIT = "java_init.list";
    private static final String HOST_PROFILE_FILE = "HostProfile.java";
    private static final int BT_MAJOR_PAGE_16KB = 35;
    private static final int ZIP_ALIGNMENT_BYTES = 4;
    private static final int PAGE_SIZE_KB_16 = 16;
    private static final int IO_BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int HTTP_OK = 200;
    private static final int HTTP_MULTIPLE_CHOICES = 300;
    private static final int MANIFEST_FIELD_COUNT = 5;
    private static final int KEY_SIZE_BITS = 2048;
    private static final int KEY_VALIDITY_DAYS = 10_000;
    private static final int SOCKS_DEFAULT_PORT = 1080;
    private static final int HTTP_PROXY_DEFAULT_PORT = 8080;
    private static final Pattern HOST_PACKAGE_PATTERN =
            Pattern.compile("PACKAGE_[A-Z0-9_]+\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MSYS_DRIVE_PATH = Pattern.compile("^/([a-zA-Z])/(.*)$");
    private static final Pattern VERSION_NON_DIGIT = Pattern.compile("[^0-9]+");
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private ModuleBuild() {
    }

    /** Dependency entry parsed from {@code module/deps.manifest}. */
    public static final class DepSpec {
        public final String fileName;
        public final String sha256;
        public final String mavenPath;
        public final String kind;
        public final String outputName;

        public DepSpec(String fileName, String sha256, String mavenPath, String kind, String outputName) {
            this.fileName = fileName;
            this.sha256 = sha256;
            this.mavenPath = mavenPath;
            this.kind = kind;
            this.outputName = outputName;
        }
    }

    /**
     * Entrypoint supporting {@code build}, {@code test}, and {@code sync} subcommands.
     *
     * @param args CLI arguments starting with subcommand name
     * @throws Exception on build, verification, or process failure
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsageAndFail("Missing subcommand (expected build | test | sync)");
            return;
        }
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        String cmd = args[0];
        switch (cmd) {
            case "sync":
                syncDeps(projectRoot.resolve("module"));
                break;
            case "test":
                runTests(projectRoot);
                break;
            case "build":
                runBuild(projectRoot, Arrays.copyOfRange(args, 1, args.length));
                break;
            default:
                printUsageAndFail("Unknown subcommand: " + cmd);
                break;
        }
    }

    private static void printUsageAndFail(String reason) {
        System.err.println(reason);
        System.err.println("Usage: ModuleBuild <build [--release] [--output <apk>] | test | sync>");
        System.exit(1);
    }

    private static void runBuild(Path projectRoot, String[] options) throws Exception {
        boolean release = false;
        String outputArg = null;
        for (int i = 0; i < options.length; i++) {
            String opt = options[i];
            if ("--release".equals(opt)) {
                release = true;
            } else if ("--debug".equals(opt)) {
                release = false;
            } else if ("--output".equals(opt) || "-o".equals(opt)) {
                if (i + 1 >= options.length) {
                    printUsageAndFail("Missing path after " + opt);
                    return;
                }
                outputArg = options[++i];
            } else if (!opt.startsWith("-") && outputArg == null) {
                outputArg = opt;
            } else {
                printUsageAndFail("Unknown build option: " + opt);
                return;
            }
        }

        Path mod = projectRoot.resolve("module");
        Map<String, String> props = readKvFile(projectRoot.resolve("module.properties"));
        String versionCode = requireProp(props, "MODULE_VERSION_CODE");
        String versionName = requireProp(props, "MODULE_VERSION_NAME");
        String minSdk = requireProp(props, "MIN_SDK");
        String targetSdk = requireProp(props, "TARGET_SDK");
        String compileSdk = props.getOrDefault("COMPILE_SDK", DEFAULT_COMPILE_SDK);
        String preferredBt = props.getOrDefault("BUILD_TOOLS", DEFAULT_BUILD_TOOLS);

        Path sdkRoot = resolveSdkRoot();
        Path jdkBin = resolveJdkBin();
        Path androidJar = resolveAndroidJar(sdkRoot, compileSdk);
        Path btDir = resolveBuildTools(sdkRoot, preferredBt);
        int btMajor = firstVersionInt(btDir.getFileName().toString());

        Path javac = jdkBin.resolve(exeName("javac"));
        Path keytool = jdkBin.resolve(exeName("keytool"));
        Path d8 = btDir.resolve(batName("d8"));
        Path aapt2 = btDir.resolve(exeName("aapt2"));
        Path zipalign = btDir.resolve(exeName("zipalign"));
        Path apksigner = btDir.resolve(batName("apksigner"));
        Path apiJar = mod.resolve("lib/api-102.jar");
        Path manifest = mod.resolve("AndroidManifest.xml");
        Path metaXposed = mod.resolve("meta/META-INF/xposed");

        requireFiles(javac, keytool, d8, aapt2, zipalign, apksigner, androidJar, apiJar, manifest,
                metaXposed.resolve(ENTRY_JAVA_INIT), metaXposed.resolve("module.prop"), metaXposed.resolve("scope.list"));

        Map<String, String> signing = resolveReleaseSigning(projectRoot, release);

        System.out.println("[0/7] 校验模块元数据与 Xposed 作用域一致...");
        validateModuleMetadata(mod, metaXposed);

        System.out.println("[1/7] 同步 DexKit 依赖...");
        List<Path> depJars = syncDeps(mod);

        String variant = release ? "release" : "debug";
        Path out = mod.resolve("build/cli").resolve(variant);
        deleteRecursive(out);
        Path classesDir = out.resolve("classes");
        Path dexDir = out.resolve("dex");
        Files.createDirectories(classesDir);
        Files.createDirectories(dexDir);

        List<Path> srcFiles = collectJavaSources(mod.resolve("src"));
        System.out.println("[2/7] javac 编译 (" + srcFiles.size() + " 个源文件)...");
        String cp = joinClasspath(androidJar, apiJar, depJars);
        Path classesJar = out.resolve("classes.jar");
        compileJava(javac, mod, out.resolve("sources.txt"), cp, classesDir, srcFiles);
        writeClassesJar(classesDir, classesJar);

        System.out.println("[3/7] d8 打包 DEX...");
        String d8Mode = release ? "--release" : "--debug";
        packageDex(d8, mod, d8Mode, minSdk, androidJar, apiJar, classesJar, depJars, dexDir);

        System.out.println("[4/7] aapt2 编译并链接资源与清单...");
        Path resZip = out.resolve("res.zip");
        Path baseApk = out.resolve("base.apk");
        runCommand(mod, aapt2.toString(), "compile", "--dir", mod.resolve("res").toString(), "-o", resZip.toString());
        runCommand(mod, aapt2.toString(), "link", "-o", baseApk.toString(),
                "-I", androidJar.toString(),
                "--manifest", manifest.toString(),
                "-A", mod.resolve("assets").toString(),
                "--min-sdk-version", minSdk,
                "--target-sdk-version", targetSdk,
                "--version-code", versionCode,
                "--version-name", versionName,
                resZip.toString());

        System.out.println("[5/7] 组装未签名 APK (含 16KB 页对齐未压缩 .so 与 Xposed 元数据)...");
        Path unsignedApk = out.resolve("unsigned.apk");
        packApk(baseApk, unsignedApk, dexDir, mod.resolve("build/deps/jni"), metaXposed);

        System.out.println("[6/7] zipalign 页对齐...");
        Path alignedApk = out.resolve("aligned.apk");
        if (btMajor >= BT_MAJOR_PAGE_16KB) {
            runCommand(mod, zipalign.toString(), "-P", String.valueOf(PAGE_SIZE_KB_16), "-f",
                    String.valueOf(ZIP_ALIGNMENT_BYTES), unsignedApk.toString(), alignedApk.toString());
        } else {
            runCommand(mod, zipalign.toString(), "-f", "-p",
                    String.valueOf(ZIP_ALIGNMENT_BYTES), unsignedApk.toString(), alignedApk.toString());
        }

        System.out.println("[7/7] apksigner 签名并校验...");
        Path signedApk = out.resolve("module.apk");
        if (release && signing.isEmpty()) {
            System.err.println("Warning: 未配置正式签名，回退到开发签名 (module/mod.keystore)。");
        }
        signAndVerifyApk(projectRoot, mod, keytool, apksigner, signing, alignedApk, signedApk);

        Path destination = outputArg != null
                ? Paths.get(normalizePathForOs(outputArg, IS_WINDOWS)).toAbsolutePath().normalize()
                : projectRoot.resolve("build").resolve("NagramTimer-v" + versionName + "-" + variant + ".apk");
        Files.createDirectories(Objects.requireNonNull(destination.getParent()));
        Files.copy(signedApk, destination, StandardCopyOption.REPLACE_EXISTING);

        System.out.println("Build succeeded: " + destination);
        System.out.println("Toolchain: compileSdk=" + androidJar.getParent().getFileName()
                + ", buildTools=" + btDir.getFileName()
                + ", variant=" + variant);
    }

    private static void runTests(Path projectRoot) throws Exception {
        Path mod = projectRoot.resolve("module");
        Map<String, String> props = readKvFile(projectRoot.resolve("module.properties"));
        String compileSdk = props.getOrDefault("COMPILE_SDK", DEFAULT_COMPILE_SDK);

        Path sdkRoot = resolveSdkRoot();
        Path jdkBin = resolveJdkBin();
        Path androidJar = resolveAndroidJar(sdkRoot, compileSdk);
        Path javac = jdkBin.resolve(exeName("javac"));
        Path java = jdkBin.resolve(exeName("java"));
        Path apiJar = mod.resolve("lib/api-102.jar");
        requireFiles(javac, java, androidJar, apiJar);

        List<Path> depJars = syncDeps(mod);

        Path outRoot = mod.resolve("build");
        Path testClasses = outRoot.resolve("test-classes");
        deleteRecursive(testClasses);
        Files.createDirectories(testClasses);

        List<Path> sources = new ArrayList<>();
        sources.addAll(collectJavaSources(mod.resolve("src")));
        sources.addAll(collectJavaSources(mod.resolve("test")));
        sources.add(mod.resolve("tools/ModuleBuild.java"));

        String cp = joinClasspath(androidJar, apiJar, depJars);
        compileJava(javac, mod, outRoot.resolve("test-sources.txt"), cp, testClasses, sources);

        String runCp = testClasses + File.pathSeparator + cp;
        runCommand(mod, java.toString(),
                "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
                "-Dtest.output=" + outRoot.toAbsolutePath(),
                "-cp", runCp,
                "com.shitianyaa.nagramx.videotimer.TestMain");
    }

    private static Map<String, String> resolveReleaseSigning(Path projectRoot, boolean release) throws IOException {
        Map<String, String> ksProps = readKvFile(projectRoot.resolve("keystore.properties"));
        String relPath = firstNonBlank(System.getenv("RELEASE_KEYSTORE_PATH"), ksProps.get("RELEASE_KEYSTORE_PATH"));
        String relStorePass = firstNonBlank(System.getenv("RELEASE_KEYSTORE_PASSWORD"), ksProps.get("RELEASE_KEYSTORE_PASSWORD"));
        String relAlias = firstNonBlank(System.getenv("RELEASE_KEY_ALIAS"), ksProps.get("RELEASE_KEY_ALIAS"));
        String relKeyPass = firstNonBlank(System.getenv("RELEASE_KEY_PASSWORD"), ksProps.get("RELEASE_KEY_PASSWORD"));

        boolean hasRelease = !isBlank(relPath) && !isBlank(relStorePass) && !isBlank(relAlias) && !isBlank(relKeyPass);
        boolean requireRelease = "true".equalsIgnoreCase(System.getenv("REQUIRE_RELEASE_SIGNING"));
        if (release && requireRelease && !hasRelease) {
            throw new IllegalStateException(
                    "Release signing is required (REQUIRE_RELEASE_SIGNING=true), but keystore properties/env vars are missing.");
        }
        if (!release || !hasRelease) {
            return Collections.emptyMap();
        }
        String normalized = normalizePathForOs(relPath, IS_WINDOWS);
        Path ksFile = Paths.get(normalized);
        if (!ksFile.isAbsolute()) {
            ksFile = projectRoot.resolve(ksFile).normalize();
        }
        if (!Files.isRegularFile(ksFile)) {
            throw new IllegalStateException("找不到指定的正式签名密钥库: " + ksFile);
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("path", ksFile.toString());
        result.put("storePass", relStorePass);
        result.put("alias", relAlias);
        result.put("keyPass", relKeyPass);
        return result;
    }

    private static void signAndVerifyApk(Path projectRoot, Path mod, Path keytool, Path apksigner,
                                         Map<String, String> signing, Path alignedApk, Path signedApk)
            throws IOException, InterruptedException {
        String ksFile;
        String ksAlias;
        String ksPass;
        String keyPass;
        if (!signing.isEmpty()) {
            ksFile = signing.get("path");
            ksAlias = signing.get("alias");
            ksPass = signing.get("storePass");
            keyPass = signing.get("keyPass");
        } else {
            Path debugKeystore = mod.resolve("mod.keystore");
            if (!Files.isRegularFile(debugKeystore)) {
                runCommand(mod, keytool.toString(), "-genkeypair",
                        "-keystore", debugKeystore.toString(),
                        "-alias", DEBUG_KEY_ALIAS,
                        "-keyalg", "RSA",
                        "-keysize", String.valueOf(KEY_SIZE_BITS),
                        "-validity", String.valueOf(KEY_VALIDITY_DAYS),
                        "-storepass", DEBUG_KEYSTORE_PASS,
                        "-keypass", DEBUG_KEYSTORE_PASS,
                        "-dname", DEBUG_KEY_DNAME);
            }
            ksFile = debugKeystore.toString();
            ksAlias = DEBUG_KEY_ALIAS;
            ksPass = DEBUG_KEYSTORE_PASS;
            keyPass = DEBUG_KEYSTORE_PASS;
        }

        Map<String, String> signEnv = new LinkedHashMap<>();
        signEnv.put("NXT_KS_PASS", ksPass);
        signEnv.put("NXT_KEY_PASS", keyPass);
        runCommandWithEnv(projectRoot, signEnv, apksigner.toString(), "sign",
                "--ks", ksFile,
                "--ks-key-alias", ksAlias,
                "--ks-pass", "env:NXT_KS_PASS",
                "--key-pass", "env:NXT_KEY_PASS",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--out", signedApk.toString(),
                alignedApk.toString());
        runCommand(projectRoot, apksigner.toString(), "verify", "--print-certs", signedApk.toString());
    }

    private static void validateModuleMetadata(Path mod, Path metaXposed) throws IOException {
        String entryClass = new String(Files.readAllBytes(metaXposed.resolve(ENTRY_JAVA_INIT)), StandardCharsets.UTF_8).trim();
        if (entryClass.isEmpty()) {
            throw new IllegalStateException("module/meta/META-INF/xposed/java_init.list 为空");
        }
        String entrySimple = entryClass.substring(entryClass.lastIndexOf('.') + 1);
        Path entryFile = findFileByName(mod.resolve("src"), entrySimple + ".java");
        if (entryFile == null) {
            throw new IllegalStateException("找不到入口类源文件: " + entryClass);
        }
        String entrySource = new String(Files.readAllBytes(entryFile), StandardCharsets.UTF_8);
        Pattern entryDecl = Pattern.compile("class\\s+" + Pattern.quote(entrySimple) + "\\s+extends\\s+XposedModule");
        if (!entryDecl.matcher(entrySource).find()) {
            throw new IllegalStateException("入口类 " + entryClass + " 不是 XposedModule 子类，拒绝出包");
        }

        Path hostProfile = findFileByName(mod.resolve("src"), HOST_PROFILE_FILE);
        if (hostProfile == null) {
            throw new IllegalStateException("找不到宿主配置类: " + HOST_PROFILE_FILE);
        }
        Set<String> codePkgs = extractHostPackages(new String(Files.readAllBytes(hostProfile), StandardCharsets.UTF_8));
        Set<String> scopePkgs = new TreeSet<>();
        for (String line : Files.readAllLines(metaXposed.resolve("scope.list"), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                scopePkgs.add(trimmed);
            }
        }
        if (codePkgs.isEmpty() || !codePkgs.equals(scopePkgs)) {
            throw new IllegalStateException("代码中的宿主包名常量与 module/meta/META-INF/xposed/scope.list 不一致: code="
                    + codePkgs + ", scope=" + scopePkgs);
        }
    }

    /** Extracts sorted {@code PACKAGE_*} constants from {@code HostProfile.java} source text. */
    public static Set<String> extractHostPackages(String hostProfileSource) {
        Set<String> packages = new TreeSet<>();
        Matcher matcher = HOST_PACKAGE_PATTERN.matcher(hostProfileSource);
        while (matcher.find()) {
            packages.add(matcher.group(1));
        }
        return packages;
    }

    /** Synchronizes pinned build dependencies from {@code module/deps.manifest}. */
    static List<Path> syncDeps(Path moduleRoot) throws Exception {
        Path manifestPath = moduleRoot.resolve("deps.manifest");
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("Missing deps.manifest: " + manifestPath);
        }
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        String manifestHash = sha256Hex(manifestBytes);
        List<DepSpec> specs = parseDepsManifest(Files.readAllLines(manifestPath, StandardCharsets.UTF_8));

        Path depsRoot = moduleRoot.resolve("build/deps");
        Path cacheDir = depsRoot.resolve("cache");
        Path jarsDir = depsRoot.resolve("jars");
        Path jniDir = depsRoot.resolve("jni");
        Path stampPath = depsRoot.resolve(".manifest.sha256");

        if (isDepsUpToDate(manifestHash, stampPath, cacheDir, jarsDir, jniDir, specs)) {
            return collectJarList(jarsDir, specs);
        }

        deleteRecursive(jarsDir);
        deleteRecursive(jniDir);
        Files.createDirectories(cacheDir);
        Files.createDirectories(jarsDir);
        Files.createDirectories(jniDir);

        String mavenRepo = stripTrailingSlash(firstNonBlank(System.getenv("MAVEN_REPO"), DEFAULT_MAVEN_REPO));
        for (DepSpec spec : specs) {
            Path cached = ensureCachedArtifact(spec, cacheDir, mavenRepo);
            if ("jar".equals(spec.kind)) {
                Files.copy(cached, jarsDir.resolve(spec.outputName), StandardCopyOption.REPLACE_EXISTING);
            } else if ("aar".equals(spec.kind)) {
                extractAarArtifact(cached, jarsDir.resolve(spec.outputName), jniDir);
            } else {
                throw new IllegalStateException("Unsupported dependency kind in deps.manifest: " + spec.kind);
            }
        }

        Files.write(stampPath, manifestHash.getBytes(StandardCharsets.UTF_8));
        return collectJarList(jarsDir, specs);
    }

    /** Parses non-comment lines from {@code module/deps.manifest}. */
    public static List<DepSpec> parseDepsManifest(List<String> lines) {
        List<DepSpec> specs = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|");
            if (parts.length != MANIFEST_FIELD_COUNT) {
                throw new IllegalArgumentException("Malformed deps.manifest line: " + line);
            }
            specs.add(new DepSpec(parts[0].trim(), parts[1].trim().toLowerCase(Locale.ROOT),
                    parts[2].trim(), parts[3].trim(), parts[4].trim()));
        }
        return specs;
    }

    private static boolean isDepsUpToDate(String manifestHash, Path stampPath, Path cacheDir,
                                          Path jarsDir, Path jniDir, List<DepSpec> specs) throws IOException {
        if (!Files.isRegularFile(stampPath)) {
            return false;
        }
        String recorded = new String(Files.readAllBytes(stampPath), StandardCharsets.UTF_8).trim();
        if (!manifestHash.equals(recorded)) {
            return false;
        }
        for (DepSpec spec : specs) {
            Path jarOut = jarsDir.resolve(spec.outputName);
            if (!Files.isRegularFile(jarOut)) {
                return false;
            }
            if ("aar".equals(spec.kind)) {
                Path cached = cacheDir.resolve(spec.fileName);
                if (!Files.isRegularFile(cached) || !verifyAarNativeLibsPresent(cached, jniDir)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean verifyAarNativeLibsPresent(Path aarPath, Path jniDir) throws IOException {
        try (ZipFile zip = new ZipFile(aarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String normalized = normalizeSafeZipName(entry.getName());
                if (!entry.isDirectory() && normalized.startsWith("jni/") && normalized.endsWith(".so")) {
                    String rel = normalized.substring("jni/".length());
                    if (!Files.isRegularFile(jniDir.resolve(rel))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static List<Path> collectJarList(Path jarsDir, List<DepSpec> specs) {
        List<Path> jars = new ArrayList<>(specs.size());
        for (DepSpec spec : specs) {
            jars.add(jarsDir.resolve(spec.outputName));
        }
        return jars;
    }

    private static Path ensureCachedArtifact(DepSpec spec, Path cacheDir, String mavenRepo)
            throws IOException, NoSuchAlgorithmException {
        Path cached = cacheDir.resolve(spec.fileName);
        if (Files.isRegularFile(cached) && spec.sha256.equals(sha256Hex(cached))) {
            return cached;
        }

        Path localHit = findLocalGradleArtifact(spec.fileName, spec.sha256);
        if (localHit != null) {
            Files.copy(localHit, cached, StandardCopyOption.REPLACE_EXISTING);
            return cached;
        }

        String urlText = mavenRepo + "/" + spec.mavenPath;
        Path tmp = cacheDir.resolve(spec.fileName + ".tmp");
        Files.deleteIfExists(tmp);
        System.out.println("Downloading " + spec.fileName + " from " + urlText + "...");
        downloadWithProxy(urlText, tmp);

        String actualHash = sha256Hex(tmp);
        if (!spec.sha256.equals(actualHash)) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("SHA-256 mismatch for " + spec.fileName
                    + ": expected " + spec.sha256 + ", got " + actualHash);
        }
        Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
        return cached;
    }

    private static Path findLocalGradleArtifact(String fileName, String expectedHash) {
        String userHome = System.getProperty("user.home");
        if (isBlank(userHome)) {
            return null;
        }
        Path gradleRoot = Paths.get(userHome, ".gradle", "caches", "modules-2", "files-2.1");
        if (!Files.isDirectory(gradleRoot)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(gradleRoot)) {
            List<Path> candidates = new ArrayList<>();
            walk.filter(p -> Files.isRegularFile(p) && fileName.equals(p.getFileName().toString()))
                    .forEach(candidates::add);
            for (Path candidate : candidates) {
                if (expectedHash.equals(sha256Hex(candidate))) {
                    return candidate;
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            System.err.println("Warning: failed to inspect local Gradle cache (" + e.getMessage() + "), falling back to remote download.");
        }
        return null;
    }

    private static void downloadWithProxy(String urlText, Path destination) throws IOException {
        Proxy proxy = resolveProxy();
        URL url = URI.create(urlText).toURL();
        HttpURLConnection conn = (HttpURLConnection) (proxy != null ? url.openConnection(proxy) : url.openConnection());
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code < HTTP_OK || code >= HTTP_MULTIPLE_CHOICES) {
                throw new IOException("HTTP " + code + " while downloading " + urlText);
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(destination)) {
                copyStream(in, out);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static Proxy resolveProxy() {
        String raw = firstNonBlank(System.getenv("HTTPS_PROXY"), System.getenv("https_proxy"));
        if (isBlank(raw)) {
            raw = firstNonBlank(System.getenv("ALL_PROXY"), System.getenv("all_proxy"));
        }
        if (isBlank(raw)) {
            raw = firstNonBlank(System.getenv("HTTP_PROXY"), System.getenv("http_proxy"));
        }
        if (isBlank(raw)) {
            return null;
        }
        try {
            URI uri = URI.create(raw.contains("://") ? raw : "http://" + raw);
            String host = uri.getHost();
            if (isBlank(host)) {
                return null;
            }
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean isSocks = scheme.startsWith("socks");
            int port = uri.getPort() > 0 ? uri.getPort() : (isSocks ? SOCKS_DEFAULT_PORT : HTTP_PROXY_DEFAULT_PORT);
            return new Proxy(isSocks ? Proxy.Type.SOCKS : Proxy.Type.HTTP, new InetSocketAddress(host, port));
        } catch (IllegalArgumentException e) {
            System.err.println("Warning: ignoring malformed proxy URL '" + raw + "': " + e.getMessage());
            return null;
        }
    }

    private static void extractAarArtifact(Path aarPath, Path outJar, Path jniDir) throws IOException {
        boolean foundClasses = false;
        try (ZipFile zip = new ZipFile(aarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeSafeZipName(entry.getName());
                if ("classes.jar".equals(name)) {
                    copyZipEntryToFile(zip, entry, outJar);
                    foundClasses = true;
                } else if (name.startsWith("jni/") && name.endsWith(".so")) {
                    String rel = name.substring("jni/".length());
                    Path target = jniDir.resolve(rel).normalize();
                    if (!target.startsWith(jniDir)) {
                        throw new IOException("Refusing to extract zip entry outside jni dir: " + name);
                    }
                    copyZipEntryToFile(zip, entry, target);
                }
            }
        }
        if (!foundClasses) {
            throw new IllegalStateException("AAR archive does not contain classes.jar: " + aarPath);
        }
    }

    private static void copyZipEntryToFile(ZipFile zip, ZipEntry entry, Path target) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = Files.newOutputStream(target)) {
            copyStream(in, out);
        }
    }

    private static String normalizeSafeZipName(String rawName) throws IOException {
        String normalized = rawName.replace('\\', '/');
        if (normalized.contains("..") || normalized.startsWith("/")) {
            throw new IOException("Unsafe zip entry path: " + rawName);
        }
        return normalized;
    }

    private static void compileJava(Path javac, Path workDir, Path argsFile, String classpath,
                                    Path classesDir, List<Path> sources) throws IOException, InterruptedException {
        List<String> lines = new ArrayList<>(sources.size());
        for (Path src : sources) {
            lines.add("\"" + src.toAbsolutePath().toString().replace('\\', '/') + "\"");
        }
        Files.write(argsFile, lines, StandardCharsets.UTF_8);
        runCommand(workDir, javac.toString(),
                "-J-Duser.language=en",
                "-J-Duser.country=US",
                "-encoding", "UTF-8",
                "-source", "8",
                "-target", "8",
                "-nowarn",
                "-cp", classpath,
                "-d", classesDir.toString(),
                "@" + argsFile.toAbsolutePath());
    }

    private static void writeClassesJar(Path classesDir, Path outJar) throws IOException {
        List<Path> files = collectFilesSorted(classesDir, Integer.MAX_VALUE);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outJar))) {
            for (Path file : files) {
                String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                byte[] data = Files.readAllBytes(file);
                writeZipEntry(out, entryName, data, false);
            }
        }
    }

    private static void packageDex(Path d8, Path mod, String d8Mode, String minSdk, Path androidJar,
                                   Path apiJar, Path classesJar, List<Path> depJars, Path dexDir)
            throws IOException, InterruptedException, NoSuchAlgorithmException {
        String variant = "--release".equals(d8Mode) ? "release" : "debug";
        String manifestHash = sha256Hex(Files.readAllBytes(mod.resolve("deps.manifest")));
        Path depDexDir = mod.resolve("build/deps/dex").resolve(variant).resolve("api" + minSdk);
        Path dexStamp = depDexDir.resolve(".deps.sha256");
        List<Path> depDexZips = new ArrayList<>(depJars.size());
        boolean cacheHit = Files.isRegularFile(dexStamp)
                && manifestHash.equals(new String(Files.readAllBytes(dexStamp), StandardCharsets.UTF_8).trim());
        for (Path depJar : depJars) {
            Path dexZip = depDexDir.resolve(depJar.getFileName().toString());
            depDexZips.add(dexZip);
            if (!Files.isRegularFile(dexZip)) {
                cacheHit = false;
            }
        }
        if (!cacheHit) {
            deleteRecursive(depDexDir);
            Files.createDirectories(depDexDir);
            for (int i = 0; i < depJars.size(); i++) {
                runCommand(mod, d8.toString(), d8Mode, "--intermediate",
                        "--min-api", minSdk,
                        "--lib", androidJar.toString(),
                        "--lib", apiJar.toString(),
                        "--output", depDexZips.get(i).toString(),
                        depJars.get(i).toString());
            }
            Files.write(dexStamp, manifestHash.getBytes(StandardCharsets.UTF_8));
        }

        List<String> mergeCmd = new ArrayList<>();
        mergeCmd.add(d8.toString());
        mergeCmd.add(d8Mode);
        mergeCmd.add("--min-api");
        mergeCmd.add(minSdk);
        mergeCmd.add("--lib");
        mergeCmd.add(androidJar.toString());
        mergeCmd.add("--lib");
        mergeCmd.add(apiJar.toString());
        mergeCmd.add("--output");
        mergeCmd.add(dexDir.toString());
        mergeCmd.add(classesJar.toString());
        for (Path depDex : depDexZips) {
            mergeCmd.add(depDex.toString());
        }
        runCommand(mod, mergeCmd.toArray(new String[0]));
    }

    private static void packApk(Path baseApk, Path outApk, Path dexDir, Path jniDir, Path metaDir) throws IOException {
        Set<String> seen = new HashSet<>();
        try (ZipFile base = new ZipFile(baseApk.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outApk))) {
            Enumeration<? extends ZipEntry> entries = base.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (!seen.add(name)) {
                    continue;
                }
                try (InputStream in = base.getInputStream(entry)) {
                    byte[] data = readAllBytesCompat(in);
                    writeZipEntry(out, name, data, entry.getMethod() == ZipEntry.STORED);
                }
            }

            for (Path dex : collectFilesSorted(dexDir, 1)) {
                String fn = dex.getFileName().toString();
                if (fn.startsWith("classes") && fn.endsWith(".dex") && seen.add(fn)) {
                    writeZipEntry(out, fn, Files.readAllBytes(dex), false);
                }
            }

            if (Files.isDirectory(jniDir)) {
                for (Path so : collectFilesSorted(jniDir, 2)) {
                    if (so.getFileName().toString().endsWith(".so")) {
                        String abi = so.getParent().getFileName().toString();
                        String entryName = "lib/" + abi + "/" + so.getFileName();
                        if (seen.add(entryName)) {
                            writeZipEntry(out, entryName, Files.readAllBytes(so), true);
                        }
                    }
                }
            }

            if (Files.isDirectory(metaDir)) {
                for (Path meta : collectFilesSorted(metaDir, 1)) {
                    String fn = meta.getFileName().toString();
                    String entryName = "META-INF/xposed/" + fn;
                    if (seen.add(entryName)) {
                        writeZipEntry(out, entryName, Files.readAllBytes(meta), ENTRY_JAVA_INIT.equals(fn));
                    }
                }
            }
        }
    }

    private static void writeZipEntry(ZipOutputStream out, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static Path resolveJdkBin() {
        String javaHome = System.getProperty("java.home");
        if (!isBlank(javaHome)) {
            Path home = Paths.get(javaHome);
            Path direct = home.resolve("bin");
            if (Files.isRegularFile(direct.resolve(exeName("javac")))) {
                return direct;
            }
            Path parent = home.getParent();
            if (parent != null && Files.isRegularFile(parent.resolve("bin").resolve(exeName("javac")))) {
                return parent.resolve("bin");
            }
        }
        throw new IllegalStateException("JDK javac not found from java.home=" + javaHome);
    }

    private static Path resolveSdkRoot() {
        List<String> candidates = new ArrayList<>();
        candidates.add(System.getenv("ANDROID_SDK_W"));
        candidates.add(System.getenv("ANDROID_HOME"));
        candidates.add(System.getenv("ANDROID_SDK_ROOT"));
        if (IS_WINDOWS) {
            candidates.add("D:\\AndroidSDK");
            String localAppData = System.getenv("LOCALAPPDATA");
            if (!isBlank(localAppData)) {
                candidates.add(localAppData + "\\Android\\Sdk");
            }
        } else {
            String userHome = System.getProperty("user.home", "");
            candidates.add(userHome + "/Android/Sdk");
            candidates.add(userHome + "/Library/Android/sdk");
        }
        for (String candidate : candidates) {
            if (isBlank(candidate)) {
                continue;
            }
            Path path = Paths.get(normalizePathForOs(candidate, IS_WINDOWS));
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new IllegalStateException("Android SDK directory not found (set ANDROID_HOME or ANDROID_SDK_ROOT)");
    }

    /** Resolves {@code android.jar} preferring {@code preferredCompileSdk}, then numeric descending. */
    static Path resolveAndroidJar(Path sdkRoot, String preferredCompileSdk) throws IOException {
        Path platformsDir = sdkRoot.resolve("platforms");
        if (!Files.isDirectory(platformsDir)) {
            throw new IllegalStateException("Missing Android SDK platforms directory: " + platformsDir);
        }
        if (!isBlank(preferredCompileSdk)) {
            Path preferred = platformsDir.resolve("android-" + preferredCompileSdk).resolve("android.jar");
            if (Files.isRegularFile(preferred)) {
                return preferred;
            }
        }
        List<Path> dirs = listSubdirs(platformsDir);
        dirs.sort((a, b) -> compareVersionDesc(
                stripPrefix(a.getFileName().toString(), "android-"),
                stripPrefix(b.getFileName().toString(), "android-")));
        for (Path dir : dirs) {
            Path jar = dir.resolve("android.jar");
            if (Files.isRegularFile(jar)) {
                return jar;
            }
        }
        throw new IllegalStateException("Missing platforms/android-*/android.jar under " + sdkRoot);
    }

    private static Path resolveBuildTools(Path sdkRoot, String preferredBt) throws IOException {
        Path btRoot = sdkRoot.resolve("build-tools");
        if (!Files.isDirectory(btRoot)) {
            throw new IllegalStateException("Missing Android SDK build-tools directory: " + btRoot);
        }
        String aaptName = exeName("aapt2");
        if (!isBlank(preferredBt)) {
            Path preferred = btRoot.resolve(preferredBt);
            if (Files.isRegularFile(preferred.resolve(aaptName))) {
                return preferred;
            }
        }
        List<Path> dirs = listSubdirs(btRoot);
        dirs.sort((a, b) -> compareVersionDesc(a.getFileName().toString(), b.getFileName().toString()));
        for (Path dir : dirs) {
            if (Files.isRegularFile(dir.resolve(aaptName))) {
                return dir;
            }
        }
        throw new IllegalStateException("Missing build-tools/*/aapt2 under " + sdkRoot);
    }

    /** Compares dotted numeric versions in descending order (e.g. {@code 37.0.0} before {@code 9.0.0}). */
    public static int compareVersionDesc(String a, String b) {
        int[] segA = parseVersionSegments(a);
        int[] segB = parseVersionSegments(b);
        int max = Math.max(segA.length, segB.length);
        for (int i = 0; i < max; i++) {
            int va = i < segA.length ? segA[i] : 0;
            int vb = i < segB.length ? segB[i] : 0;
            if (va != vb) {
                return Integer.compare(vb, va);
            }
        }
        return b.compareTo(a);
    }

    /** Normalizes Git Bash / MSYS drive paths such as {@code /d/AndroidSDK} when running on Windows. */
    public static String normalizePathForOs(String rawPath, boolean windows) {
        if (isBlank(rawPath) || !windows) {
            return rawPath;
        }
        String trimmed = rawPath.trim();
        Matcher msys = MSYS_DRIVE_PATH.matcher(trimmed);
        if (msys.matches()) {
            return Character.toUpperCase(msys.group(1).charAt(0)) + ":\\" + msys.group(2).replace('/', '\\');
        }
        return trimmed;
    }

    static Map<String, String> readKvFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static int[] parseVersionSegments(String version) {
        String[] parts = VERSION_NON_DIGIT.split(version == null ? "" : version.trim());
        List<Integer> nums = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                // Clamp segments over 9 digits without catching NumberFormatException for control flow.
                nums.add(part.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(part));
            }
        }
        int[] arr = new int[nums.size()];
        for (int i = 0; i < nums.size(); i++) {
            arr[i] = nums.get(i);
        }
        return arr;
    }

    private static int firstVersionInt(String version) {
        int[] segs = parseVersionSegments(version);
        return segs.length > 0 ? segs[0] : 0;
    }

    private static String stripPrefix(String text, String prefix) {
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }

    private static List<Path> listSubdirs(Path dir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    private static List<Path> collectJavaSources(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(p -> p.toString().replace('\\', '/')))
                    .forEach(result::add);
        }
        return result;
    }

    private static Path findFileByName(Path root, String fileName) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> Files.isRegularFile(p) && fileName.equals(p.getFileName().toString()))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
    }

    private static List<Path> collectFilesSorted(Path dir, int maxDepth) throws IOException {
        List<Path> list = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return list;
        }
        try (Stream<Path> walk = Files.walk(dir, maxDepth)) {
            walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.toString().replace('\\', '/')))
                    .forEach(list::add);
        }
        return list;
    }

    private static String joinClasspath(Path androidJar, Path apiJar, List<Path> depJars) {
        String sep = File.pathSeparator;
        StringBuilder sb = new StringBuilder();
        sb.append(androidJar).append(sep).append(apiJar);
        for (Path dep : depJars) {
            sb.append(sep).append(dep);
        }
        return sb.toString();
    }

    private static void runCommand(Path workDir, String... cmd) throws IOException, InterruptedException {
        runCommandWithEnv(workDir, Collections.emptyMap(), cmd);
    }

    private static void runCommandWithEnv(Path workDir, Map<String, String> extraEnv, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.inheritIO();
        if (!extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        Process process = pb.start();
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Command failed with exit code " + code + ": " + cmd[0]);
        }
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(all::add);
        }
        for (Path p : all) {
            Files.deleteIfExists(p);
        }
    }

    private static byte[] readAllBytesCompat(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copyStream(in, out);
        return out.toByteArray();
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[IO_BUFFER_SIZE];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
    }

    private static String sha256Hex(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[IO_BUFFER_SIZE];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
        }
        return formatHex(md.digest());
    }

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        return formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String formatHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void requireFiles(Path... paths) {
        for (Path p : paths) {
            if (!Files.exists(p)) {
                throw new IllegalStateException("缺少依赖工具或文件: " + p);
            }
        }
    }

    private static String requireProp(Map<String, String> props, String key) {
        String value = props.get(key);
        if (isBlank(value)) {
            throw new IllegalStateException("module.properties 缺少必填字段: " + key);
        }
        return value;
    }

    private static String firstNonBlank(String primary, String fallback) {
        return !isBlank(primary) ? primary.trim() : (!isBlank(fallback) ? fallback.trim() : "");
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static String stripTrailingSlash(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    private static String exeName(String base) {
        return IS_WINDOWS ? base + ".exe" : base;
    }

    private static String batName(String base) {
        return IS_WINDOWS ? base + ".bat" : base;
    }
}
