package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Set;

@Service
public class CpInstanceRegistrationService {

    private static final Set<String> ENVIRONMENTS = Set.of("production", "staging", "dev");

    private final CpInstanceRepository repository;
    private final CpInstanceCredentialService credentials;

    public CpInstanceRegistrationService(CpInstanceRepository repository,
                                         CpInstanceCredentialService credentials) {
        this.repository = repository;
        this.credentials = credentials;
    }

    @Transactional
    public CpInstanceCredentialService.IssuedEnrollment register(
            RegistrationCommand command,
            long actorUserId) {
        validatePlacement(command);
        long clientId = repository.findClientIdByCode(command.clientCode())
                .orElseThrow(() -> new CpApiException(
                        HttpStatus.NOT_FOUND,
                        "client_not_found",
                        "Client was not found"));
        long instanceId = repository.create(clientId, command);
        return credentials.issueEnrollment(instanceId, actorUserId);
    }

    private static void validatePlacement(RegistrationCommand command) {
        if (command == null
                || isBlank(command.clientCode())
                || !ENVIRONMENTS.contains(command.environment())
                || !isHttpUrl(command.url())
                || command.deploymentMode() == null) {
            throw invalidPlacement();
        }

        boolean valid = switch (command.deploymentMode()) {
            case MANAGED_CLOUD ->
                    "EU".equals(command.jurisdiction())
                            && "HETZNER".equals(command.cloudProvider())
                            && "CLOUDFLARE_R2".equals(command.storageProvider())
                            && "CLOUDFLARE".equals(command.edgeProvider())
                            && "MANAGED_995".equals(command.supportTier());
            case CUSTOMER_HOSTED ->
                    isNamedValue(command.jurisdiction())
                            && isNamedValue(command.cloudProvider())
                            && isNamedValue(command.storageProvider())
                            && (command.edgeProvider() == null || isNamedValue(command.edgeProvider()))
                            && "CUSTOMER_HOSTED_SUPPORT".equals(command.supportTier());
        };
        if (!valid) {
            throw invalidPlacement();
        }
    }

    private static boolean isHttpUrl(URI uri) {
        return uri != null
                && uri.isAbsolute()
                && uri.getHost() != null
                && ("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()));
    }

    private static boolean isNamedValue(String value) {
        return !isBlank(value) && value.length() <= 64;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static CpApiException invalidPlacement() {
        return new CpApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "instance_placement_invalid",
                "Instance deployment placement is invalid");
    }

    public record RegistrationCommand(
            String clientCode,
            String environment,
            URI url,
            CpInstanceDeploymentMode deploymentMode,
            String jurisdiction,
            String cloudProvider,
            String storageProvider,
            String edgeProvider,
            String supportTier) {
    }
}
