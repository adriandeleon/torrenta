package com.torrenta.ui;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a row of the Files tab holds, read off its name's extension: the rule for which glyph it
 * shows. Pure; {@link #glyph} is the one place a kind meets {@link Icons}.
 */
enum FileKind {
    FOLDER,
    VIDEO,
    AUDIO,
    IMAGE,
    /** A book or a comic: ebooks and the comic archives (a .cbr is a rar of pages, read as a book). */
    BOOK,
    DOCUMENT,
    SUBTITLE,
    ARCHIVE,
    DISC,
    DATA,
    PROGRAM,
    OTHER;

    private static final Map<FileKind, Set<String>> EXTENSIONS = Map.of(
            VIDEO,
                    Set.of(
                            "mkv", "mp4", "m4v", "avi", "mov", "wmv", "webm", "mpg", "mpeg", "ts", "m2ts", "vob", "flv",
                            "ogv", "3gp", "divx"),
            AUDIO,
                    Set.of(
                            "mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b", "aac", "wav", "wma", "alac", "ape",
                            "aiff", "aif", "dsf", "mka", "cue"),
            IMAGE,
                    Set.of(
                            "jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "svg", "heic", "avif", "ico",
                            "raw", "cr2", "nef", "dng"),
            BOOK, Set.of("epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbr", "cbz", "cb7", "cbt"),
            DOCUMENT,
                    Set.of(
                            "pdf", "txt", "nfo", "md", "rtf", "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx",
                            "odp", "html", "htm", "log"),
            SUBTITLE, Set.of("srt", "ass", "ssa", "sub", "idx", "vtt", "sup"),
            ARCHIVE,
                    Set.of("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz", "xz", "txz", "zst", "lz", "lzma", "z"),
            DISC, Set.of("iso", "img", "dmg", "bin", "nrg", "mdf", "vhd", "vhdx", "vmdk", "qcow2"),
            DATA, Set.of("sqlite", "db", "xml", "json", "csv", "tsv", "yaml", "yml", "sfv", "md5", "sha1", "sha256"),
            PROGRAM,
                    Set.of(
                            "exe",
                            "msi",
                            "deb",
                            "rpm",
                            "apk",
                            "appimage",
                            "pkg",
                            "jar",
                            "sh",
                            "bat",
                            "cmd",
                            "ps1",
                            "flatpak",
                            "snap"));

    /** The kind of a folder, or of a file by its name's last extension (case aside); OTHER without one. */
    static FileKind of(String name, boolean folder) {
        if (folder) return FOLDER;
        String extension = extension(name);
        if (extension.isEmpty()) return OTHER;
        // Split archives: name.r00, name.part1.rar is caught by "rar", name.7z.001 by its number.
        if (extension.matches("r\\d\\d") || extension.matches("\\d{3}")) return ARCHIVE;
        for (FileKind kind : values()) {
            if (EXTENSIONS.getOrDefault(kind, Set.of()).contains(extension)) return kind;
        }
        return OTHER;
    }

    /** The part after the last dot, lower case; empty for none, a dot file or a trailing dot. */
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** This kind's glyph, fresh, at the menus' size. */
    Icons.Glyph glyph() {
        Icons.Glyph g =
                switch (this) {
                    case FOLDER -> Icons.folder();
                    case VIDEO -> Icons.fileVideo();
                    case AUDIO -> Icons.fileAudio();
                    case IMAGE -> Icons.fileImage();
                    case BOOK -> Icons.book();
                    case DOCUMENT -> Icons.files();
                    case SUBTITLE -> Icons.subtitles();
                    case ARCHIVE -> Icons.fileArchive();
                    case DISC -> Icons.disc();
                    case DATA -> Icons.database();
                    case PROGRAM -> Icons.box();
                    case OTHER -> Icons.file();
                };
        return g.at(Icons.MENU_SIZE);
    }
}
