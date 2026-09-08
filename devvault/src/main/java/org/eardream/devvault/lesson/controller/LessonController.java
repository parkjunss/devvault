package org.eardream.devvault.lesson.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.eardream.devvault.lesson.entity.Lesson;
import org.eardream.devvault.lesson.entity.LessonSourceType;
import org.eardream.devvault.lesson.service.LessonService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class LessonController {
    private final LessonService lessonService;

    public LessonController(LessonService lessonService) {
        this.lessonService = lessonService;
    }

    @PostMapping("/api/sections/{sectionId}/lessons")
    ResponseEntity<LessonResponse> createWritten(@AuthenticationPrincipal Jwt jwt, @PathVariable Long sectionId,
                                                 @Valid @RequestBody CreateWrittenLessonRequest request) {
        Lesson lesson = lessonService.createWritten(jwt.getSubject(), sectionId, request.title(),
                request.contentMd(), request.orderNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(LessonResponse.from(lesson));
    }

    @PostMapping("/api/sections/{sectionId}/lessons/from-file")
    ResponseEntity<LessonResponse> createFromFile(@AuthenticationPrincipal Jwt jwt, @PathVariable Long sectionId,
                                                  @Valid @RequestBody CreateLessonFromFileRequest request) {
        Lesson lesson = lessonService.createFromFile(jwt.getSubject(), sectionId, request.storedFileId(),
                request.title(), request.orderNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(LessonResponse.from(lesson));
    }

    @GetMapping("/api/sections/{sectionId}/lessons")
    List<LessonResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long sectionId) {
        return lessonService.list(jwt.getSubject(), sectionId).stream().map(LessonResponse::from).toList();
    }

    @GetMapping("/api/lessons/{id}")
    LessonResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return LessonResponse.from(lessonService.get(jwt.getSubject(), id));
    }

    public record CreateWrittenLessonRequest(@NotBlank @Size(max = 200) String title,
                                              @NotBlank String contentMd,
                                              int orderNo) {
    }

    public record CreateLessonFromFileRequest(@NotNull @Positive Long storedFileId,
                                               @NotBlank @Size(max = 200) String title,
                                               int orderNo) {
    }

    public record LessonResponse(Long id, String title, int orderNo, String contentMd,
                                  LessonSourceType sourceType, Long sourceFileId, Instant createdAt) {
        public static LessonResponse from(Lesson lesson) {
            Long sourceFileId = lesson.getSourceFile() == null ? null : lesson.getSourceFile().getId();
            return new LessonResponse(lesson.getId(), lesson.getTitle(), lesson.getOrderNo(), lesson.getContentMd(),
                    lesson.getSourceType(), sourceFileId, lesson.getCreatedAt());
        }
    }
}
