package com.smartup24.cms.instance.webhook;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** NFR-SEC-06: real TLS receivers with trust limited to a disposable localhost certificate. */
class WebhookTlsExtension implements BeforeEachCallback, AfterEachCallback {
    private static SSLContext context;
    private SSLContext previous;

    @Override
    public void beforeEach(ExtensionContext ignored) throws Exception {
        previous = SSLContext.getDefault();
        SSLContext.setDefault(context());
    }

    @Override
    public void afterEach(ExtensionContext ignored) {
        SSLContext.setDefault(previous);
    }

    static HttpsServer server() throws Exception {
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context()));
        return server;
    }

    private static synchronized SSLContext context() throws Exception {
        if (context != null) return context;
        Path directory = Files.createTempDirectory("webhook-tls-");
        Path store = directory.resolve("receiver.p12");
        String password = UUID.randomUUID().toString();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        try {
            var builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-genkeypair",
                    "-alias",
                    "receiver",
                    "-keyalg",
                    "EC",
                    "-groupname",
                    "secp256r1",
                    "-dname",
                    "CN=localhost",
                    "-ext",
                    "SAN=ip:127.0.0.1,dns:localhost",
                    "-validity",
                    "2",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    store.toString(),
                    "-storepass:env",
                    "SMC_TEST_STORE_PASSWORD",
                    "-noprompt");
            builder.environment().put("SMC_TEST_STORE_PASSWORD", password);
            var process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                throw new IllegalStateException("Disposable TLS certificate generation timed out");
            }
            if (process.exitValue() != 0)
                throw new IllegalStateException("Disposable TLS certificate generation failed");
            var keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) {
                keys.load(input, password.toCharArray());
            }
            var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password.toCharArray());
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keys);
            var created = SSLContext.getInstance("TLS");
            created.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
            context = created;
            return created;
        } finally {
            Files.deleteIfExists(store);
            Files.deleteIfExists(directory);
        }
    }
}
