package com.smartup24.cms.instance.upl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.dwh.FndRawWriter;
import com.smartup24.cms.instance.fnd.load.FndLoadService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.parse.UplXlsxParser;
import com.smartup24.cms.instance.upl.upload.UplApplyJob;
import com.smartup24.cms.instance.upl.upload.UplPackageRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** Plan 10/10, item 3.9: a queued apply with broken arguments or a vanished package fails loudly, so it retries. */
class UplApplyJobArgumentsTest {

    private final UplPackageRepository repo = mock(UplPackageRepository.class);
    private final UplApplyJob job = new UplApplyJob(
            repo,
            mock(UplSourceService.class),
            mock(MfFileService.class),
            mock(UplXlsxParser.class),
            mock(FndLoadService.class),
            mock(FndRawWriter.class),
            mock(FndActors.class),
            mock(TransactionTemplate.class));

    @Test
    void brokenArgumentsAreRefused() {
        assertThatThrownBy(() -> job.run(Map.of())).hasMessageContaining("packageId");
        assertThatThrownBy(() -> job.run(Map.of("packageId", "not-a-uuid"))).hasMessageContaining("packageId");
        assertThatThrownBy(() -> job.run(Map.of("packageId", UUID.randomUUID().toString())))
                .hasMessageContaining("userId");
    }

    @Test
    void vanishedPackageIsRefused() {
        when(repo.findByPublicId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> job.run(Map.of("packageId", UUID.randomUUID().toString(), "userId", 7L)))
                .isInstanceOf(IllegalStateException.class);
    }
}
