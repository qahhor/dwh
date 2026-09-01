package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.support.CpPostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CpInstanceRegistrationServiceTest extends CpPostgresIntegrationSupport {

    private CpInstanceRepository repository;
    private CpInstanceCredentialService credentials;
    private CpInstanceRegistrationService service;

    @BeforeEach
    void setUp() {
        repository = mock(CpInstanceRepository.class);
        credentials = mock(CpInstanceCredentialService.class);
        service = new CpInstanceRegistrationService(repository, credentials);
    }

    @Test
    void registersOnlyTheApprovedManagedCloudPlacementAndIssuesEnrollment() {
        var command = managedCommand();
        var enrollment = new CpInstanceCredentialService.IssuedEnrollment(
                42L,
                "one-time-enrollment",
                Instant.parse("2026-09-01T00:15:00Z"));
        when(repository.findClientIdByCode("alpha")).thenReturn(Optional.of(7L));
        when(repository.create(7L, command)).thenReturn(42L);
        when(credentials.issueEnrollment(42L, 9L)).thenReturn(enrollment);

        assertThat(service.register(command, 9L)).isEqualTo(enrollment);

        verify(repository).create(7L, command);
        verify(credentials).issueEnrollment(42L, 9L);
    }

    @Test
    void customerHostedAcceptsClientSelectedNamedProvidersWithoutAnEdgeProvider() {
        var command = new CpInstanceRegistrationService.RegistrationCommand(
                "alpha",
                "production",
                URI.create("https://alpha.customer.invalid"),
                CpInstanceDeploymentMode.CUSTOMER_HOSTED,
                "UZ",
                "CUSTOMER_VMWARE",
                "CUSTOMER_MINIO",
                null,
                "CUSTOMER_HOSTED_SUPPORT");
        var enrollment = new CpInstanceCredentialService.IssuedEnrollment(
                42L,
                "one-time-enrollment",
                Instant.parse("2026-09-01T00:15:00Z"));
        when(repository.findClientIdByCode("alpha")).thenReturn(Optional.of(7L));
        when(repository.create(7L, command)).thenReturn(42L);
        when(credentials.issueEnrollment(42L, 9L)).thenReturn(enrollment);

        assertThat(service.register(command, 9L)).isEqualTo(enrollment);
    }

    @ParameterizedTest(name = "rejects invalid placement: {0}")
    @MethodSource("invalidPlacements")
    void rejectsEveryInvalidManagedOrCustomerHostedPlacement(InvalidPlacement invalid) {
        assertThatThrownBy(() -> service.register(invalid.command(), 9L))
                .isInstanceOfSatisfying(CpApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(422);
                    assertThat(error.errorCode()).isEqualTo("instance_placement_invalid");
                });

        verify(repository, never()).create(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verify(credentials, never()).issueEnrollment(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void reportsAnUnknownClientWithoutCreatingAnInstance() {
        when(repository.findClientIdByCode("alpha")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(managedCommand(), 9L))
                .isInstanceOfSatisfying(CpApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(404);
                    assertThat(error.errorCode()).isEqualTo("client_not_found");
                });
        verify(repository, never()).create(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedEnrollmentInsertRollsBackInstanceCreation() throws Exception {
        cleanAndMigrateTo("6");
        long clientId = jdbc().sql("""
                        insert into cp_clients(code, name, resource_profile)
                        values ('alpha', 'Alpha', 'S')
                        returning id
                        """)
                .query(Long.class)
                .single();
        assertThat(clientId).isPositive();
        CpInstanceRepository realRepository = new CpInstanceRepository(jdbc());
        CpInstanceCredentialService failingCredentials = mock(CpInstanceCredentialService.class);
        when(failingCredentials.issueEnrollment(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(9L)))
                .thenThrow(new IllegalStateException("simulated enrollment persistence failure"));
        CpInstanceRegistrationService transactionalService =
                new CpInstanceRegistrationService(realRepository, failingCredentials);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource()));

        assertThat(CpInstanceRegistrationService.class
                .getMethod("register", CpInstanceRegistrationService.RegistrationCommand.class, long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThatThrownBy(() -> transaction.executeWithoutResult(
                ignored -> transactionalService.register(managedCommand(), 9L)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc().sql("select count(*) from cp_instances where client_id = :clientId")
                .param("clientId", clientId)
                .query(Long.class)
                .single()).isZero();
    }

    private static Stream<InvalidPlacement> invalidPlacements() {
        return Stream.of(
                invalid("managed jurisdiction", managedCommand("US", "HETZNER", "CLOUDFLARE_R2",
                        "CLOUDFLARE", "MANAGED_995")),
                invalid("managed cloud", managedCommand("EU", "AWS", "CLOUDFLARE_R2",
                        "CLOUDFLARE", "MANAGED_995")),
                invalid("managed storage", managedCommand("EU", "HETZNER", "S3",
                        "CLOUDFLARE", "MANAGED_995")),
                invalid("managed edge", managedCommand("EU", "HETZNER", "CLOUDFLARE_R2",
                        null, "MANAGED_995")),
                invalid("managed support", managedCommand("EU", "HETZNER", "CLOUDFLARE_R2",
                        "CLOUDFLARE", "BEST_EFFORT")),
                invalid("customer jurisdiction", customerCommand(" ", "CUSTOMER_AWS", "CUSTOMER_S3",
                        null, "CUSTOMER_HOSTED_SUPPORT")),
                invalid("customer cloud", customerCommand("UZ", " ", "CUSTOMER_S3",
                        null, "CUSTOMER_HOSTED_SUPPORT")),
                invalid("customer storage", customerCommand("UZ", "CUSTOMER_AWS", " ",
                        null, "CUSTOMER_HOSTED_SUPPORT")),
                invalid("customer support", customerCommand("UZ", "CUSTOMER_AWS", "CUSTOMER_S3",
                        null, "MANAGED_995")),
                invalid("environment", new CpInstanceRegistrationService.RegistrationCommand(
                        "alpha", "qa", URI.create("https://alpha.invalid"),
                        CpInstanceDeploymentMode.MANAGED_CLOUD, "EU", "HETZNER",
                        "CLOUDFLARE_R2", "CLOUDFLARE", "MANAGED_995")),
                invalid("URL scheme", new CpInstanceRegistrationService.RegistrationCommand(
                        "alpha", "production", URI.create("file:///etc/passwd"),
                        CpInstanceDeploymentMode.MANAGED_CLOUD, "EU", "HETZNER",
                        "CLOUDFLARE_R2", "CLOUDFLARE", "MANAGED_995")));
    }

    private static CpInstanceRegistrationService.RegistrationCommand managedCommand() {
        return managedCommand("EU", "HETZNER", "CLOUDFLARE_R2", "CLOUDFLARE", "MANAGED_995");
    }

    private static CpInstanceRegistrationService.RegistrationCommand managedCommand(
            String jurisdiction,
            String cloudProvider,
            String storageProvider,
            String edgeProvider,
            String supportTier) {
        return new CpInstanceRegistrationService.RegistrationCommand(
                "alpha", "production", URI.create("https://alpha.invalid"),
                CpInstanceDeploymentMode.MANAGED_CLOUD,
                jurisdiction, cloudProvider, storageProvider, edgeProvider, supportTier);
    }

    private static CpInstanceRegistrationService.RegistrationCommand customerCommand(
            String jurisdiction,
            String cloudProvider,
            String storageProvider,
            String edgeProvider,
            String supportTier) {
        return new CpInstanceRegistrationService.RegistrationCommand(
                "alpha", "production", URI.create("https://alpha.invalid"),
                CpInstanceDeploymentMode.CUSTOMER_HOSTED,
                jurisdiction, cloudProvider, storageProvider, edgeProvider, supportTier);
    }

    private static InvalidPlacement invalid(
            String name,
            CpInstanceRegistrationService.RegistrationCommand command) {
        return new InvalidPlacement(name, command);
    }

    private record InvalidPlacement(
            String name,
            CpInstanceRegistrationService.RegistrationCommand command) {
        @Override
        public String toString() {
            return name;
        }
    }
}
