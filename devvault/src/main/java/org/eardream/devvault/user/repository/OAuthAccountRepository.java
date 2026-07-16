package org.eardream.devvault.user.repository;

import org.eardream.devvault.user.entity.OauthAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OAuthAccountRepository extends JpaRepository<OauthAccount,Long> {

    Optional<OauthAccount> findByProviderAndProviderUserId(
            String provider,
            String providerUserId
    );

}
