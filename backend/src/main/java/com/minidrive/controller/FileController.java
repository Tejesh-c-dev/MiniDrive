package com.minidrive.controller;

import com.minidrive.dto.file.FileMetadataRequest;
import com.minidrive.dto.file.FileMetadataResponse;
import com.minidrive.dto.file.FileMetadataUpdateRequest;
import com.minidrive.dto.file.FileResponse;
import com.minidrive.dto.file.FileUploadResponse;
import com.minidrive.service.FileService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * File metadata and object transfer endpoints.
 */
@RestController
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping(path = "/api/files", consumes = "application/json")
    public ResponseEntity<FileUploadResponse> create(
            @Valid @RequestBody FileMetadataRequest request,
            Authentication authentication) {

        FileUploadResponse response = fileService.createMetadata(
                authentication,
                request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping(path = "/api/files", consumes = "multipart/form-data")
    public ResponseEntity<FileUploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "folderId", required = false) UUID folderId,
            Authentication authentication) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(fileService.upload(authentication, file, folderId));
    }

    @GetMapping("/api/files")
    public ResponseEntity<List<FileResponse>> list(
            @RequestParam(name = "folderId", required = false) UUID folderId,
            Authentication authentication) {

        return ResponseEntity.ok(
                fileService.listInFolder(
                        authentication,
                        folderId));
    }

    @GetMapping("/api/files/{id}")
    public ResponseEntity<FileMetadataResponse> get(
            @PathVariable UUID id,
            Authentication authentication) {

        return ResponseEntity.ok(
                fileService.get(authentication, id));
    }

    @GetMapping("/api/files/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable UUID id,
            Authentication authentication) {
        FileService.DownloadedFile file = fileService.download(authentication, id);
        StreamingResponseBody body = output -> {
            try (var input = file.inputStream()) {
                input.transferTo(output);
            }
        };
        String safeFilename = file.name().replaceAll("[\\r\\n\\\"\\\\/\\p{Cntrl}]", "_");
        if (safeFilename.isBlank()) safeFilename = "download";
        ContentDisposition disposition = safeFilename.codePoints().anyMatch(character -> character > 0x7f)
                ? ContentDisposition.attachment().filename(safeFilename, StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(safeFilename).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition.toString())
                .body(body);
    }

    @GetMapping("/api/folders/{folderId}/files")
    public ResponseEntity<List<FileResponse>> listInFolder(
            @PathVariable UUID folderId,
            Authentication authentication) {

        return ResponseEntity.ok(
                fileService.listInOwnedFolder(
                        authentication,
                        folderId));
    }

    @PatchMapping("/api/files/{id}")
    public ResponseEntity<FileMetadataResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody FileMetadataUpdateRequest request,
            Authentication authentication) {

        return ResponseEntity.ok(
                fileService.update(
                        authentication,
                        id,
                        request));
    }

    @DeleteMapping("/api/files/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            Authentication authentication) {

        fileService.delete(authentication, id);

        return ResponseEntity.noContent().build();
    }
}
