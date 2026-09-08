package org.eardream.devvault.section.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eardream.devvault.section.entity.Section;
import org.eardream.devvault.section.service.SectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class SectionController {
    private final SectionService sectionService;

    public SectionController(SectionService sectionService) {
        this.sectionService = sectionService;
    }

    @PostMapping("/api/courses/{courseId}/sections")
    ResponseEntity<SectionResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable Long courseId,
                                           @Valid @RequestBody CreateSectionRequest request) {
        Section section = sectionService.create(jwt.getSubject(), courseId, request.title(), request.orderNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(SectionResponse.from(section));
    }

    @GetMapping("/api/courses/{courseId}/sections")
    List<SectionResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long courseId) {
        return sectionService.list(jwt.getSubject(), courseId).stream().map(SectionResponse::from).toList();
    }

    @PatchMapping("/api/sections/{id}")
    SectionResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                           @Valid @RequestBody CreateSectionRequest request) {
        return SectionResponse.from(sectionService.rename(jwt.getSubject(), id, request.title()));
    }

    public record CreateSectionRequest(@NotBlank @Size(max = 200) String title, int orderNo) {
    }

    public record SectionResponse(Long id, String title, int orderNo, Instant createdAt) {
        public static SectionResponse from(Section section) {
            return new SectionResponse(section.getId(), section.getTitle(), section.getOrderNo(), section.getCreatedAt());
        }
    }
}
