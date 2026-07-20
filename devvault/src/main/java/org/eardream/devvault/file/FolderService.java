package org.eardream.devvault.file;

import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class FolderService {
    private final FolderRepository folderRepository;
    private final StoredFileRepository fileRepository;
    private final UserRepository userRepository;

    public FolderService(FolderRepository folderRepository,
                         StoredFileRepository fileRepository,
                         UserRepository userRepository) {
        this.folderRepository = folderRepository;
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Folder create(String ownerEmail, String requestedName, Long parentId) {
        String name = validateName(requestedName);
        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Folder parent = parentId == null ? null : findOwned(parentId, ownerEmail);
        if (folderRepository.existsByOwnerEmailAndParentIdAndName(ownerEmail, parentId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 폴더가 이미 있습니다.");
        }
        return folderRepository.save(Folder.builder().name(name).parent(parent).owner(owner).build());
    }

    @Transactional(readOnly = true)
    public FolderChildren children(String ownerEmail, Long folderId, Pageable pageable) {
        findOwned(folderId, ownerEmail);
        List<Folder> folders = folderRepository
                .findAllByOwnerEmailAndParentIdOrderByNameAsc(ownerEmail, folderId);
        Page<StoredFile> files = fileRepository.findAllByOwnerEmailAndFolderId(ownerEmail, folderId, pageable);
        return new FolderChildren(folders, files);
    }

    private Folder findOwned(Long folderId, String ownerEmail) {
        return folderRepository.findByIdAndOwnerEmail(folderId, ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "폴더를 찾을 수 없습니다."));
    }

    private static String validateName(String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (!StringUtils.hasText(name) || name.length() > 100 || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바르지 않은 폴더 이름입니다.");
        }
        return name;
    }

    public record FolderChildren(List<Folder> folders, Page<StoredFile> files) {
    }
}
