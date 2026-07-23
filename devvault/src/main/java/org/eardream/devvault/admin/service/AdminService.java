package org.eardream.devvault.admin.service;

import lombok.RequiredArgsConstructor;
import org.eardream.devvault.admin.entity.AdminAuditLog;
import org.eardream.devvault.admin.repository.AdminAuditLogRepository;
import org.eardream.devvault.auth.repository.RefreshTokenRepository;
import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.file.repository.StoredFileRepository;
import org.eardream.devvault.file.service.FileStorageService;
import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.entity.UserRole;
import org.eardream.devvault.user.repository.RoleRepository;
import org.eardream.devvault.user.repository.OAuthAccountRepository;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.user.repository.UserRoleRepository;
import org.eardream.devvault.user.service.ProfileImageService;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class AdminService {
    private static final String ROLE_USER = "ROLE_USER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final Set<String> ALLOWED_ROLES = Set.of(ROLE_USER, ROLE_ADMIN);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final StoredFileRepository storedFileRepository;
    private final FileStorageService fileStorageService;
    private final AdminAuditLogRepository auditLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final ProfileImageService profileImageService;
    private final HealthEndpoint healthEndpoint;

    @Transactional(readOnly = true)
    public DashboardSummary dashboard(String adminEmail) {
        currentAdmin(adminEmail);
        StoredFileRepository.UsageSummary usage = storedFileRepository.summarizeAllUsage();
        return new DashboardSummary(userRepository.countByDeletedAtIsNull(),
                userRepository.countByEnabledTrueAndDeletedAtIsNull(),
                usage.getFileCount(), usage.getUsedBytes(), healthEndpoint.health().getStatus().getCode());
    }

    @Transactional(readOnly = true)
    public Page<UserSummary> users(String adminEmail, String query, Pageable pageable) {
        currentAdmin(adminEmail);
        // ponytail: admin pages are capped at 100 rows; use a grouped usage query if that limit grows.
        return userRepository.search(normalizeQuery(query), pageable).map(this::toUserSummary);
    }

    @Transactional
    public UserSummary updateUser(String adminEmail, Long userId, Boolean enabled, Set<String> requestedRoles) {
        User admin = currentAdmin(adminEmail);
        if (enabled == null && requestedRoles == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "변경할 값을 입력해야 합니다.");
        }
        User target = userRepository.findById(userId).orElseThrow(AdminService::userNotFound);
        Set<String> currentRoles = rolesOf(target);
        Set<String> finalRoles = requestedRoles == null ? currentRoles : normalizeRoles(requestedRoles);
        boolean finalEnabled = enabled == null ? target.isEnabled() : enabled;
        Role adminRole = currentRoles.contains(ROLE_ADMIN) || finalRoles.contains(ROLE_ADMIN)
                ? roleRepository.findByRoleForUpdate(ROLE_ADMIN)
                        .orElseThrow(() -> new IllegalStateException("ROLE_ADMIN이 초기화되지 않았습니다."))
                : null;

        if (currentRoles.contains(ROLE_ADMIN) && !finalRoles.contains(ROLE_ADMIN)
                && userRoleRepository.countByRoleRole(ROLE_ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "마지막 관리자 권한은 제거할 수 없습니다.");
        }
        if (target.isEnabled() && currentRoles.contains(ROLE_ADMIN) && !finalEnabled
                && userRoleRepository.countEnabledByRole(ROLE_ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "마지막 활성 관리자는 비활성화할 수 없습니다.");
        }

        boolean changed = false;
        if (enabled != null && target.isEnabled() != enabled) {
            boolean before = target.isEnabled();
            target.setEnabled(enabled);
            audit(admin, "UPDATE_USER_STATUS", "USER", target.getId(), before + " -> " + enabled);
            changed = true;
        }
        if (requestedRoles != null && !currentRoles.equals(finalRoles)) {
            target.getUserRoles().removeIf(userRole -> {
                if (finalRoles.contains(userRole.getRole().getRole())) {
                    return false;
                }
                userRoleRepository.delete(userRole);
                return true;
            });
            Set<String> remaining = rolesOf(target);
            for (String roleName : finalRoles) {
                if (!remaining.contains(roleName)) {
                    Role role = roleName.equals(ROLE_ADMIN) ? adminRole : roleRepository.findByRole(roleName)
                            .orElseThrow(() -> new IllegalStateException(roleName + "이 초기화되지 않았습니다."));
                    target.getUserRoles().add(userRoleRepository.save(
                            UserRole.builder().user(target).role(role).build()));
                }
            }
            audit(admin, "UPDATE_USER_ROLES", "USER", target.getId(), currentRoles + " -> " + finalRoles);
            changed = true;
        }
        if (changed) {
            refreshTokenRepository.deleteAllByUserId(target.getId());
        }
        return toUserSummary(target);
    }

    @Transactional
    public UserSummary updateStorageQuota(String adminEmail, Long userId, Long quotaBytes) {
        User admin = currentAdmin(adminEmail);
        User target = userRepository.findById(userId).orElseThrow(AdminService::userNotFound);
        long currentQuota = target.getStorageQuotaBytes();
        long usedBytes = storedFileRepository.sumStoredBytes(target.getEmail());
        if (quotaBytes == null || quotaBytes < usedBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "저장소 한도는 현재 사용량보다 작게 지정할 수 없습니다.");
        }
        target.updateStorageQuota(quotaBytes);
        audit(admin, "UPDATE_STORAGE_QUOTA", "USER", target.getId(), currentQuota + " -> " + quotaBytes);
        return toUserSummary(target);
    }

    @Transactional
    public void deleteUser(String adminEmail, Long userId) {
        User admin = currentAdmin(adminEmail);
        User target = userRepository.findById(userId)
                .filter(user -> !user.isDeleted())
                .orElseThrow(AdminService::userNotFound);
        if (rolesOf(target).contains(ROLE_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "관리자 권한을 먼저 해제한 뒤 계정을 삭제해 주세요.");
        }

        String originalEmail = target.getEmail();
        refreshTokenRepository.deleteAllByUserId(target.getId());
        oauthAccountRepository.deleteAllByUserId(target.getId());
        userRoleRepository.deleteAllByUserId(target.getId());
        audit(admin, "DELETE_USER", "USER", target.getId(), originalEmail);
        profileImageService.delete(originalEmail);
        target.deleteAccount();
    }

    @Transactional(readOnly = true)
    public Page<FileSummary> files(String adminEmail, String query, Pageable pageable) {
        currentAdmin(adminEmail);
        return storedFileRepository.searchAll(normalizeQuery(query), pageable).map(AdminService::toFileSummary);
    }

    @Transactional
    public void deleteFile(String adminEmail, Long fileId) {
        User admin = currentAdmin(adminEmail);
        StoredFile file = storedFileRepository.findById(fileId)
                .filter(storedFile -> storedFile.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."));
        file.softDelete();
        audit(admin, "DELETE_FILE", "FILE", file.getId(), file.getOwner().getEmail() + ":" + file.getOriginalName());
    }

    @Transactional
    public int deleteFiles(String adminEmail, Set<Long> fileIds) {
        User admin = currentAdmin(adminEmail);
        validateFileIds(fileIds);
        int deleted = 0;
        for (StoredFile file : storedFileRepository.findAllById(fileIds)) {
            if (file.isDeleted()) {
                continue;
            }
            file.softDelete();
            audit(admin, "DELETE_FILE", "FILE", file.getId(),
                    file.getOwner().getEmail() + ":" + file.getOriginalName());
            deleted++;
        }
        return deleted;
    }

    @Transactional
    public int deleteFilesPermanently(String adminEmail, Set<Long> fileIds) {
        User admin = currentAdmin(adminEmail);
        validateFileIds(fileIds);
        int deleted = 0;
        for (StoredFile file : storedFileRepository.findAllById(fileIds)) {
            if (!file.isDeleted()) {
                continue;
            }
            audit(admin, "PERMANENT_DELETE_FILE", "FILE", file.getId(),
                    file.getOwner().getEmail() + ":" + file.getOriginalName());
            fileStorageService.deletePermanently(file.getOwner().getEmail(), file.getId());
            deleted++;
        }
        return deleted;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogSummary> auditLogs(String adminEmail, Pageable pageable) {
        currentAdmin(adminEmail);
        return auditLogRepository.findAll(pageable).map(AdminService::toAuditLogSummary);
    }

    private User currentAdmin(String email) {
        return userRepository.findByEmail(email)
                .filter(User::isEnabled)
                .filter(user -> rolesOf(user).contains(ROLE_ADMIN))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."));
    }

    private void audit(User admin, String action, String targetType, Long targetId, String details) {
        auditLogRepository.save(AdminAuditLog.builder()
                .admin(admin)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .details(details)
                .build());
    }

    private static Set<String> normalizeRoles(Set<String> roles) {
        Set<String> normalized = new HashSet<>();
        for (String role : roles) {
            if (role == null) {
                throw invalidRoles();
            }
            normalized.add(role.trim().toUpperCase(Locale.ROOT));
        }
        if (!normalized.contains(ROLE_USER) || !ALLOWED_ROLES.containsAll(normalized)) {
            throw invalidRoles();
        }
        return normalized;
    }

    private static void validateFileIds(Set<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty() || fileIds.size() > 100
                || fileIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "삭제할 파일을 1개 이상 100개 이하로 선택해 주세요.");
        }
    }

    private static Set<String> rolesOf(User user) {
        Set<String> roles = new TreeSet<>();
        user.getUserRoles().forEach(userRole -> roles.add(userRole.getRole().getRole()));
        return roles;
    }

    private static String normalizeQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어가 너무 깁니다.");
        }
        return normalized;
    }

    private UserSummary toUserSummary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getUsername(), user.isEnabled(),
                rolesOf(user), user.getStorageQuotaBytes(), storedFileRepository.sumStoredBytes(user.getEmail()),
                user.getDeletedAt());
    }

    private static FileSummary toFileSummary(StoredFile file) {
        return new FileSummary(file.getId(), file.getOwner().getEmail(), file.getOriginalName(), file.getContentType(),
                file.getSize(), file.getChecksum(), file.getCreatedAt(), file.getDeletedAt());
    }

    private static AuditLogSummary toAuditLogSummary(AdminAuditLog log) {
        return new AuditLogSummary(log.getId(), log.getAdmin().getEmail(), log.getAction(), log.getTargetType(),
                log.getTargetId(), log.getDetails(), log.getCreatedAt());
    }

    private static ResponseStatusException invalidRoles() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "역할은 ROLE_USER와 ROLE_ADMIN만 지정할 수 있습니다.");
    }

    private static ResponseStatusException userNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");
    }

    public record DashboardSummary(long userCount, long enabledUserCount, long fileCount, long usedBytes,
                                   String serviceStatus) {
    }

    public record UserSummary(Long id, String email, String username, boolean enabled, Set<String> roles,
                              long storageQuotaBytes, long usedBytes, Instant deletedAt) {
    }

    public record FileSummary(Long id, String ownerEmail, String originalName, String contentType, long size,
                              String checksum, Instant createdAt, Instant deletedAt) {
    }

    public record AuditLogSummary(Long id, String adminEmail, String action, String targetType, Long targetId,
                                  String details, Instant createdAt) {
    }
}
