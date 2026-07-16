package org.eardream.devvault.user.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(
        name = "oauth_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_oauth_provider_user",
                columnNames = {"provider", "provider_user_id"}
        )
)
public class OauthAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(nullable = false)
    private String providerEmail;


}
