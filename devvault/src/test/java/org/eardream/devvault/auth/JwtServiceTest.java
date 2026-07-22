package org.eardream.devvault.auth;

import org.eardream.devvault.auth.dto.AuthToken;
import org.eardream.devvault.auth.service.JwtService;
import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtServiceTest {

    @Test
    void createsSignedTokenWithSubjectAndRoles() {
        SecretKey key = new SecretKeySpec(
                "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtService service = new JwtService(NimbusJwtEncoder.withSecretKey(key).build(), 900_000);
        User user = User.builder().email("user@example.com").password("encoded").username("user").build();
        user.getUserRoles().add(UserRole.builder().user(user).role(new Role("ROLE_USER")).build());

        AuthToken token = service.createToken(user, "refresh-token");
        Jwt jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
                .decode(token.accessToken());

        assertEquals("user@example.com", jwt.getSubject());
        assertEquals("ROLE_USER", jwt.getClaimAsStringList("roles").get(0));
    }
}
