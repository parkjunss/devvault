package org.eardream.devvault.auth.service;

import lombok.RequiredArgsConstructor;
import org.eardream.devvault.auth.dto.AuthToken;
import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.entity.UserRole;
import org.eardream.devvault.user.repository.RoleRepository;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.user.repository.UserRoleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public AuthToken signup(String email, String password, String username) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        User user = userRepository.save(User.builder()
                .email(normalizedEmail)
                .password(passwordEncoder.encode(password))
                .username(username.trim())
                .termsAcceptedAt(Instant.now())
                .privacyAcceptedAt(Instant.now())
                .build());
        Role role = roleRepository.findByRole("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("ROLE_USER가 초기화되지 않았습니다."));
        UserRole userRole = userRoleRepository.save(UserRole.builder().user(user).role(role).build());
        user.getUserRoles().add(userRole);
        return createTokens(user);
    }

    public AuthToken login(String email, String password) {
        User user = (User) authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        email.trim().toLowerCase(Locale.ROOT), password))
                .getPrincipal();
        return createTokens(user);
    }

    public AuthToken refresh(String refreshToken) {
        RefreshTokenService.RotatedToken rotated = refreshTokenService.rotate(refreshToken);
        return jwtService.createToken(rotated.user(), rotated.refreshToken());
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    public AuthToken createTokens(User user) {
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "비활성화된 사용자입니다.");
        }
        return jwtService.createToken(user, refreshTokenService.issue(user));
    }
}
