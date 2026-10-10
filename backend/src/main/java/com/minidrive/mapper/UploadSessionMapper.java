package com.minidrive.mapper;

import com.minidrive.dto.upload.UploadSessionResponse;
import com.minidrive.dto.upload.UploadSessionStatusResponse;
import com.minidrive.entity.UploadSession;

import java.util.List;

/**
 * Maps an {@link UploadSession} to its API read models. Staging object keys are
 * never copied into a response; only session metadata and the resume map are
 * exposed.
 */
public final class UploadSessionMapper {

    private UploadSessionMapper() {
    }

    public static UploadSessionResponse toResponse(UploadSession session) {
        return new UploadSessionResponse(
                session.getId(),
                session.getFilename(),
                session.getContentType(),
                session.getFolder() != null ? session.getFolder().getId() : null,
                session.getTargetFile() != null ? session.getTargetFile().getId() : null,
                session.getTotalSizeBytes(),
                session.getChunkSizeBytes(),
                session.getTotalChunks(),
                session.getStatus().name(),
                session.getExpiresAt(),
                session.getCreatedAt());
    }

    public static UploadSessionStatusResponse toStatusResponse(
            UploadSession session,
            List<Integer> uploadedChunks) {
        return new UploadSessionStatusResponse(
                session.getId(),
                session.getFilename(),
                session.getContentType(),
                session.getTotalSizeBytes(),
                session.getChunkSizeBytes(),
                session.getTotalChunks(),
                session.getStatus().name(),
                uploadedChunks,
                session.getExpiresAt());
    }
}
