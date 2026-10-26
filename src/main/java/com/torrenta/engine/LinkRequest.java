package com.torrenta.engine;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * A link to download as it is, not as a torrent, with the Add Link dialog's choices.
 *
 * @param url the link: http, https, ftp, ftps or sftp ({@link Inputs#isDirectLink})
 * @param savePath the folder, on this machine; null for the download folder
 * @param fileName the file's name as a person chose it; null or blank for the link's own (which
 *     the server may then replace with the name it gives)
 * @param startPaused added stopped; the person resumes it
 */
public record LinkRequest(
        String url, @Nullable Path savePath, @Nullable String fileName, boolean startPaused) {

    public LinkRequest {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("a link request needs a link");
        url = url.strip();
    }

    /** The link with every default: the download folder, its own name, started. */
    public static LinkRequest of(String url) {
        return new LinkRequest(url, null, null, false);
    }
}
