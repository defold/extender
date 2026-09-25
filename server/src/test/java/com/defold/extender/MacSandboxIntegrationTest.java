package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.defold.extender.client.ExtenderClient;
import com.defold.extender.client.ExtenderClientException;
import com.defold.extender.client.ExtenderResource;
import com.defold.extender.client.FileExtenderResource;
import com.defold.extender.services.spm.SpmBeacon;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * The macOS counterpart of the sandbox cases in {@link IntegrationTest}: boots the standalone
 * server from server/app (the jar and launcher that setup-standalone-env.sh produces, the env
 * that generate_user_env.sh writes) on a free port, builds arm64-osx under the Seatbelt sandbox
 * and checks the Swift package flow: a manifest cannot reach the network while it is resolved,
 * and binary target archives reach SwiftPM through the artifact cache.
 * Docker-free; needs Xcode, xcodegen, and a populated platformsdk. Opt in with
 * {@code -PmacSandboxE2e=true}; {@code -PmacSandboxSdk=<sha1>} picks the Defold SDK.
 */
@EnabledOnOs({OS.MAC})
@EnabledIfSystemProperty(named = "extender.test.macSandboxE2e", matches = "true")
@Tag("integration")
public class MacSandboxIntegrationTest {

    private static final String DEFAULT_SDK = "574678c7d44be490d874fbed2d0ae6211feec4d9"; // 1.13.1
    private static final String PLATFORM = "arm64-osx";
    private static final Path SERVER_DIR = Path.of("").toAbsolutePath();

    private static String sdk;
    private static int port;
    private static Process server;
    private static Path stdout;
    private static Path workDir;
    private static Path www;
    private static SSLContext tls;
    private static LocalHttps https;

