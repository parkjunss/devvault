package org.eardream.devvault.file;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final FileStorageService fileStorageService;

    public DashboardController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    FileStorageService.DashboardSummary get(@AuthenticationPrincipal Jwt jwt) {
        return fileStorageService.dashboard(jwt.getSubject());
    }
}
