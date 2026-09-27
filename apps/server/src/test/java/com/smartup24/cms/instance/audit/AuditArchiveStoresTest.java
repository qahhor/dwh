package com.smartup24.cms.instance.audit;

import com.smartup24.cms.instance.audit.archive.AuditArchiveProperties;
import com.smartup24.cms.instance.audit.archive.LocalAuditArchiveStore;
import com.smartup24.cms.instance.audit.archive.S3AuditArchiveStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/** The two archive targets and their settings (decision of 2026-09-27: local or S3, chosen in the settings). */
class AuditArchiveStoresTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("S3: files go to the bucket under the prefix, and are deleted from there")
    void s3StoreUsesBucketAndPrefix() throws Exception {
        S3Client client = Mockito.mock(S3Client.class);
        var store = new S3AuditArchiveStore(client, "audit-bucket", "/cms/audit");
        Path file = Files.writeString(dir.resolve("a.gz"), "x");

        store.put("audit-log_2021-04-05.jsonl.gz", file);
        store.delete("audit-log_2021-04-05.jsonl.gz");

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        Mockito.verify(client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo("audit-bucket");
        assertThat(put.getValue().key()).isEqualTo("cms/audit/audit-log_2021-04-05.jsonl.gz");
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        Mockito.verify(client).deleteObject(delete.capture());
        assertThat(delete.getValue().key()).isEqualTo("cms/audit/audit-log_2021-04-05.jsonl.gz");
    }

    @Test
    @DisplayName("Local: a key cannot leave the archive directory")
    void localStoreStaysInItsDirectory() throws Exception {
        var store = new LocalAuditArchiveStore(dir.resolve("archive"));
        Path file = Files.writeString(dir.resolve("b.gz"), "y");

        assertThatThrownBy(() -> store.put("../escape.gz", file)).isInstanceOf(IllegalArgumentException.class);
        store.put("kept.gz", file);
        assertThat(dir.resolve("archive/kept.gz")).exists();
        store.delete("kept.gz");
        store.delete("kept.gz");
        assertThat(dir.resolve("archive/kept.gz")).doesNotExist();
    }

    @Test
    @DisplayName("Settings: the target is local or s3; S3 needs its settings only when chosen; keys stay out of text")
    void settingsAreChecked() {
        assertThatThrownBy(() -> properties("ftp")).hasMessageContaining("local or s3");
        assertThat(properties("S3").target()).isEqualTo("s3");

        var incomplete = new AuditArchiveProperties.S3(URI.create("https://s3.example.test"), "auto", "key", null,
                "bucket", "audit/", true);
        assertThatThrownBy(incomplete::validate).hasMessageContaining("SMC_AUDIT_ARCHIVE_S3_SECRET_KEY");
        var withCredentials = new AuditArchiveProperties.S3(URI.create("https://user:pass@s3.example.test"), "auto",
                "key", "secret", "bucket", "audit/", true);
        assertThatThrownBy(withCredentials::validate).hasMessageContaining("without credentials");

        var complete = new AuditArchiveProperties.S3(URI.create("https://s3.example.test"), "auto", "AKIA-probe",
                "secret-probe", "bucket", "audit/", true);
        assertThat(complete.toString()).doesNotContain("AKIA-probe").doesNotContain("secret-probe");
    }

    private static AuditArchiveProperties properties(String target) {
        return new AuditArchiveProperties(true, target, Path.of("archive"), Duration.ofDays(7), DataSize.ofMegabytes(100),
                Duration.ofDays(90), false, new AuditArchiveProperties.S3(null, "auto", null, null, null, "audit/", true));
    }
}
