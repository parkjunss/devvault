package org.eardream.devvault.user;

import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleInitializerTest {

    @Mock
    private RoleRepository roleRepository;

    @InjectMocks
    private RoleInitializer roleInitializer;

    @Test
    void createsOnlyMissingRoles() {
        when(roleRepository.findByRole("ROLE_USER"))
                .thenReturn(Optional.of(new Role("ROLE_USER")));
        when(roleRepository.findByRole("ROLE_ADMIN"))
                .thenReturn(Optional.empty());

        roleInitializer.run(null);

        verify(roleRepository, never()).save(argThat(role -> role.getRole().equals("ROLE_USER")));
        verify(roleRepository).save(argThat(role -> role.getRole().equals("ROLE_ADMIN")));
    }
}
