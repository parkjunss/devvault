package org.eardream.devvault.file.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "file_revisions", uniqueConstraints = @UniqueConstraint(columnNames = {"file_id", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class FileRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private StoredFile file;
    @Column(nullable = false)
    private long version;
    @Column(nullable = false, length = 36)
    private String storedName;
    @Column(nullable = false)
    private String originalName;
    private String contentType;
    @Column(nullable = false)
    private long size;
    @Column(nullable = false, length = 64)
    private String checksum;
    @Column(nullable = false)
    private Instant createdAt;

    public static FileRevision archive(StoredFile file) {
        return FileRevision.builder().file(file).version(file.getVersion()).storedName(file.getStoredName())
                .originalName(file.getOriginalName()).contentType(file.getContentType()).size(file.getSize())
                .checksum(file.getChecksum()).createdAt(file.getUpdatedAt() == null ? file.getCreatedAt() : file.getUpdatedAt()).build();
    }
}
