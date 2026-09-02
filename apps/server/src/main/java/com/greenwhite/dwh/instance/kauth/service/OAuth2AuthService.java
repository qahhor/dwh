package com.greenwhite.dwh.instance.kauth.service;

import com.greenwhite.dwh.instance.kauth.repository.SsoProviderRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OAuth2AuthService {

    private final SsoProviderRepository ssoProviderRepository;

    public OAuth2AuthService(SsoProviderRepository ssoProviderRepository) {
        this.ssoProviderRepository = ssoProviderRepository;
    }

    public record SsoProviderPublicDto(
            String providerId,
            String name,
            String icon,
            String authorizationUrl,
            String clientId,
            String scopes
    ) {}

    public List<SsoProviderPublicDto> getEnabledProviders() {
        return ssoProviderRepository.findEnabledProviders().stream()
                .map(p -> new SsoProviderPublicDto(
                        p.providerId(),
                        p.name(),
                        p.icon(),
                        p.authorizationUrl(),
                        p.clientId(),
                        p.scopes()
                ))
                .toList();
    }

}
