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
import java.util.ArrayList;
import java.util.HashMap;
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
 * and checks that a Swift package manifest cannot reach the network while it is resolved.
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
        // the beacon repository is served over TLS with a certificate nothing trusts
        env.put("GIT_SSL_NO_VERIFY", "1");
        // echoes every launcher argv into the server log
        env.put("DM_DEBUG_COMMANDS", "1");

        sdk = System.getProperty("extender.test.macSandboxSdk", DEFAULT_SDK);
        workDir = Files.createTempDirectory("mac-sandbox-e2e");
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
        waitForServer();

        String log = Files.readString(stdout);
        assertTrue(log.contains("Process sandbox enabled: backend=SEATBELT"), "sandbox not enabled at startup:\n" + tail(log));
        assertTrue(log.contains("strict=true"), tail(log));
    }

    @AfterAll
    static void stopServer() throws Exception {
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

        File destination = Files.createTempFile(workDir, "dmengine", ".zip").toFile();
        File log = Files.createTempFile(workDir, "dmengine", ".log").toFile();
        try {
            client().build(PLATFORM, sdk, sources, destination, log);
        } catch (ExtenderClientException e) {
            throw new AssertionError("build failed:\n" + Files.readString(log.toPath()) + "\n--- server ---\n" + tail(Files.readString(stdout)), e);
        }
        assertTrue(destination.length() > 0, "empty engine archive");
        try (ZipFile zip = new ZipFile(destination)) {
            assertNotNull(zip.getEntry("dmengine"), "no dmengine in the archive");
        }
        assertTrue(Files.readString(stdout).contains("Sandboxed: " + SERVER_DIR.resolve("app/extender-sandbox")),
            "no build command went through the launcher");
    }

    @Test
    public void manifestsCannotReachTheNetworkWhileResolving() throws Exception {
        String name = "Beacon" + UUID.randomUUID().toString().substring(0, 8);
        try (ServerSocket listener = new ServerSocket(0);
             BeaconRepoServer repoServer = BeaconRepoServer.start(workDir, name, listener.getLocalPort())) {
            Path upload = workDir.resolve("upload-" + name);
            Files.createDirectories(upload.resolve("src"));
            Files.createDirectories(upload.resolve("osx"));
            Files.writeString(upload.resolve("ext.manifest"), "name: SpmExt\n");
            Files.writeString(upload.resolve("src/spmext.mm"),
                "#include <dmsdk/sdk.h>\n"
                + "static dmExtension::Result Init(dmExtension::Params*) { return dmExtension::RESULT_OK; }\n"
                + "DM_DECLARE_EXTENSION(SpmExt, \"SpmExt\", 0, 0, Init, 0, 0, 0)\n");
            Files.writeString(upload.resolve("osx/SwiftPackages.json"),
                "{ \"platform\": \"osx\", \"minVersion\": \"14.0\", \"packages\": ["
                + "  { \"url\": \"" + repoServer.url() + "\", \"from\": \"1.0.0\", \"products\": [\"" + name + "\"] } ] }");
            List<ExtenderResource> sources = new ArrayList<>(List.of(
                new FileExtenderResource(upload.resolve("ext.manifest").toString(), "spmext/ext.manifest"),
                new FileExtenderResource(upload.resolve("src/spmext.mm").toString(), "spmext/src/spmext.mm"),
                new FileExtenderResource(upload.resolve("osx/SwiftPackages.json").toString(), "spmext/osx/SwiftPackages.json")));

            File destination = Files.createTempFile(workDir, "dmengine", ".zip").toFile();
            File log = Files.createTempFile(workDir, "dmengine", ".log").toFile();
            assertThrows(ExtenderClientException.class,
                () -> client().build(PLATFORM, sdk, sources, destination, log));
            String error = Files.readString(log.toPath());
            assertTrue(error.contains(SpmBeacon.MARKER), "the manifest never ran:\n" + error + "\n--- server ---\n" + tail(Files.readString(stdout)));
            assertTrue(error.contains("connect FAILED"), "a Package.swift reached the network while resolving:\n" + error);
        }
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

    /**
     * A bare git repository holding the beacon package, served with git's dumb HTTP protocol
     * over TLS on 0.0.0.0:443: the only shape of URL the manifest parser accepts is
     * https://host/path with the default port, and an unprivileged bind to 443 works on macOS.
     */
    private static final class BeaconRepoServer implements AutoCloseable {
        private final HttpsServer https;
        private final String name;

        private BeaconRepoServer(HttpsServer https, String name) {
            this.https = https;
            this.name = name;
        }

        String url() {
            return "https://localhost/" + name + ".git";
        }

        static BeaconRepoServer start(Path dir, String name, int beaconPort) throws Exception {
            Path source = dir.resolve("repos/" + name);
            Files.createDirectories(source.resolve("Sources/" + name));
            Files.writeString(source.resolve("Package.swift"), SpmBeacon.manifest(name, beaconPort));
            Files.writeString(source.resolve("Sources/" + name + "/" + name + ".swift"), "public let x = 1\n");
            git(source, "git", "init", "--quiet");
            git(source, "git", "add", "-A");
            git(source, "git", "-c", "user.email=e@example.com", "-c", "user.name=E", "commit", "--quiet", "-m", "init");
            git(source, "git", "tag", "1.0.0");
            Path bare = dir.resolve("repos/" + name + ".git");
            git(dir, "git", "clone", "--bare", "--quiet", source.toString(), bare.toString());
            git(bare, "git", "update-server-info");

            Path keystore = dir.resolve("beacon.p12");
            char[] password = "beacon".toCharArray();
            String keytool = System.getProperty("java.home") + "/bin/keytool";
            run(dir, keytool, "-genkeypair", "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", new String(password));
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(keystore)) {
                store.load(in, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, password);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);

            HttpsServer https;
            try {
                https = HttpsServer.create(new InetSocketAddress("0.0.0.0", 443), 0);
            } catch (IOException e) {
                assumeTrue(false, "cannot listen on 0.0.0.0:443: " + e.getMessage());
                throw e;
            }
            https.setHttpsConfigurator(new HttpsConfigurator(ssl));
            String prefix = "/" + name + ".git/";
            https.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                Path file = path.startsWith(prefix) ? bare.resolve(path.substring(prefix.length())).normalize() : null;
                if (file == null || !file.startsWith(bare) || !Files.isRegularFile(file)) {
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
            return new BeaconRepoServer(https, name);
        }

        @Override
        public void close() {
            https.stop(0);
        }

        private static void git(Path cwd, String... command) throws Exception {
            run(cwd, command);
        }

        private static void run(Path cwd, String... command) throws Exception {
            Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(2, TimeUnit.MINUTES) && process.exitValue() == 0, String.join(" ", command) + ":\n" + output);
        }
    }
}
