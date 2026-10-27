package com.torrenta.migrate;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One torrent as another client has it, read and ready to bring over: what the import plan decides
 * by and what the engine adds from.
 *
 * @param client the client it comes from, for the dialog and the log ("qBittorrent")
 * @param id the id Torrenta will know it by: the v2 hash cut to 20 bytes when there is one, else
 *     the v1 hash, hex (the engine's {@code getBest()})
 * @param name the name the torrent has
 * @param rename the name a person gave it in the other client; empty for none
 * @param resume the other client's resume data, libtorrent's format, as read
 * @param metainfo the {@code .torrent}; null for a magnet whose metadata never arrived
 * @param savePath where its files are
 * @param category its category; empty for none
 * @param categoryPath that category's folder in the other client; empty for none
 * @param tags its tags
 * @param stopped stopped by a person (paused and not managed by the queue)
 * @param files the files the metainfo lists, by their paths under the save path; empty without
 *     metainfo
 * @param piecesHave pieces the resume data says are there
 * @param pieces pieces in the torrent; 0 without metainfo
 * @param ratioLimit its own ratio limit; -2 for the global one, -1 for none
 * @param seedingMinutes its own seeding-time limit, in minutes; -2 for the global one, -1 for none
 * @param renamed files the other client renamed, by the torrent's own index: the path under the
 *     save path, forward slashes, as Torrenta's {@code .storage} sidecar keeps them
 */
public record ImportCandidate(
        String client,
        String id,
        String name,
        String rename,
        byte[] resume,
        byte @Nullable [] metainfo,
        String savePath,
        String category,
        String categoryPath,
        List<String> tags,
        boolean stopped,
        List<FileEntry> files,
        long piecesHave,
        long pieces,
        double ratioLimit,
        long seedingMinutes,
        java.util.Map<Integer, String> renamed) {

    /**
     * One file: its path under the save path, as the other client has it on disk, its size, and
     * whether the resume data says some of its pieces are there (a file with none may well be
     * absent: a torrent not yet downloaded that far, or a file left out).
     */
    public record FileEntry(String path, long size, boolean claimed) {}

    public ImportCandidate {
        rename = rename == null ? "" : rename;
        category = category == null ? "" : category;
        categoryPath = categoryPath == null ? "" : categoryPath;
        tags = tags == null ? List.of() : List.copyOf(tags);
        files = files == null ? List.of() : List.copyOf(files);
        renamed = renamed == null ? java.util.Map.of() : java.util.Map.copyOf(renamed);
    }

    /** No file renamed: what most clients' torrents are. */
    public ImportCandidate(
            String client,
            String id,
            String name,
            String rename,
            byte[] resume,
            byte @Nullable [] metainfo,
            String savePath,
            String category,
            String categoryPath,
            List<String> tags,
            boolean stopped,
            List<FileEntry> files,
            long piecesHave,
            long pieces,
            double ratioLimit,
            long seedingMinutes) {
        this(
                client,
                id,
                name,
                rename,
                resume,
                metainfo,
                savePath,
                category,
                categoryPath,
                tags,
                stopped,
                files,
                piecesHave,
                pieces,
                ratioLimit,
                seedingMinutes,
                java.util.Map.of());
    }

    /** The same torrent with its files looked for in another folder: a person moved them, or a test. */
    public ImportCandidate withSavePath(String path) {
        return new ImportCandidate(
                client,
                id,
                name,
                rename,
                resume,
                metainfo,
                path,
                category,
                categoryPath,
                tags,
                stopped,
                files,
                piecesHave,
                pieces,
                ratioLimit,
                seedingMinutes,
                renamed);
    }

    /** Every piece is there. */
    public boolean complete() {
        return pieces > 0 && piecesHave >= pieces;
    }

    /** The name the dialog shows: the one a person gave, else the torrent's own. */
    public String shownName() {
        return rename.isEmpty() ? name : rename;
    }
}
