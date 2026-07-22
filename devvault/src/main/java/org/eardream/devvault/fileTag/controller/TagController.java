package org.eardream.devvault.fileTag.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.eardream.devvault.fileTag.service.TagService;
import org.eardream.devvault.fileTag.entity.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class TagController {
    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @PostMapping("/tags")
    ResponseEntity<TagResponse> create(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody CreateTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TagResponse.from(tagService.create(jwt.getSubject(), request.name())));
    }

    @GetMapping("/tags")
    List<TagResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return tagService.list(jwt.getSubject()).stream().map(TagResponse::from).toList();
    }

    @PostMapping("/files/{fileId}/tags")
    ResponseEntity<Void> attach(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable Long fileId,
                                @Valid @RequestBody AttachTagRequest request) {
        tagService.attach(jwt.getSubject(), fileId, request.tagId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/files/{fileId}/tags/{tagId}")
    ResponseEntity<Void> detach(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable Long fileId,
                                @PathVariable Long tagId) {
        tagService.detach(jwt.getSubject(), fileId, tagId);
        return ResponseEntity.noContent().build();
    }

    public record CreateTagRequest(@NotBlank @Size(max = 50) String name) {
    }

    public record AttachTagRequest(@NotNull @Positive Long tagId) {
    }

    public record TagResponse(Long id, String name) {
        public static TagResponse from(Tag tag) {
            return new TagResponse(tag.getId(), tag.getName());
        }
    }
}
