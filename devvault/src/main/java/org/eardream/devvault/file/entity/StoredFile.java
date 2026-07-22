package org.eardream.devvault.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.eardream.devvault.file.entity.Folder;
import org.eardream.devvault.fileTag.entity.Tag;
import org.eardream.devvault.user.entity.User;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "stored_files",
        uniqueConstraints = @UniqueConstraint(name = "uk_stored_files_owner_checksum",
                columnNames = {"owner_id", "checksum"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class StoredFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private Folder folder;

    @Column(nullable = false)
    private String originalName;

    @Column(nullable = false, unique = true, length = 36)
    private String storedName;

    @Column(length = 255)
    private String contentType;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(nullable = false)
    @Builder.Default
    private boolean favorite = false;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant deletedAt;

    @ManyToMany
    @JoinTable(name = "file_tags",
            joinColumns = @JoinColumn(name = "file_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"file_id", "tag_id"}))
    @Builder.Default
    private Set<Tag> tags = new HashSet<>();

    void rename(String name) {
        this.originalName = name;
    }

    void moveTo(Folder folder) {
        this.folder = folder;
    }

    public void attachTag(Tag tag) {
        tags.add(tag);
    }

    public void detachTag(Tag tag) {
        tags.remove(tag);
    }

    public void softDelete() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
        }
    }

    void restore() {
        deletedAt = null;
    }

    void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
