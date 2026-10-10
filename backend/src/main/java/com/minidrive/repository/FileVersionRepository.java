package com.minidrive.repository;

import com.minidrive.entity.FileVersion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileVersionRepository extends JpaRepository<FileVersion, UUID> {

    List<FileVersion> findByFileIdOrderByVersionNumberAsc(UUID fileId);

    /** History listing order: newest revision first. */
    List<FileVersion> findByFileIdOrderByVersionNumberDesc(UUID fileId);

    Optional<FileVersion> findByFileIdAndVersionNumber(UUID fileId, int versionNumber);

    /**
     * Looks a version up only within the given file. Scoping the lookup by file
     * id is what makes a version id from another file resolve to "not found"
     * rather than leaking whether that version exists.
     */
    Optional<FileVersion> findByIdAndFileId(UUID id, UUID fileId);

    Optional<FileVersion> findTopByFileIdOrderByVersionNumberDesc(UUID fileId);

    long countByFileId(UUID fileId);

    boolean existsByFileIdAndVersionNumber(UUID fileId, int versionNumber);

    /**
     * Highest recorded version number per file, resolved in a single query so
     * list endpoints can surface {@code currentVersion} without an N+1 lookup.
     */
    @Query("select v.file.id as fileId, max(v.versionNumber) as currentVersion "
            + "from FileVersion v where v.file.id in :fileIds group by v.file.id")
    List<CurrentVersionProjection> findCurrentVersions(@Param("fileIds") Collection<UUID> fileIds);

    /** Projection pairing a file with its latest version number. */
    interface CurrentVersionProjection {

        UUID getFileId();

        int getCurrentVersion();
    }
}
