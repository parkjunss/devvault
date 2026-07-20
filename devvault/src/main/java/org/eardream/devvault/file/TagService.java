package org.eardream.devvault.file;

import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class TagService {
    private final TagRepository tagRepository;
    private final StoredFileRepository fileRepository;
    private final UserRepository userRepository;

    public TagService(TagRepository tagRepository,
                      StoredFileRepository fileRepository,
                      UserRepository userRepository) {
        this.tagRepository = tagRepository;
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Tag create(String ownerEmail, String requestedName) {
        String name = validateName(requestedName);
        if (tagRepository.existsByOwnerEmailAndName(ownerEmail, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 태그가 이미 있습니다.");
        }
        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return tagRepository.save(Tag.builder().name(name).owner(owner).build());
    }

    @Transactional(readOnly = true)
    public List<Tag> list(String ownerEmail) {
        return tagRepository.findAllByOwnerEmailOrderByNameAsc(ownerEmail);
    }

    @Transactional
    public void attach(String ownerEmail, Long fileId, Long tagId) {
        StoredFile file = fileRepository.findByIdAndOwnerEmail(fileId, ownerEmail)
                .orElseThrow(TagService::notFound);
        Tag tag = tagRepository.findByIdAndOwnerEmail(tagId, ownerEmail)
                .orElseThrow(TagService::notFound);
        file.attachTag(tag);
    }

    private static String validateName(String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (!StringUtils.hasText(name) || name.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바르지 않은 태그 이름입니다.");
        }
        return name;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "파일 또는 태그를 찾을 수 없습니다.");
    }
}
