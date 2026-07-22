package org.eardream.devvault.auth.service;

import lombok.RequiredArgsConstructor;
import org.eardream.devvault.user.entity.OauthAccount;
import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.entity.UserRole;
import org.eardream.devvault.user.repository.OAuthAccountRepository;
import org.eardream.devvault.user.repository.RoleRepository;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.user.repository.UserRoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class GoogleOAuthService {

    private static final String PROVIDER = "google";

    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User login(OAuth2User principal) {
        String providerUserId = required(principal, "sub");
        String email = required(principal, "email").trim().toLowerCase(Locale.ROOT);
        if (!Boolean.TRUE.equals(principal.getAttribute("email_verified")) || email.length() > 50) {
            throw invalidUserInfo();
        }

        User user = oauthAccountRepository.findByProviderAndProviderUserId(PROVIDER, providerUserId)
                .map(account -> userRepository.findById(account.getUserId())
                        .orElseThrow(GoogleOAuthService::invalidUserInfo))
                .orElseGet(() -> linkAccount(principal, providerUserId, email));
        if (!user.isEnabled()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("account_disabled"), "비활성화된 사용자입니다.");
        }
        return user;
    }

    private User linkAccount(OAuth2User principal, String providerUserId, String email) {
        User user = userRepository.findByEmail(email).orElseGet(() -> createUser(principal, email));
        oauthAccountRepository.save(OauthAccount.builder()
                .userId(user.getId())
                .provider(PROVIDER)
                .providerUserId(providerUserId)
                .providerEmail(email)
                .build());
        return user;
    }

    private User createUser(OAuth2User principal, String email) {
        String name = principal.getAttribute("name");
        if (name == null || name.isBlank()) {
            name = email.substring(0, email.indexOf('@'));
        }
        if (name.length() > 50) {
            name = name.substring(0, 50);
        }

        Instant acceptedAt = Instant.now();
        User user = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .username(name)
                .termsAcceptedAt(acceptedAt)
                .privacyAcceptedAt(acceptedAt)
                .build());
        Role role = roleRepository.findByRole("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("ROLE_USER가 초기화되지 않았습니다."));
        UserRole userRole = userRoleRepository.save(UserRole.builder().user(user).role(role).build());
        user.getUserRoles().add(userRole);
        return user;
    }

    private static String required(OAuth2User principal, String attribute) {
        String value = principal.getAttribute(attribute);
        if (value == null || value.isBlank()) {
            throw invalidUserInfo();
        }
        return value;
    }

    private static OAuth2AuthenticationException invalidUserInfo() {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_user_info"), "Google 사용자 정보를 확인할 수 없습니다.");
    }
}
