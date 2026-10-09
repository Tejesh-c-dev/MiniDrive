package com.minidrive.controller;

import com.minidrive.dto.sharelink.CreateShareLinkRequest;
import com.minidrive.dto.sharelink.CreatedShareLinkResponse;
import com.minidrive.dto.sharelink.ShareLinkResponse;
import com.minidrive.service.ShareLinkService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Owner-only management of public share links. The acting user always comes
 * from the authenticated principal, and the backend rejects any non-owner
 * regardless of what the UI exposes.
 */
@RestController
public class ShareLinkController {

    private final ShareLinkService shareLinkService;

    public ShareLinkController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    /** Creates a new link and returns the raw token exactly once. */
    @PostMapping("/api/files/{fileId}/share-link")
    public ResponseEntity<CreatedShareLinkResponse> create(
            @PathVariable UUID fileId,
            @Valid @RequestBody(required = false) CreateShareLinkRequest request,
            Authentication authentication) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(shareLinkService.create(authentication, fileId, request));
    }

    /** Lists a file's links as metadata; raw tokens are never returned. */
    @GetMapping("/api/files/{fileId}/share-links")
    public ResponseEntity<List<ShareLinkResponse>> list(
            @PathVariable UUID fileId,
            Authentication authentication) {

        return ResponseEntity.ok(
                shareLinkService.list(authentication, fileId));
    }

    /** Revokes a link so its token stops working immediately. */
    @DeleteMapping("/api/files/{fileId}/share-links/{shareId}")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID fileId,
            @PathVariable UUID shareId,
            Authentication authentication) {

        shareLinkService.revoke(authentication, fileId, shareId);

        return ResponseEntity.noContent().build();
    }
}
