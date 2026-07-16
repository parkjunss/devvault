package org.eardream.devvault.user;

import lombok.RequiredArgsConstructor;
import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.repository.RoleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RoleInitializer implements ApplicationRunner {

    private final RoleRepository roleRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        saveIfAbsent("ROLE_USER");
        saveIfAbsent("ROLE_ADMIN");
    }

    private void saveIfAbsent(String role) {
        roleRepository.findByRole(role)
                .orElseGet(() -> roleRepository.save(new Role(role)));
    }
}
