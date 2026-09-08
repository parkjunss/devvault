package org.eardream.devvault.course.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eardream.devvault.course.entity.Course;
import org.eardream.devvault.course.entity.CourseStatus;
import org.eardream.devvault.course.service.CourseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/courses")
public class CourseController {
    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @PostMapping
    ResponseEntity<CourseResponse> create(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody CreateCourseRequest request) {
        Course course = courseService.create(jwt.getSubject(), request.title(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(CourseResponse.from(course));
    }

    @GetMapping
    List<CourseResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return courseService.list(jwt.getSubject()).stream().map(CourseResponse::from).toList();
    }

    @GetMapping("/{id}")
    CourseResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return CourseResponse.from(courseService.get(jwt.getSubject(), id));
    }

    @PatchMapping("/{id}")
    CourseResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                          @Valid @RequestBody CreateCourseRequest request) {
        return CourseResponse.from(courseService.rename(jwt.getSubject(), id, request.title(), request.description()));
    }

    @PostMapping("/{id}/activate")
    CourseResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return CourseResponse.from(courseService.activate(jwt.getSubject(), id));
    }

    @PostMapping("/{id}/archive")
    CourseResponse archive(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return CourseResponse.from(courseService.archive(jwt.getSubject(), id));
    }

    public record CreateCourseRequest(@NotBlank @Size(max = 200) String title,
                                       @Size(max = 2000) String description) {
    }

    public record CourseResponse(Long id, String title, String description, CourseStatus status, Instant createdAt) {
        public static CourseResponse from(Course course) {
            return new CourseResponse(course.getId(), course.getTitle(), course.getDescription(),
                    course.getStatus(), course.getCreatedAt());
        }
    }
}
