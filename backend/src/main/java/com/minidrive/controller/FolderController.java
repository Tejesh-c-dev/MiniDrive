package com.minidrive.controller;

import com.minidrive.dto.folder.FolderRequest;
import com.minidrive.dto.folder.FolderResponse;
import com.minidrive.service.FolderService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @PostMapping
    public ResponseEntity<FolderResponse> create(
            @Valid @RequestBody FolderRequest request,
            Authentication authentication) {

        FolderResponse response = folderService.create(
                authentication,
                request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<FolderResponse>> list(
            @RequestParam(name = "parentFolderId", required = false) UUID parentFolderId,
            Authentication authentication) {

        return ResponseEntity.ok(
                folderService.listChildren(
                        authentication,
                        parentFolderId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FolderResponse> get(
            @PathVariable UUID id,
            Authentication authentication) {

        return ResponseEntity.ok(
                folderService.get(authentication, id));
    }

    @GetMapping("/{id}/children")
    public ResponseEntity<List<FolderResponse>> children(
            @PathVariable UUID id,
            Authentication authentication) {

        return ResponseEntity.ok(
                folderService.listChildren(
                        authentication,
                        id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<FolderResponse> rename(
            @PathVariable UUID id,
            @Valid @RequestBody FolderRequest request,
            Authentication authentication) {

        return ResponseEntity.ok(
                folderService.rename(
                        authentication,
                        id,
                        request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            Authentication authentication) {

        folderService.delete(authentication, id);

        return ResponseEntity.noContent().build();
    }
}
