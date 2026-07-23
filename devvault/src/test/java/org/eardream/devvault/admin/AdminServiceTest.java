package org.eardream.devvault.admin;

import org.eardream.devvault.admin.entity.AdminAuditLog;
import org.eardream.devvault.admin.repository.AdminAuditLogRepository;
import org.eardream.devvault.admin.service.AdminService;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceTest {

    @Test
    void disablesUserRevokesRefreshTokensAndWritesAuditLog() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        User target = user(2L, "user@example.com");
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.users.findById(target.getId())).thenReturn(Optional.of(target));

        AdminService.UserSummary result = fixture.service.updateUser(
                admin.getEmail(), target.getId(), false, null);

        assertFalse(result.enabled());
        verify(fixture.refreshTokens).deleteAllByUserId(target.getId());
        verify(fixture.auditLogs).save(any(AdminAuditLog.class));
    }

    @Test
    void preventsRemovingTheLastAdminRole() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        Role adminRole = adminRole(admin);
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.users.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(fixture.roles.findByRoleForUpdate("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(fixture.userRoles.countByRoleRole("ROLE_ADMIN")).thenReturn(1L);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> fixture.service.updateUser(
                        admin.getEmail(), admin.getId(), null, Set.of("ROLE_USER")));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(fixture.userRoles, never()).delete(any(UserRole.class));
    }

    @Test
    void grantsAdminRoleAndWritesAuditLog() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        User target = user(2L, "user@example.com");
        Role adminRole = adminRole(admin);
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.users.findById(target.getId())).thenReturn(Optional.of(target));
        when(fixture.roles.findByRoleForUpdate("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(fixture.userRoles.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminService.UserSummary result = fixture.service.updateUser(
                admin.getEmail(), target.getId(), null, Set.of("ROLE_USER", "ROLE_ADMIN"));

        assertEquals(Set.of("ROLE_ADMIN", "ROLE_USER"), result.roles());
        verify(fixture.auditLogs).save(any(AdminAuditLog.class));
        verify(fixture.refreshTokens).deleteAllByUserId(target.getId());
    }

    @Test
    void adminReducesUserStorageQuotaDownToCurrentUsage() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        User target = user(2L, "user@example.com");
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.users.findById(target.getId())).thenReturn(Optional.of(target));
        when(fixture.files.sumStoredBytes(target.getEmail())).thenReturn(20_000_000_000L);

        AdminService.UserSummary result = fixture.service.updateStorageQuota(
                admin.getEmail(), target.getId(), 20_000_000_000L);

        assertEquals(20_000_000_000L, result.storageQuotaBytes());
        assertEquals(20_000_000_000L, result.usedBytes());
        verify(fixture.auditLogs).save(any(AdminAuditLog.class));
    }

    @Test
    void rejectsStorageQuotaBelowCurrentUsage() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        User target = user(2L, "user@example.com");
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.users.findById(target.getId())).thenReturn(Optional.of(target));
        when(fixture.files.sumStoredBytes(target.getEmail())).thenReturn(20_000_000_000L);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> fixture.service.updateStorageQuota(
                        admin.getEmail(), target.getId(), 19_999_999_999L));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void softDeletesAnyUsersFileAndWritesAuditLog() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        StoredFile file = StoredFile.builder().id(9L).owner(user(2L, "user@example.com"))
                .originalName("notes.txt").storedName("stored").checksum("checksum").build();
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.files.findById(file.getId())).thenReturn(Optional.of(file));

        fixture.service.deleteFile(admin.getEmail(), file.getId());

        verify(fixture.auditLogs).save(any(AdminAuditLog.class));
        assertTrue(file.getDeletedAt() != null);
    }

    @Test
    void deletesNonAdminAccountAndRevokesAccess() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        User target = user(2L, "user@example.com");
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.users.findById(target.getId())).thenReturn(Optional.of(target));

        fixture.service.deleteUser(admin.getEmail(), target.getId());

        assertTrue(target.isDeleted());
        verify(fixture.profileImages).delete("user@example.com");
        verify(fixture.refreshTokens).deleteAllByUserId(target.getId());
        verify(fixture.oauthAccounts).deleteAllByUserId(target.getId());
        verify(fixture.userRoles).deleteAllByUserId(target.getId());
        verify(fixture.auditLogs).save(any(AdminAuditLog.class));
    }

    @Test
    void bulkDeletesActiveFiles() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        StoredFile first = StoredFile.builder().id(9L).owner(user(2L, "user@example.com")).originalName("a.txt").build();
        StoredFile second = StoredFile.builder().id(10L).owner(user(2L, "user@example.com")).originalName("b.txt").build();
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.files.findAllById(Set.of(9L, 10L))).thenReturn(List.of(first, second));

        int deleted = fixture.service.deleteFiles(admin.getEmail(), Set.of(9L, 10L));

        assertEquals(2, deleted);
        assertTrue(first.isDeleted());
        assertTrue(second.isDeleted());
    }

    @Test
    void permanentlyDeletesOnlyTrashedFiles() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        User owner = user(2L, "user@example.com");
        StoredFile file = StoredFile.builder().id(9L).owner(owner).originalName("a.txt").build();
        StoredFile active = StoredFile.builder().id(10L).owner(owner).originalName("b.txt").build();
        file.softDelete();
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.files.findAllById(Set.of(file.getId(), active.getId()))).thenReturn(List.of(file, active));

        int deleted = fixture.service.deleteFilesPermanently(
                admin.getEmail(), Set.of(file.getId(), active.getId()));

        assertEquals(1, deleted);
        verify(fixture.fileStorage).deletePermanently(owner.getEmail(), file.getId());
        verify(fixture.fileStorage, never()).deletePermanently(owner.getEmail(), active.getId());
        verify(fixture.auditLogs).save(any(AdminAuditLog.class));
    }

    @Test
    void returnsAdminDashboardTotalsAndHealth() {
        Fixture fixture = new Fixture();
        User admin = admin(1L, "admin@example.com");
        StoredFileRepository.UsageSummary usage = mock(StoredFileRepository.UsageSummary.class);
        CompositeHealthDescriptor health = mock(CompositeHealthDescriptor.class);
        when(fixture.users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(fixture.users.countByDeletedAtIsNull()).thenReturn(5L);
        when(fixture.users.countByEnabledTrueAndDeletedAtIsNull()).thenReturn(4L);
        when(usage.getFileCount()).thenReturn(12L);
        when(usage.getUsedBytes()).thenReturn(4096L);
        when(fixture.files.summarizeAllUsage()).thenReturn(usage);
        when(health.getStatus()).thenReturn(Status.UP);
        when(fixture.health.health()).thenReturn(health);

        AdminService.DashboardSummary result = fixture.service.dashboard(admin.getEmail());

        assertEquals(5L, result.userCount());
        assertEquals(4L, result.enabledUserCount());
        assertEquals(12L, result.fileCount());
        assertEquals(4096L, result.usedBytes());
        assertEquals("UP", result.serviceStatus());
    }

    private static User admin(Long id, String email) {
        Role role = new Role("ROLE_ADMIN");
        User user = user(id, email);
        user.getUserRoles().add(UserRole.builder().user(user).role(role).build());
        return user;
    }

    private static User user(Long id, String email) {
        Role role = new Role("ROLE_USER");
        User user = User.builder().id(id).email(email).password("pw").username(email).build();
        user.getUserRoles().add(UserRole.builder().user(user).role(role).build());
        return user;
    }

    private static Role adminRole(User user) {
        return user.getUserRoles().stream()
                .map(UserRole::getRole)
                .filter(role -> role.getRole().equals("ROLE_ADMIN"))
                .findFirst()
                .orElseThrow();
    }

    private static class Fixture {
        final UserRepository users = mock(UserRepository.class);
        final RoleRepository roles = mock(RoleRepository.class);
        final UserRoleRepository userRoles = mock(UserRoleRepository.class);
        final StoredFileRepository files = mock(StoredFileRepository.class);
        final FileStorageService fileStorage = mock(FileStorageService.class);
        final AdminAuditLogRepository auditLogs = mock(AdminAuditLogRepository.class);
        final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        final OAuthAccountRepository oauthAccounts = mock(OAuthAccountRepository.class);
        final ProfileImageService profileImages = mock(ProfileImageService.class);
        final HealthEndpoint health = mock(HealthEndpoint.class);
        final AdminService service = new AdminService(
                users, roles, userRoles, files, fileStorage, auditLogs, refreshTokens, oauthAccounts, profileImages,
                health);
    }
}
