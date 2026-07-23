package org.eardream.devvault.admin.controller;

import lombok.RequiredArgsConstructor;
import org.eardream.devvault.admin.service.AdminService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService adminService;

    @GetMapping("/dashboard")
    AdminService.DashboardSummary dashboard(@AuthenticationPrincipal Jwt jwt) {
        return adminService.dashboard(jwt.getSubject());
    }

    @GetMapping("/users")
    Page<AdminService.UserSummary> users(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false) String query,
                                         @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return adminService.users(jwt.getSubject(), query, pageable);
    }

    @PatchMapping("/users/{id}")
    AdminService.UserSummary updateUser(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                        @RequestBody UpdateUserRequest request) {
        return adminService.updateUser(jwt.getSubject(), id, request.enabled(), request.roles());
    }

    @DeleteMapping("/users/{id}")
    ResponseEntity<Void> deleteUser(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        adminService.deleteUser(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/users/{id}/storage-quota")
    AdminService.UserSummary updateStorageQuota(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                @RequestBody UpdateStorageQuotaRequest request) {
        return adminService.updateStorageQuota(jwt.getSubject(), id, request.quotaBytes());
    }

    @GetMapping("/files")
    Page<AdminService.FileSummary> files(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false) String query,
                                         @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                         Pageable pageable) {
        return adminService.files(jwt.getSubject(), query, pageable);
    }

    @DeleteMapping("/files/{id}")
    ResponseEntity<Void> deleteFile(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        adminService.deleteFile(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/files/bulk-delete")
    BulkDeleteResponse deleteFiles(@AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody BulkDeleteRequest request) {
        return new BulkDeleteResponse(adminService.deleteFiles(jwt.getSubject(), request.fileIds()));
    }

    @GetMapping("/audit-logs")
    Page<AdminService.AuditLogSummary> auditLogs(@AuthenticationPrincipal Jwt jwt,
                                                 @PageableDefault(size = 20, sort = "createdAt",
                                                         direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.auditLogs(jwt.getSubject(), pageable);
    }

    public record UpdateUserRequest(Boolean enabled, Set<String> roles) {
    }

    public record UpdateStorageQuotaRequest(Long quotaBytes) {
    }

    public record BulkDeleteRequest(@NotEmpty @Size(max = 100) Set<@Positive Long> fileIds) {
    }

    public record BulkDeleteResponse(int deletedCount) {
    }
}
