package com.minidrive.controller;

import com.minidrive.dto.sharelink.SharedFileResponse;
import com.minidrive.service.ShareLinkService;
import com.minidrive.service.ClientIpResolver;
import com.minidrive.service.DistributedRateLimiter;
import com.minidrive.util.DownloadResponses;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import jakarta.servlet.http.HttpServletRequest;

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
    private final DistributedRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public ShareController(ShareLinkService shareLinkService,
                           DistributedRateLimiter rateLimiter,
                           ClientIpResolver clientIpResolver) {
        this.shareLinkService = shareLinkService;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/{token}")
    public ResponseEntity<SharedFileResponse> metadata(
            @PathVariable String token,
            HttpServletRequest request) {

        rateLimiter.share(clientIpResolver.resolve(request), token);

        return ResponseEntity.ok(shareLinkService.getSharedFile(token));
    }

    @GetMapping("/{token}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String token,
            HttpServletRequest request) {

        rateLimiter.share(clientIpResolver.resolve(request), token);

        ShareLinkService.SharedFileDownload shared = shareLinkService.download(token);

        return DownloadResponses.attachment(
                shared.name(),
                shared.contentType(),
                shared.sizeBytes(),
                shared.inputStream());
    }
}
