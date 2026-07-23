package org.eardream.devvault.user;

import org.eardream.devvault.user.entity.Role;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.entity.UserRole;
import org.eardream.devvault.user.repository.RoleRepository;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.user.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleInitializerTest {

    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void createsOnlyMissingRoles() {
        RoleInitializer roleInitializer = initializer("");
        when(roleRepository.findByRole("ROLE_USER"))
                .thenReturn(Optional.of(new Role("ROLE_USER")));
        when(roleRepository.findByRole("ROLE_ADMIN"))
                .thenReturn(Optional.empty());

        roleInitializer.run(null);

        verify(roleRepository, never()).save(argThat(role -> role.getRole().equals("ROLE_USER")));
        verify(roleRepository).save(argThat(role -> role.getRole().equals("ROLE_ADMIN")));
    }

    @Test
    void grantsAdminRoleToConfiguredExistingUser() {
        Role adminRole = new Role("ROLE_ADMIN");
        User user = User.builder().id(7L).email("admin@example.com").password("pw").username("admin").build();
        when(roleRepository.findByRole("ROLE_USER")).thenReturn(Optional.of(new Role("ROLE_USER")));
        when(roleRepository.findByRole("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

        initializer(" Admin@Example.com ").run(null);

        verify(userRoleRepository).save(argThat(userRole ->
                userRole.getUser().equals(user) && userRole.getRole().equals(adminRole)));
    }

    @Test
    void doesNotDuplicateExistingAdminRole() {
        Role adminRole = new Role("ROLE_ADMIN");
        User user = User.builder().id(7L).email("admin@example.com").password("pw").username("admin").build();
        user.getUserRoles().add(UserRole.builder().user(user).role(adminRole).build());
        when(roleRepository.findByRole("ROLE_USER")).thenReturn(Optional.of(new Role("ROLE_USER")));
        when(roleRepository.findByRole("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));

        initializer("admin@example.com").run(null);

        verify(userRoleRepository, never()).save(any());
    }

    @Test
    void createsConfiguredAdminAccountWithBothRoles() {
        Role userRole = new Role("ROLE_USER");
        Role adminRole = new Role("ROLE_ADMIN");
        when(roleRepository.findByRole("ROLE_USER")).thenReturn(Optional.of(userRole));
        when(roleRepository.findByRole("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secure-password")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

        initializer(" Admin@Example.com ", "secure-password", "관리자").run(null);

        verify(userRepository).save(argThat(user -> user.getEmail().equals("admin@example.com")
                && user.getPassword().equals("encoded-password") && user.getUsername().equals("관리자")));
        verify(userRoleRepository).save(argThat(userRoleLink -> userRoleLink.getRole().equals(userRole)));
        verify(userRoleRepository).save(argThat(userRoleLink -> userRoleLink.getRole().equals(adminRole)));
    }

    private RoleInitializer initializer(String adminEmail) {
        return initializer(adminEmail, "", "관리자");
    }

    private RoleInitializer initializer(String adminEmail, String adminPassword, String adminUsername) {
        return new RoleInitializer(roleRepository, userRepository, userRoleRepository, passwordEncoder,
                adminEmail, adminPassword, adminUsername);
    }
}
