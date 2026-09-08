package org.eardream.devvault.lesson.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.section.entity.Section;

import java.time.Instant;

@Entity
@Table(name = "lessons")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Lesson {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false)
    private Section section;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "order_no", nullable = false)
    private int orderNo;

    @Lob
    @Column(name = "content_md", nullable = false, columnDefinition = "LONGTEXT")
    private String contentMd;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private LessonSourceType sourceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_file_id")
    private StoredFile sourceFile;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public void rewrite(String title, String contentMd) {
        this.title = title;
        this.contentMd = contentMd;
    }

    public void reorder(int orderNo) {
        this.orderNo = orderNo;
    }
}
