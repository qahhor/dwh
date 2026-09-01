package com.greenwhite.dwh.cp.deployment;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.security.CpRequiresRole;
import com.greenwhite.dwh.cp.security.CpSecurityContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/instances")
public class CpTargetController {

    private final CpTargetService service;

    public CpTargetController(CpTargetService service) {
        this.service = service;
    }

    @PutMapping("/{instanceId}/target")
    @CpRequiresRole(CpPref.ROLE_ADMIN)
    public ResponseEntity<CpTarget> assign(
            @PathVariable long instanceId,
            @Valid @RequestBody AssignTargetCommand command) {
        Long actorUserId = CpSecurityContext.currentUserId();
        if (actorUserId == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Control plane login is required");
        }
        return ResponseEntity.ok(service.assign(instanceId, command, actorUserId));
    }
}
