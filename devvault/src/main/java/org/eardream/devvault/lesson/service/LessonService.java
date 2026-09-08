package org.eardream.devvault.lesson.service;

import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.file.service.FileStorageService;
import org.eardream.devvault.lesson.entity.Lesson;
import org.eardream.devvault.lesson.entity.LessonSourceType;
import org.eardream.devvault.lesson.repository.LessonRepository;
import org.eardream.devvault.section.entity.Section;
import org.eardream.devvault.section.repository.SectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class LessonService {
    private final LessonRepository lessonRepository;
    private final SectionRepository sectionRepository;
    private final FileStorageService fileStorageService;
    private final TextExtractionService textExtractionService;

    public LessonService(LessonRepository lessonRepository,
                         SectionRepository sectionRepository,
                         FileStorageService fileStorageService,
                         TextExtractionService textExtractionService) {
        this.lessonRepository = lessonRepository;
        this.sectionRepository = sectionRepository;
        this.fileStorageService = fileStorageService;
        this.textExtractionService = textExtractionService;
    }

    @Transactional
    public Lesson createWritten(String ownerEmail, Long sectionId, String title, String contentMd, int orderNo) {
        Section section = ownedSection(ownerEmail, sectionId);
        if (!StringUtils.hasText(contentMd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "교재 내용이 비어 있습니다.");
        }
        return lessonRepository.save(Lesson.builder()
                .section(section)
                .title(validateTitle(title))
                .orderNo(orderNo)
                .contentMd(contentMd)
                .sourceType(LessonSourceType.WRITTEN)
                .build());
    }

    @Transactional
    public Lesson createFromFile(String ownerEmail, Long sectionId, Long storedFileId, String title, int orderNo) {
        Section section = ownedSection(ownerEmail, sectionId);
        FileStorageService.StoredDownload download = fileStorageService.download(ownerEmail, storedFileId);
        StoredFile storedFile = download.metadata();
        String contentMd = textExtractionService.extract(download.path(), storedFile.getContentType(), storedFile.getOriginalName());
        if (!StringUtils.hasText(contentMd)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "파일에서 텍스트를 추출하지 못했습니다.");
        }
        return lessonRepository.save(Lesson.builder()
                .section(section)
                .title(validateTitle(title))
                .orderNo(orderNo)
                .contentMd(contentMd)
                .sourceType(LessonSourceType.UPLOAD)
                .sourceFile(storedFile)
                .extractedAt(Instant.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<Lesson> list(String ownerEmail, Long sectionId) {
        ownedSection(ownerEmail, sectionId);
        return lessonRepository.findAllBySectionIdOrderByOrderNoAsc(sectionId);
    }

    @Transactional(readOnly = true)
    public Lesson get(String ownerEmail, Long lessonId) {
        return owned(ownerEmail, lessonId);
    }

    private Lesson owned(String ownerEmail, Long lessonId) {
        return lessonRepository.findByIdAndSectionCourseOwnerEmail(lessonId, ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "교재를 찾을 수 없습니다."));
    }

    private Section ownedSection(String ownerEmail, Long sectionId) {
        return sectionRepository.findByIdAndCourseOwnerEmail(sectionId, ownerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "주차를 찾을 수 없습니다."));
    }

    private static String validateTitle(String requestedTitle) {
        String title = requestedTitle == null ? "" : requestedTitle.trim();
        if (!StringUtils.hasText(title) || title.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바르지 않은 교재 제목입니다.");
        }
        return title;
    }
}
