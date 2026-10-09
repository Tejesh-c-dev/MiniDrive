package com.minidrive.dto.permission;

import com.minidrive.entity.FileRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request to grant (or update) a collaborator's direct access to a file.
 */
public class PermissionRequest {

    @NotBlank(message = "Email must not be blank")
    @Email(message = "Email must be a valid address")
    private String email;

    @NotNull(message = "Role must not be null")
    private FileRole role;

    public PermissionRequest() {
    }

    public PermissionRequest(String email, FileRole role) {
        this.email = email;
        this.role = role;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public FileRole getRole() {
        return role;
    }

    public void setRole(FileRole role) {
        this.role = role;
    }
}
