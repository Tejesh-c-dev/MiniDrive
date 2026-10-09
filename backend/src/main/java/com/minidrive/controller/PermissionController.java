package com.minidrive.controller;

import com.minidrive.dto.permission.PermissionRequest;
import com.minidrive.dto.permission.PermissionResponse;
import com.minidrive.service.PermissionService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * File-scoped permission management. Every operation is owner-only and the
 * acting user is taken from the authenticated principal.
 */
@RestController
@RequestMapping("/api/files/{fileId}/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @PostMapping
    public ResponseEntity<PermissionResponse> grant(
            @PathVariable UUID fileId,
            @Valid @RequestBody PermissionRequest request,
            Authentication authentication) {

        return ResponseEntity.ok(
                permissionService.grant(authentication, fileId, request));
    }

    @GetMapping
    public ResponseEntity<List<PermissionResponse>> list(
            @PathVariable UUID fileId,
            Authentication authentication) {

        return ResponseEntity.ok(
                permissionService.list(authentication, fileId));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID fileId,
            @PathVariable UUID userId,
            Authentication authentication) {

        permissionService.revoke(authentication, fileId, userId);

        return ResponseEntity.noContent().build();
    }
}
