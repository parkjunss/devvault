package org.eardream.devvault.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.eardream.devvault.user.dto.PasswordChangeRequest;
import org.eardream.devvault.user.dto.ProfileImage;
import org.eardream.devvault.user.dto.ProfileResponse;
import org.eardream.devvault.user.dto.UpdateProfileRequest;
import org.eardream.devvault.user.service.ProfileImageService;
import org.eardream.devvault.user.service.UserService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ProfileImageService profileImageService;


    @GetMapping("/profile")
    public ProfileResponse profile(Authentication authentication) {
        String email = authentication.getName();
        return userService.getProfile(email);
    }

    @PatchMapping("/profile")
    public ProfileResponse updateProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(authentication.getName(), request);
    }

    @PatchMapping("/profile/password")
    public ProfileResponse updatePassword(
            Authentication authentication,
            @Valid @RequestBody PasswordChangeRequest request) {
        return userService.updatePassword(authentication.getName(), request);
    }

    @GetMapping("/profile/image")
    public ResponseEntity<Resource> profileImage(Authentication authentication) {
        ProfileImage image =
                profileImageService.load(authentication.getName());

        return ResponseEntity.ok()
                .contentType(image.mediaType())
                .cacheControl(CacheControl.noStore())
                .body(image.resource());
    }

    @PostMapping(
            value = "/profile/image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateProfileImage(
            Authentication authentication,
            @RequestPart("file") MultipartFile file) {

        profileImageService.replace(authentication.getName(), file);
    }

    @DeleteMapping("/profile/image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfileImage(Authentication authentication) {
        profileImageService.delete(authentication.getName());
    }








}
