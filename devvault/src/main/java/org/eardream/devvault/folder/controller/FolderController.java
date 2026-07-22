package org.eardream.devvault.file;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eardream.devvault.file.controller.FileController;
import org.eardream.devvault.file.entity.Folder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/folders")
public class FolderController {
    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @PostMapping
    ResponseEntity<FolderResponse> create(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody CreateFolderRequest request) {
        Folder folder = folderService.create(jwt.getSubject(), request.name(), request.parentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(FolderResponse.from(folder));
    }

    @PatchMapping("/{id}")
    FolderResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                          @RequestBody UpdateFolderRequest request) {
        boolean parentChanged = request.parentId() != null;
        Long parentId = parseParentId(request.parentId());
        if (request.name() == null && !parentChanged) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "변경할 값을 입력해야 합니다.");
        }
        return FolderResponse.from(folderService.update(
                jwt.getSubject(), id, request.name(), parentChanged, parentId));
    }

    @GetMapping
    List<FolderResponse> roots(@AuthenticationPrincipal Jwt jwt) {
        return folderService.roots(jwt.getSubject()).stream().map(FolderResponse::from).toList();
    }

    @GetMapping("/{id}/children")
    FolderChildrenResponse children(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable Long id,
                                    @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                    Pageable pageable) {
        FolderService.FolderChildren children = folderService.children(jwt.getSubject(), id, pageable);
        return new FolderChildrenResponse(
                children.folders().stream().map(FolderResponse::from).toList(),
                children.files().map(FileController.FileResponse::from));
    }

    public record CreateFolderRequest(@NotBlank @Size(max = 100) String name, Long parentId) {
    }

    public record UpdateFolderRequest(String name, JsonNode parentId) {
    }

    private static Long parseParentId(JsonNode parentId) {
        if (parentId == null || parentId.isNull()) {
            return null;
        }
        if (!parentId.isIntegralNumber() || !parentId.canConvertToLong() || parentId.longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parentId는 양의 정수 또는 null이어야 합니다.");
        }
        return parentId.longValue();
    }

    public record FolderResponse(Long id, String name, Long parentId, Instant createdAt) {
        static FolderResponse from(Folder folder) {
            return new FolderResponse(folder.getId(), folder.getName(),
                    folder.getParent() == null ? null : folder.getParent().getId(), folder.getCreatedAt());
        }
    }

    public record FolderChildrenResponse(List<FolderResponse> folders,
                                         Page<FileController.FileResponse> files) {
    }
}
