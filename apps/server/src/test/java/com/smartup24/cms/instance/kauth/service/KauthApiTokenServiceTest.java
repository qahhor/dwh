package com.smartup24.cms.instance.kauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.kauth.api.KauthPref;
import com.smartup24.cms.instance.kauth.repository.KauthApiTokenRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 4.7: only a bearer value with the current prefix is looked up as a personal API token. */
class KauthApiTokenServiceTest {

    private final KauthApiTokenRepository repository = mock(KauthApiTokenRepository.class);
    private final KauthApiTokenService service = new KauthApiTokenService(repository, mock(KauthCredentialGuard.class));

    @Test
    @DisplayName("4.7: a value with the old prefix dwh_, a blank or a null value is not looked up")
    void foreignValuesAreNotLookedUp() {
        assertThat(service.validateToken("dwh_abcdefghijklmnop")).isEmpty();
        assertThat(service.validateToken(" ")).isEmpty();
        assertThat(service.validateToken(null)).isEmpty();
        verify(repository, never()).findActiveByTokenHash(anyString());
    }

    @Test
    @DisplayName("4.7: a value with the prefix smc_ is looked up by its hash")
    void currentValuesAreLookedUpByHash() {
        String raw = KauthPref.API_TOKEN_PREFIX + "abcdefghijklmnop";
        when(repository.findActiveByTokenHash(KauthPasswordHasher.sha256(raw))).thenReturn(Optional.empty());

        assertThat(service.validateToken(raw)).isEmpty();
        verify(repository).findActiveByTokenHash(KauthPasswordHasher.sha256(raw));
    }
}
