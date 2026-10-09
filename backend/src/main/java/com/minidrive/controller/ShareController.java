package com.minidrive.controller;

import com.minidrive.dto.sharelink.SharedFileResponse;
import com.minidrive.service.ShareLinkService;
import com.minidrive.util.DownloadResponses;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Anonymous, read-only access to a file through a public share link.
 *
 * <p>Both endpoints authorize solely by the bearer token in the path; a file id
 * supplied by the recipient is never accepted as a substitute. They expose only
 * minimal, safe metadata and stream downloads through the same storage
 * abstraction and filename-safety code as the authenticated download. No
 * mutation is possible here.</p>
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final ShareLinkService shareLinkService;

    public ShareController(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<SharedFileResponse> metadata(
            @PathVariable String token) {

        return ResponseEntity.ok(shareLinkService.getSharedFile(token));
    }

    @GetMapping("/{token}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String token) {

        ShareLinkService.SharedFileDownload shared = shareLinkService.download(token);

        return DownloadResponses.attachment(
                shared.name(),
                shared.contentType(),
                shared.sizeBytes(),
                shared.inputStream());
    }
}
