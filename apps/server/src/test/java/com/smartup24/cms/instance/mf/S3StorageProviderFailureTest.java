package com.smartup24.cms.instance.mf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.mf.storage.S3StorageProperties;
import com.smartup24.cms.instance.mf.storage.S3StorageProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The S3-compatible storage against a client that fails: a missing object is not an error, any other failure of the
 * store is, the answer of a store that changed the content is refused, and every operation is measured by outcome.
 */
class S3StorageProviderFailureTest {

    private static final byte[] CONTENT = "SmartupCMS stored file".getBytes(StandardCharsets.UTF_8);

    private final S3Client client = mock(S3Client.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final S3StorageProvider provider = new S3StorageProvider(client, properties(), meters);

    @Test
    @DisplayName("Upload stores the content under the instance bucket with its SHA-256 and reports it")
    void uploadStoresContentWithItsChecksum() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        var stored = provider.upload("files", "a/b.txt", new ByteArrayInputStream(CONTENT), CONTENT.length, " ");

        assertThat(stored.sizeBytes()).isEqualTo(CONTENT.length);
        assertThat(stored.contentType()).isEqualTo("application/octet-stream");
        assertThat(stored.sha256()).hasSize(64);
        assertThat(outcome("upload", "success")).isEqualTo(1);
    }

    @Test
    @DisplayName("A store that answers another checksum loses the object and fails the upload")
    void checksumMismatchDeletesTheObject() {
        String other = Base64.getEncoder().encodeToString(new byte[32]);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().checksumSHA256(other).build());

        assertThatThrownBy(() -> provider.upload(
                        "files", "c.txt", new ByteArrayInputStream(CONTENT), CONTENT.length, "text/plain"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("S3 storage upload failed")
                .cause()
                .hasMessageContaining("checksum");
        verify(client).deleteObject(any(DeleteObjectRequest.class));
        assertThat(outcome("upload", "error")).isEqualTo(1);
    }

    @Test
    @DisplayName("Content that is shorter or longer than declared is rejected before the store sees it")
    void sizeMismatchIsRejected() {
        assertThatThrownBy(() -> provider.upload(
                        "files", "short.txt", new ByteArrayInputStream(CONTENT), CONTENT.length + 1L, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.upload(
                        "files", "long.txt", new ByteArrayInputStream(CONTENT), CONTENT.length - 1L, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.upload("files", "none.txt", null, 1, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(outcome("upload", "rejected")).isEqualTo(2);
    }

    @Test
    @DisplayName("A source that fails while it is read fails the upload as a storage error")
    void unreadableSourceFailsTheUpload() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk gone");
            }
        };

        assertThatThrownBy(() -> provider.upload("files", "broken.txt", broken, 10, "text/plain"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("S3 storage upload failed");
    }

    @Test
    @DisplayName("Unsafe addresses never reach the store")
    void unsafeAddressesAreRefused() {
        for (String[] address : new String[][] {
            {"files", "/absolute"},
            {"files", "a\\b"},
            {"files", "a/../b"},
            {"fi/les", "x"},
            {"files", "ctl\u0001"},
            {"files", " "},
            {"files", "k".repeat(1100)}
        }) {
            assertThatThrownBy(() -> provider.exists(address[0], address[1]))
                    .as(address[1])
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> provider.download(null, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Download: a missing object is null, a 404 is null, any other failure is a storage error")
    void downloadOutcomes() {
        GetObjectResponse response =
                GetObjectResponse.builder().contentLength(5L).contentType(null).build();
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(
                        response,
                        AbortableInputStream.create(
                                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)))))
                .thenThrow(NoSuchKeyException.builder().message("gone").build())
                .thenThrow(s3Error(404))
                .thenThrow(s3Error(503))
                .thenThrow(new IllegalStateException("socket closed"));

        var found = provider.download("files", "x.txt");
        assertThat(found.contentLength()).isEqualTo(5);
        assertThat(found.contentType()).isEqualTo("application/octet-stream");
        assertThat(provider.download("files", "x.txt")).isNull();
        assertThat(provider.download("files", "x.txt")).isNull();
        assertThatThrownBy(() -> provider.download("files", "x.txt"))
                .hasMessage("S3 storage download failed")
                .hasCauseInstanceOf(S3Exception.class);
        assertThatThrownBy(() -> provider.download("files", "x.txt"))
                .hasMessage("S3 storage download failed")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(outcome("download", "not_found")).isEqualTo(2);
        assertThat(outcome("download", "error")).isEqualTo(2);
    }

    @Test
    @DisplayName("Exists: true, false on a missing object or a 404, a storage error otherwise")
    void existsOutcomes() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build())
                .thenThrow(NoSuchKeyException.builder().message("gone").build())
                .thenThrow(s3Error(404))
                .thenThrow(s3Error(403))
                .thenThrow(new IllegalStateException("socket closed"));

        assertThat(provider.exists("files", "x.txt")).isTrue();
        assertThat(provider.exists("files", "x.txt")).isFalse();
        assertThat(provider.exists("files", "x.txt")).isFalse();
        assertThatThrownBy(() -> provider.exists("files", "x.txt")).hasMessage("S3 storage exists failed");
        assertThatThrownBy(() -> provider.exists("files", "x.txt")).hasMessage("S3 storage exists failed");
        assertThat(outcome("exists", "success")).isEqualTo(1);
    }

    @Test
    @DisplayName("Delete reports a failure of the store; health reports it as unhealthy without the cause")
    void deleteAndHealthFailures() {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Error(500));
        when(client.headBucket(any(HeadBucketRequest.class))).thenThrow(s3Error(403));

        assertThatThrownBy(() -> provider.delete("files", "x.txt"))
                .hasMessage("S3 storage delete failed")
                .hasCauseInstanceOf(S3Exception.class);
        var health = provider.checkHealth();
        assertThat(health.isHealthy()).isFalse();
        assertThat(health.toString()).doesNotContain("403");
        assertThat(outcome("health", "error")).isEqualTo(1);
    }

    private long outcome(String operation, String outcome) {
        var timer = meters.find("smc.storage.operation")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private static S3Exception s3Error(int status) {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .message("status " + status)
                .build();
    }

    private static S3StorageProperties properties() {
        var properties = new S3StorageProperties();
        properties.setEndpoint(URI.create("http://127.0.0.1:9"));
        properties.setRegion("us-east-1");
        properties.setAccessKey("test-access");
        properties.setSecretKey("test-secret");
        properties.setBucket("smartupcms-test");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
        return properties;
    }
}
