package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.instance.api.CpHeartbeatRequest;
import com.greenwhite.dwh.cp.instance.api.CpHeartbeatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class CpHeartbeatService {

    private final CpHeartbeatRepository repository;
    private final ObjectMapper objectMapper;

    public CpHeartbeatService(CpHeartbeatRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CpHeartbeatResponse recordHeartbeat(CpInstancePrincipal principal,
                                               CpHeartbeatRequest request) {
        String componentHealthJson = serializeComponents(request.components());
        var state = repository.recordHeartbeat(
                principal.instanceId(),
                request,
                componentHealthJson);
        return new CpHeartbeatResponse(
                true,
                principal.instanceId(),
                state.licenseStatus(),
                state.resourceProfile(),
                state.desiredGeneration());
    }

    private String serializeComponents(CpHeartbeatRequest.ComponentHealth components) {
        if (components == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(components);
        } catch (Exception error) {
            throw new CpApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "telemetry_serialization_failed",
                    "Component telemetry could not be serialized");
        }
    }
}
