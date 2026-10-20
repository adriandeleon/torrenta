package com.torrenta.engine;

import java.util.List;

/**
 * What a {@code .torrent} says about itself, before it is added: enough for the Add dialog to show
 * the name, the size and the file tree, and to let the person choose.
 *
 * @param id the best info-hash, hex
 * @param files the real files, in index order; libtorrent's padding files are left out, so the
 *     indices are the torrent's own and may have gaps
 * @param trackers the announce URLs, in tier order
 * @param comment the torrent's comment, or empty
 * @param createdBy the creating program, or empty
 * @param creationDate epoch seconds, or 0
 * @param v1 the info has v1 (SHA-1) piece hashes
 * @param v2 the info has v2 (SHA-256 merkle) piece hashes; both is a hybrid torrent
 */
public record TorrentPreview(
        String id,
        String name,
        long size,
        List<FileEntry> files,
        boolean isPrivate,
        List<String> trackers,
        String comment,
        String createdBy,
        long creationDate,
        boolean v1,
        boolean v2) {

    /** One file: its index in the torrent, its path inside it (forward slashes), its size. */
    public record FileEntry(int index, String path, long size) {}

    /** More than one file, or one file inside a folder: the torrent has a root folder. */
    public boolean hasRootFolder() {
        return files.stream().anyMatch(f -> f.path().contains("/"));
    }
}