    @BeforeAll
    static void bootServer() throws Exception {
        Path jar = SERVER_DIR.resolve("app/extender.jar");
        Path launcher = SERVER_DIR.resolve("app/extender-sandbox");
        assumeTrue(Files.isRegularFile(jar), "no server jar at " + jar);
        assumeTrue(Files.isExecutable(launcher), "no sandbox launcher at " + launcher);
        assumeTrue(Files.isDirectory(Path.of("/Applications/Xcode.app/Contents/Developer")), "Xcode.app not installed");
        assumeTrue(Files.isExecutable(Path.of("/opt/homebrew/bin/xcodegen")), "xcodegen not installed");

        Map<String, String> env = new HashMap<>();
        for (String name : List.of(".env", "user.env", "macos.env")) {
            Path file = SERVER_DIR.resolve("envs/" + name);
            assumeTrue(Files.isRegularFile(file), "missing " + file + " (run envs/generate_user_env.sh)");
            env.putAll(readEnvFile(file));
        }
        // a DYNAMO_HOME in user.env would make the server ignore the requested SDK
        env.remove("DYNAMO_HOME");
        workDir = Files.createTempDirectory("mac-sandbox-e2e");
        www = Files.createDirectories(workDir.resolve("www"));
        // the local HTTPS server the tests publish packages on; git skips verification, curl
        // (binary target archives) gets the certificate through the granted CA bundle variable
        Path certificate = createServerCertificate();
        env.put("GIT_SSL_NO_VERIFY", "1");
        env.put("CURL_CA_BUNDLE", certificate.toString());
        // echoes every launcher argv into the server log
        env.put("DM_DEBUG_COMMANDS", "1");

        sdk = System.getProperty("extender.test.macSandboxSdk", DEFAULT_SDK);
        stdout = workDir.resolve("server.log");
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String java = env.getOrDefault("JAVA_HOME", System.getProperty("java.home")) + "/bin/java";
        List<String> command = List.of(java, "-Xmx4g", "-XX:MaxDirectMemorySize=2g", "-jar", jar.toString(),
            "--server.port=" + port,
            "--spring.profiles.active=standalone-dev",
            "--spring.config.additional-location=file:" + SERVER_DIR.resolve("configs") + "/",
            "--extender.sdk.location=" + env.getOrDefault("EXTENDER_SDK_LOCATION", SERVER_DIR.resolve("app/sdk").toString()),
            "--extender.sandbox.launcher-path=" + launcher,
            // caches of the live standalone instance stay untouched
            "--extender.spm.home-dir-prefix=" + workDir.resolve(".swiftpm"),
            "--extender.cocoapods.home-dir-prefix=" + workDir.resolve(".cocoapods"));
        ProcessBuilder pb = new ProcessBuilder(command)
            .directory(SERVER_DIR.resolve("app").toFile())
            .redirectErrorStream(true)
            .redirectOutput(stdout.toFile());
        pb.environment().putAll(env);
        server = pb.start();
        https = LocalHttps.start();
        waitForServer();

        String log = Files.readString(stdout);
        assertTrue(log.contains("Process sandbox enabled: backend=SEATBELT"), "sandbox not enabled at startup:\n" + tail(log));
        assertTrue(log.contains("strict=true"), tail(log));
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (https != null) {
            https.close();
        }
        if (server != null) {
            server.destroy();
            if (!server.waitFor(30, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
    }

    @Test
    public void buildsOsxUnderTheSandbox() throws Exception {
        List<ExtenderResource> sources = new ArrayList<>(List.of(
            new FileExtenderResource("test-data/ext/ext.manifest"),
            new FileExtenderResource("test-data/ext/src/test_ext.cpp"),
            new FileExtenderResource("test-data/ext/lib/arm64-osx/libalib.a")));

        File destination = build(sources);
        try (ZipFile zip = new ZipFile(destination)) {
            assertNotNull(zip.getEntry("dmengine"), "no dmengine in the archive");
        }
        assertTrue(Files.readString(stdout).contains("Sandboxed: " + SERVER_DIR.resolve("app/extender-sandbox")),
            "no build command went through the launcher");
    }

    @Test
    public void manifestsCannotReachTheNetworkWhileResolving() throws Exception {
        String name = "Beacon" + UUID.randomUUID().toString().substring(0, 8);
        try (ServerSocket listener = new ServerSocket(0)) {
            Path source = Files.createDirectories(workDir.resolve("repos/" + name));
            Files.createDirectories(source.resolve("Sources/" + name));
            Files.writeString(source.resolve("Package.swift"), SpmBeacon.manifest(name, listener.getLocalPort()));
            Files.writeString(source.resolve("Sources/" + name + "/" + name + ".swift"), "public let x = 1\n");
            publish(source, name, "1.0.0");

            List<ExtenderResource> sources = spmExtension(name, "https://localhost/" + name + ".git", "1.0.0", name, "");
            File destination = Files.createTempFile(workDir, "dmengine", ".zip").toFile();
            File log = Files.createTempFile(workDir, "dmengine", ".log").toFile();
            assertThrows(ExtenderClientException.class,
                () -> client().build(PLATFORM, sdk, sources, destination, log));
            String error = Files.readString(log.toPath());
            assertTrue(error.contains(SpmBeacon.MARKER), "the manifest never ran:\n" + error + "\n--- server ---\n" + tail(Files.readString(stdout)));
            assertTrue(error.contains("connect FAILED"), "a Package.swift reached the network while resolving:\n" + error);
        }
    }

    /**
     * A package whose product wraps a binary target: the archive is served next to the
     * repository, must reach SwiftPM through the artifact cache (resolution has no network),
     * and its static library must end up on the engine link, which calls into it.
     */
    @Test
    public void binaryTargetArchivesReachTheBuildThroughTheArtifactCache() throws Exception {
        String name = "Bin" + UUID.randomUUID().toString().substring(0, 8);
        {
            Path source = Files.createDirectories(workDir.resolve("repos/" + name));
            String archiveUrl = "https://localhost/" + name + ".zip";
            String checksum = publishArchive(name, 1);
            writeBinaryPackage(source, name, archiveUrl, checksum);
            publish(source, name, "1.0.0");

            String extern = "extern \"C\" int " + name + "_add(int, int);\n";
            List<ExtenderResource> sources = spmExtension(name, "https://localhost/" + name + ".git", "1.0.0", name,
                extern + "static volatile int g_" + name + " = " + name + "_add(1, 2);\n");
            build(sources);
            // the first round names the archive, curl fetches it, the second round builds with
            // it in the cache and no network: a green build is the proof SwiftPM took it from there
            String serverLog = Files.readString(stdout);
            assertTrue(serverLog.contains("Fetching archive of binary target " + name + "Bin from " + archiveUrl), tail(serverLog));
            assertTrue(serverLog.contains("round 2, 1 repositories mirrored, 1 artifacts cached"), tail(serverLog));

            // the publisher replaces the archive behind the same URL and bumps the checksum:
            // the cached copy no longer matches, is evicted and fetched again
            String replaced = publishArchive(name, 2);
            writeBinaryPackage(source, name, archiveUrl, replaced);
            publish(source, name, "1.0.1");
            List<ExtenderResource> bumped = spmExtension(name, "https://localhost/" + name + ".git", "1.0.1", name,
                extern + "static volatile int g_" + name + " = " + name + "_add(1, 2);\n");
            build(bumped);
            serverLog = Files.readString(stdout);
            assertTrue(serverLog.contains("does not match its manifest checksum, fetching again"), tail(serverLog));
            assertTrue(serverLog.indexOf("Fetching archive of binary target " + name + "Bin from " + archiveUrl)
                < serverLog.lastIndexOf("Fetching archive of binary target " + name + "Bin from " + archiveUrl),
                "the replaced archive was not fetched again:\n" + tail(serverLog));
        }
    }

    private static File build(List<ExtenderResource> sources) throws Exception {
        File destination = Files.createTempFile(workDir, "dmengine", ".zip").toFile();
        File log = Files.createTempFile(workDir, "dmengine", ".log").toFile();
        try {
            client().build(PLATFORM, sdk, sources, destination, log);
        } catch (ExtenderClientException e) {
            throw new AssertionError("build failed:\n" + Files.readString(log.toPath()) + "\n--- server ---\n" + tail(Files.readString(stdout)), e);
        }
        assertTrue(destination.length() > 0, "empty engine archive");
        return destination;
    }

    /** An extension declaring one Swift package; {@code extraSource} lands in its .mm file. */
    private static List<ExtenderResource> spmExtension(String name, String url, String from, String product,
            String extraSource) throws IOException {
        Path upload = workDir.resolve("upload-" + name + "-" + from);
        Files.createDirectories(upload.resolve("src"));
        Files.createDirectories(upload.resolve("osx"));
        Files.writeString(upload.resolve("ext.manifest"), "name: SpmExt\n");
        Files.writeString(upload.resolve("src/spmext.mm"),
            "#include <dmsdk/sdk.h>\n" + extraSource
            + "static dmExtension::Result Init(dmExtension::Params*) { return dmExtension::RESULT_OK; }\n"
            + "DM_DECLARE_EXTENSION(SpmExt, \"SpmExt\", 0, 0, Init, 0, 0, 0)\n");
        Files.writeString(upload.resolve("osx/SwiftPackages.json"),
            "{ \"platform\": \"osx\", \"minVersion\": \"14.0\", \"packages\": ["
            + "  { \"url\": \"" + url + "\", \"from\": \"" + from + "\", \"products\": [\"" + product + "\"] } ] }");
        return new ArrayList<>(List.of(
            new FileExtenderResource(upload.resolve("ext.manifest").toString(), "spmext/ext.manifest"),
            new FileExtenderResource(upload.resolve("src/spmext.mm").toString(), "spmext/src/spmext.mm"),
            new FileExtenderResource(upload.resolve("osx/SwiftPackages.json").toString(), "spmext/osx/SwiftPackages.json")));
    }

    private static void writeBinaryPackage(Path source, String name, String archiveUrl, String checksum) throws IOException {
        Files.createDirectories(source.resolve("Sources/" + name));
        Files.writeString(source.resolve("Package.swift"),
            "// swift-tools-version:5.9\n"
            + "import PackageDescription\n"
            + "let package = Package(name: \"" + name + "\",\n"
            + "    products: [.library(name: \"" + name + "\", targets: [\"" + name + "\"])],\n"
            + "    targets: [\n"
            + "        .binaryTarget(name: \"" + name + "Bin\", url: \"" + archiveUrl + "\", checksum: \"" + checksum + "\"),\n"
            + "        .target(name: \"" + name + "\", dependencies: [\"" + name + "Bin\"])])\n");
        Files.writeString(source.resolve("Sources/" + name + "/" + name + ".swift"),
            "import " + name + "Bin\npublic func " + name + "_value() -> Int32 { " + name + "_add(1, 2) }\n");
    }

    /** Builds a one-function static xcframework, zips it into www/<name>.zip and returns SwiftPM's checksum. */
    private static String publishArchive(String name, int generation) throws Exception {
        Path dir = Files.createDirectories(workDir.resolve("xcframework-" + name + "-" + generation));
        Path include = Files.createDirectories(dir.resolve("include"));
        Files.writeString(dir.resolve("lib.c"), "int " + name + "_add(int a, int b) { return a + b + " + generation + "; }\n");
        Files.writeString(include.resolve(name + ".h"), "int " + name + "_add(int a, int b);\n");
        Files.writeString(include.resolve("module.modulemap"), "module " + name + "Bin { header \"" + name + ".h\"\nexport * }\n");
        run(dir, "/usr/bin/clang", "-c", "-arch", "arm64", "-mmacosx-version-min=11.0", "lib.c", "-o", "lib.o");
        run(dir, "/usr/bin/ar", "rcs", "lib" + name + ".a", "lib.o");
        Path xcframework = dir.resolve(name + "Bin.xcframework");
        run(dir, "/usr/bin/xcodebuild", "-create-xcframework", "-library", "lib" + name + ".a", "-headers", "include",
            "-output", xcframework.toString());
        Path zip = dir.resolve(name + ".zip");
        run(dir, "/usr/bin/ditto", "-c", "-k", "--keepParent", xcframework.toString(), zip.toString());
        Files.copy(zip, www.resolve(name + ".zip"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
    }

    /** Commits and tags the package and refreshes its dumb-HTTP bare clone under www/. */
    private static void publish(Path source, String name, String tag) throws Exception {
        if (!Files.isDirectory(source.resolve(".git"))) {
            run(source, "git", "init", "--quiet");
        }
        run(source, "git", "add", "-A");
        run(source, "git", "-c", "user.email=e@example.com", "-c", "user.name=E", "commit", "--quiet", "-m", tag);
        run(source, "git", "tag", tag);
        Path bare = www.resolve(name + ".git");
        if (Files.isDirectory(bare)) {
            run(bare, "git", "fetch", "--quiet", "--tags", source.toString(), "+refs/heads/*:refs/heads/*");
        } else {
            run(www, "git", "clone", "--bare", "--quiet", source.toString(), bare.toString());
        }
        run(bare, "git", "update-server-info");
    }

    private static ExtenderClient client() throws IOException {
        return new ExtenderClient("http://localhost:" + port, Files.createTempDirectory(workDir, "client-cache").toFile());
    }

    private static void waitForServer() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (System.nanoTime() < deadline) {
            assertTrue(server.isAlive(), "server exited during startup:\n" + tail(Files.readString(stdout)));
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:" + port + "/actuator/health").toURL().openConnection();
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                if (connection.getResponseCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("server did not come up on port " + port + ":\n" + tail(Files.readString(stdout)));
    }

    private static String tail(String text) {
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - 60);
        return String.join("\n", List.of(lines).subList(from, lines.length));
    }

    /** KEY=value lines as written by generate_user_env.sh; a quoted value loses its quotes. */
    private static Map<String, String> readEnvFile(Path file) throws IOException {
        Map<String, String> result = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            if (line.isBlank() || line.startsWith("#") || eq < 0) {
                continue;
            }
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(line.substring(0, eq).strip(), value);
        }
        return result;
    }

    /** A self-signed certificate for localhost; the PEM export is what curl in the server trusts. */
    private static Path createServerCertificate() throws Exception {
        Path keystore = workDir.resolve("localhost.p12");
        char[] password = "localhost".toCharArray();
        String keytool = System.getProperty("java.home") + "/bin/keytool";
        run(workDir, keytool, "-genkeypair", "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
            "-keystore", keystore.toString(), "-storepass", new String(password));
        Path pem = workDir.resolve("localhost.pem");
        run(workDir, keytool, "-exportcert", "-rfc", "-alias", "localhost", "-keystore", keystore.toString(),
            "-storepass", new String(password), "-file", pem.toString());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            store.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password);
        tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        return pem;
    }

    private static void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0, String.join(" ", command) + ":\n" + output);
    }

    /**
     * Serves www/ over TLS on 0.0.0.0:443: git's dumb HTTP protocol for the bare repositories
     * and plain files for the archives. The manifest parser accepts only https://host/path with
     * the default port, and an unprivileged bind to 443 works on macOS.
     */
    private static final class LocalHttps implements AutoCloseable {
        private final HttpsServer https;

        private LocalHttps(HttpsServer https) {
            this.https = https;
        }

        static LocalHttps start() throws IOException {
            HttpsServer https;
            try {
                https = HttpsServer.create(new InetSocketAddress("0.0.0.0", 443), 0);
            } catch (IOException e) {
                assumeTrue(false, "cannot listen on 0.0.0.0:443: " + e.getMessage());
                throw e;
            }
            https.setHttpsConfigurator(new HttpsConfigurator(tls));
            https.createContext("/", exchange -> {
                Path file = www.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
                if (!file.startsWith(www) || !Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] body = Files.readAllBytes(file);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            https.start();
            return new LocalHttps(https);
        }

        @Override
        public void close() {
            https.stop(0);
        }
    }
}
