package com.greenwhite.dwh.cp.controller;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.repository.CpAnnouncementRepository;
import com.greenwhite.dwh.cp.security.CpRequiresRole;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Объявления платформы (FR-CP-5): черновик → публикация → архив. */
@RestController
@RequestMapping("/api/v1/announcements")
public class CpAnnouncementController {

    private final CpAnnouncementRepository repository;

    public CpAnnouncementController(CpAnnouncementRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @CpRequiresRole({CpPref.ROLE_EDITOR, CpPref.ROLE_ENGINEER})
    public ResponseEntity<List<CpAnnouncementRepository.Announcement>> list() {
        return ResponseEntity.ok(repository.list());
    }

    @PostMapping
    @CpRequiresRole({CpPref.ROLE_EDITOR})
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateDto body) {
        var contents = body.contents().stream().collect(Collectors.toMap(
                ContentDto::language,
                c -> new CpAnnouncementRepository.ContentDto(c.title(), c.body())));
        Long id = repository.create(body.bannerType() != null ? body.bannerType() : "info",
                contents, body.targetClientIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "state", "draft"));
    }

    @PostMapping("/{id}/publish")
    @CpRequiresRole({CpPref.ROLE_EDITOR})
    @Transactional
    public ResponseEntity<Void> publish(@PathVariable("id") Long id) {
        repository.setState(id, "published");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/archive")
    @CpRequiresRole({CpPref.ROLE_EDITOR})
    @Transactional
    public ResponseEntity<Void> archive(@PathVariable("id") Long id) {
        repository.setState(id, "archived");
        return ResponseEntity.noContent().build();
    }

    public record ContentDto(@NotBlank String language, @NotBlank String title, @NotBlank String body) {}

    public record CreateDto(String bannerType, List<ContentDto> contents, List<Long> targetClientIds) {}
}
