package org.eardream.devvault.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User implements UserDetails {
    public static final long DEFAULT_STORAGE_QUOTA_BYTES = 50_000_000_000L;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "user_image")
    private String userImage;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "privacy_accepted_at")
    private Instant privacyAcceptedAt;

    @Column(name = "storage_quota_bytes")
    @Builder.Default
    private Long storageQuotaBytes = DEFAULT_STORAGE_QUOTA_BYTES;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "password_login_enabled")
    @Builder.Default
    private Boolean passwordLoginEnabled = true;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "user")
    @Builder.Default
    private List<UserRole> userRoles = new ArrayList<>();


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return userRoles.stream()
                .map(UserRole::getRole)
                .map(Role::getRole)
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    @Override
    public boolean isAccountNonExpired() {
        return UserDetails.super.isAccountNonExpired();
    }

    @Override
    public boolean isAccountNonLocked() {
        return UserDetails.super.isAccountNonLocked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return UserDetails.super.isCredentialsNonExpired();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void updatePassword(String password) {
        this.password = password;
        this.passwordLoginEnabled = true;
    }

    public boolean isPasswordLoginEnabled() {
        return passwordLoginEnabled == null || passwordLoginEnabled;
    }

    public void initializeOAuthPasswordLogin() {
        if (passwordLoginEnabled == null) {
            passwordLoginEnabled = false;
        }
    }

    public void updateUsername(String username) {
        this.username = username;
    }

    public void updateUserImage(String userImage) {
        this.userImage = userImage;
    }

    public long getStorageQuotaBytes() {
        return storageQuotaBytes == null ? DEFAULT_STORAGE_QUOTA_BYTES : storageQuotaBytes;
    }

    public void updateStorageQuota(long storageQuotaBytes) {
        this.storageQuotaBytes = storageQuotaBytes;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void deleteAccount() {
        if (deletedAt != null) {
            return;
        }
        String suffix = id == null ? java.util.UUID.randomUUID().toString().substring(0, 12) : id.toString();
        email = "deleted-" + suffix + "@deleted.invalid";
        username = "deleted-" + suffix;
        password = java.util.UUID.randomUUID().toString();
        userImage = null;
        enabled = false;
        deletedAt = Instant.now();
        userRoles.clear();
    }
}
