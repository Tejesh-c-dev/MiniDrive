package com.minidrive.mapper;

import com.minidrive.dto.permission.PermissionResponse;
import com.minidrive.entity.Permission;

public final class PermissionMapper {

    private PermissionMapper() {
    }

    public static PermissionResponse toResponse(Permission permission) {
        return new PermissionResponse(
                permission.getUser().getId(),
                permission.getUser().getEmail(),
                permission.getRole(),
                permission.getCreatedAt());
    }
}
