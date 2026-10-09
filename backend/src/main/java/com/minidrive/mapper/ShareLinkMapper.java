package com.minidrive.mapper;

import com.minidrive.dto.sharelink.CreatedShareLinkResponse;
import com.minidrive.dto.sharelink.ShareLinkResponse;
import com.minidrive.dto.sharelink.SharedFileResponse;
import com.minidrive.entity.File;
import com.minidrive.entity.ShareLink;

import java.time.LocalDateTime;

public final class ShareLinkMapper {

    private ShareLinkMapper() {
    }

    public static ShareLinkResponse toResponse(ShareLink link) {
        return new ShareLinkResponse(
                link.getId(),
                link.getFile().getId(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                isExpired(link));
    }

    public static CreatedShareLinkResponse toCreatedResponse(ShareLink link, String token) {
        return new CreatedShareLinkResponse(
                link.getId(),
                link.getFile().getId(),
                token,
                link.getCreatedAt(),
                link.getExpiresAt());
    }

    public static SharedFileResponse toSharedFileResponse(File file) {
        return new SharedFileResponse(
                file.getName(),
                file.getContentType(),
                file.getSizeBytes());
    }

    public static boolean isExpired(ShareLink link) {
        return link.getExpiresAt() != null
                && !link.getExpiresAt().isAfter(LocalDateTime.now());
    }
}
