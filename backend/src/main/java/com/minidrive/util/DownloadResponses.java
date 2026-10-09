package com.minidrive.util;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds the {@code attachment} download response shared by the authenticated
 * file endpoint and the anonymous public share endpoint. A single
 * implementation keeps filename sanitization and content-type handling
 * identical so the public link cannot bypass the owner download's safety
 * checks.
 */
public final class DownloadResponses {

    private DownloadResponses() {
    }

    public static ResponseEntity<StreamingResponseBody> attachment(
            String name,
            String contentType,
            long sizeBytes,
            InputStream inputStream) {

        StreamingResponseBody body = output -> {
            try (var input = inputStream) {
                input.transferTo(output);
            }
        };

        String safeFilename = name.replaceAll("[\\r\\n\"\\\\/\\p{Cntrl}]", "_");
        if (safeFilename.isBlank()) {
            safeFilename = "download";
        }

        ContentDisposition disposition = safeFilename
                .codePoints()
                .anyMatch(character -> character > 0x7f)
                ? ContentDisposition.attachment()
                        .filename(safeFilename, StandardCharsets.UTF_8)
                        .build()
                : ContentDisposition.attachment()
                        .filename(safeFilename)
                        .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(sizeBytes)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(body);
    }
}
