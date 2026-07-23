package org.eardream.devvault.user.service;

import lombok.RequiredArgsConstructor;
import org.eardream.devvault.user.dto.PasswordChangeRequest;
import org.eardream.devvault.user.dto.ProfileResponse;
import org.eardream.devvault.user.dto.UpdateProfileRequest;
import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ProfileResponse getProfile(String email) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        return ProfileResponse.from(user);
    }

    public String getProfileImageKey(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."))
                .getUserImage();
    }

    @Transactional
    public ProfileResponse updateProfile(String email, UpdateProfileRequest request) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if(request.username() != null) {
            user.updateUsername(request.username());
        }

        return ProfileResponse.from(user);
    }

    @Transactional
    public ProfileResponse updatePassword(String email, PasswordChangeRequest request) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if (user.isPasswordLoginEnabled()
                && (!StringUtils.hasText(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPassword()))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "현재 비밀번호가 일치하지 않습니다."
            );
        }

        user.updatePassword(passwordEncoder.encode(request.newPassword()));
        return ProfileResponse.from(user);
    }


    @Transactional
    public String replaceProfileImage(String email, String newKey) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        String oldKey = user.getUserImage();

        user.updateUserImage(newKey);

        return oldKey;
    }

    @Transactional
    public String clearProfileImage(String email) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        String oldKey = user.getUserImage();

        user.updateUserImage(null);

        return oldKey;
    }

}
