package com.minidrive.controller;

import com.minidrive.dto.upload.ChunkUploadResponse;
import com.minidrive.dto.upload.CreateUploadSessionRequest;
import com.minidrive.dto.upload.UploadSessionResponse;
import com.minidrive.dto.upload.UploadSessionStatusResponse;
import com.minidrive.service.UploadSessionService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.UUID;

/**
 * Resumable, chunked upload endpoints (Phase 8.2).
 *
 * <p>Every endpoint is authenticated and owner-scoped: the acting user is taken
 * from the principal, never from the request, and a session belonging to
 * another user resolves to {@code 404}. These endpoints only stage chunk bytes;
 * assembling them into a file is a later phase.</p>
 */
@RestController
public class UploadController {

    private final UploadSessionService uploadSessionService;

    public UploadController(UploadSessionService uploadSessionService) {
        this.uploadSessionService = uploadSessionService;
    }

    /** Initializes a session and returns its id, chunk size, and chunk count. */
    @PostMapping(path = "/api/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UploadSessionResponse> create(
            @Valid @RequestBody CreateUploadSessionRequest request,
            Authentication authentication) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(uploadSessionService.createSession(authentication, request));
    }

    /** Returns the session status and the sorted indexes already persisted. */
    @GetMapping("/api/uploads/{sessionId}")
    public ResponseEntity<UploadSessionStatusResponse> status(
            @PathVariable UUID sessionId,
            Authentication authentication) {

        return ResponseEntity.ok(
                uploadSessionService.getStatus(authentication, sessionId));
    }

    /**
     * Ingests one raw chunk. The body must be exactly {@code
     * application/octet-stream} and contain precisely the expected number of
     * bytes for its index; the service streams it to staging storage under a
     * server-generated key.
     */
    @PutMapping(
            path = "/api/uploads/{sessionId}/chunks/{index}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<ChunkUploadResponse> putChunk(
            @PathVariable UUID sessionId,
            @PathVariable int index,
            InputStream body,
            Authentication authentication) {

        return ResponseEntity.ok(
                uploadSessionService.ingestChunk(authentication, sessionId, index, body));
    }

    /** Aborts an eligible session and removes all of its staging objects. */
    @DeleteMapping("/api/uploads/{sessionId}")
    public ResponseEntity<Void> abort(
            @PathVariable UUID sessionId,
            Authentication authentication) {

        uploadSessionService.abort(authentication, sessionId);

        return ResponseEntity.noContent().build();
    }
}
