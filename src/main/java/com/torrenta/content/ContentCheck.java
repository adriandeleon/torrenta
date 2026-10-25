package com.torrenta.content;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.torrenta.engine.TorrentPreview;

import static com.torrenta.i18n.Messages.tr;

/**
 * What in a torrent's files deserves a look before it is added: a program dressed as a video, a
 * name that hides its real extension, a shortcut that can run a command, a program or a link
 * where the torrent is media, an archive where the name promises a film, a file that asks for
 * something before anything plays. The card above the Add dialog's file tree.
 *
 * <p>Only wanted files are reported, so a file the person skipped stops warning; whether the
 * torrent is media is read off all of them. A program in a software torrent is not a finding:
 * the context decides, and most of the findings need a media torrent to mean anything. Each file
 * is one finding, the most serious that fits.
 *
 * <p>Pure, and tested: the dialog only lays the card out.
 *
 * @param findings in the torrent's file order
 */
public record ContentCheck(List<Finding> findings) {

    public ContentCheck {
        findings = List.copyOf(findings);
    }

    public static final ContentCheck NONE = new ContentCheck(List.of());

    /** The card's colour: amber for a caution, red for danger. */
    public enum Severity {
        CAUTION,
        DANGER
    }

    /** What is wrong with a file, most serious first: the first one that fits is the finding. */
    public enum Kind {
        /** Bidi controls in the name, which make {@code exe.mkv} display as {@code vkm.exe}. */
        HIDDEN_EXTENSION(Severity.DANGER, "content.hiddenExtension"),
        /** A media or document extension before a program's: {@code film.mkv.exe}. */
        DISGUISED(Severity.DANGER, "content.disguised"),
        /** A Windows shortcut or Explorer command file, which runs whatever it points at. */
        SHORTCUT(Severity.DANGER, "content.shortcut"),
        /** A program in a torrent that is media. */
        PROGRAM(Severity.DANGER, "content.program"),
        /** A link to a web page in a torrent that is media: the usual "get the codec here". */
        LINK(Severity.CAUTION, "content.link"),
        /** An archive where the torrent's name promises a film or an album, with no media beside it. */
        ARCHIVE(Severity.CAUTION, "content.archive"),
        /** A file in a media torrent named for a password, a codec, an unlock. */
        BAIT(Severity.CAUTION, "content.bait");

        private final Severity severity;
        private final String key;

        Kind(Severity severity, String key) {
            this.severity = severity;
            this.key = key;
        }

        public Severity severity() {
            return severity;
        }

        /** The card's line for this kind: the first file's name, and how many more. */
        public String line(String first, int more) {
            return tr(key, first, more);
        }
    }

    /** One file and what is wrong with it. */
    public record Finding(Kind kind, int index, String path) {}

    /** One line of the card: a kind, its first file and how many more there are. */
    public record Line(Kind kind, String first, int more) {

        public String text() {
            return kind.line(visible(name(first)), more);
        }
    }

    /** Extensions that run as programs or scripts when opened. */
    static final Set<String> PROGRAMS = Set.of(
            "exe",
            "scr",
            "com",
            "pif",
            "bat",
            "cmd",
            "vbs",
            "vbe",
            "js",
            "jse",
            "wsf",
            "wsh",
            "hta",
            "cpl",
            "msi",
            "msp",
            "ps1",
            "psm1",
            "jar",
            "apk",
            "appimage",
            "sh",
            "command",
            "run",
            "deb",
            "rpm",
            "pkg");

    /** Shortcuts that run a command on Windows, whatever the torrent is. */
    static final Set<String> SHORTCUTS = Set.of("lnk", "scf");

    /** Links to web pages: harmless in themselves, the scam's usual way out of a media torrent. */
    static final Set<String> LINKS = Set.of("url", "webloc", "website");

    /** The kinds a disguise pretends to be; a program's extension after one of these is a disguise. */
    private static final Set<FileKind> PRETENDED = Set.of(
            FileKind.VIDEO,
            FileKind.AUDIO,
            FileKind.IMAGE,
            FileKind.BOOK,
            FileKind.DOCUMENT,
            FileKind.SUBTITLE,
            FileKind.ARCHIVE,
            FileKind.DISC);

    /** What the torrent is for when most of its bytes are one of these. */
    private static final Set<FileKind> MEDIA = Set.of(FileKind.VIDEO, FileKind.AUDIO, FileKind.IMAGE, FileKind.BOOK);

    /** A bait word in a file of one of these kinds is the file itself, not a lure: a song named "Password". */
    private static final Set<FileKind> NOT_BAIT =
            Set.of(FileKind.VIDEO, FileKind.AUDIO, FileKind.IMAGE, FileKind.BOOK, FileKind.SUBTITLE);

    /** Bidi embeddings, overrides, isolates and marks: none belongs in a file name. */
    private static final Pattern BIDI = Pattern.compile("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    /** Words in a torrent's name that promise a film, an episode or an album. */
    private static final Pattern PROMISES_MEDIA = Pattern.compile("\\b(?:480p|576p|720p|1080[pi]|2160p|4k|uhd"
            + "|x26[45]|h ?26[45]|hevc|av1|xvid|divx|blu ?ray|bdrip|brrip|web ?rip|web ?dl|hdtv|dvdrip|hdrip"
            + "|remux|s\\d{1,2}e\\d{1,3}|season \\d+|flac|mp3|320kbps)\\b");

    /** Separators a name uses in place of spaces. */
    private static final Pattern SEPARATORS = Pattern.compile("[._\\-\\[\\](){}+]+");

    /** What the dialog checks with: the language's bait words, which include the English ones. */
    public static ContentCheck of(TorrentPreview preview, IntPredicate wanted) {
        return of(preview, wanted, baitWords());
    }

    /**
     * The findings among the wanted files.
     *
     * @param wanted whether the file at a torrent index is wanted
     * @param baitWords lower-case words and phrases that make a non-media file in a media torrent
     *     a lure, matched as whole words
     */
    static ContentCheck of(TorrentPreview preview, IntPredicate wanted, Set<String> baitWords) {
        boolean media = isMedia(preview);
        boolean promisesMedia = promisesMedia(preview.name());
        Pattern bait = baitPattern(baitWords);
        List<Finding> found = new ArrayList<>();
        for (TorrentPreview.FileEntry f : preview.files()) {
            if (!wanted.test(f.index())) continue;
            Kind kind = kindOf(f.path(), media, promisesMedia, bait);
            if (kind != null) found.add(new Finding(kind, f.index(), f.path()));
        }
        return new ContentCheck(found);
    }

    /** The finding for one file, or null; the first kind that fits wins. */
    private static Kind kindOf(String path, boolean media, boolean promisesMedia, Pattern bait) {
        String name = name(path);
        if (BIDI.matcher(path).find()) return Kind.HIDDEN_EXTENSION;
        String extension = FileKind.extension(name);
        if (PROGRAMS.contains(extension)
                && PRETENDED.contains(FileKind.of(stem(name).strip(), false))) return Kind.DISGUISED;
        if (SHORTCUTS.contains(extension)) return Kind.SHORTCUT;
        boolean context = media || promisesMedia;
        if (context && (PROGRAMS.contains(extension) || insideAppBundle(path) || extension.equals("desktop")))
            return Kind.PROGRAM;
        if (context && LINKS.contains(extension)) return Kind.LINK;
        if (promisesMedia && !media && FileKind.of(name, false) == FileKind.ARCHIVE) return Kind.ARCHIVE;
        if (context && bait != null && !NOT_BAIT.contains(FileKind.of(name, false))) {
            if (bait.matcher(words(stem(name))).find()) return Kind.BAIT;
        }
        return null;
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    /** The worst finding's severity; CAUTION for none. */
    public Severity severity() {
        return findings.stream().anyMatch(f -> f.kind().severity() == Severity.DANGER)
                ? Severity.DANGER
                : Severity.CAUTION;
    }

    /** One line per kind found, most serious first, each naming its first file. */
    public List<Line> lines() {
        Map<Kind, List<Finding>> byKind = new EnumMap<>(Kind.class);
        for (Finding f : findings)
            byKind.computeIfAbsent(f.kind(), k -> new ArrayList<>()).add(f);
        List<Line> lines = new ArrayList<>();
        byKind.forEach((kind, list) -> lines.add(new Line(kind, list.get(0).path(), list.size() - 1)));
        return lines;
    }

    /** The torrent indices of every file found: what "Skip these files" unticks. */
    public Set<Integer> indices() {
        return findings.stream().map(Finding::index).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The card's title, by severity. */
    public String title() {
        return severity() == Severity.DANGER ? tr("content.title.danger") : tr("content.title.caution");
    }

    /** The sentence under the lines, by severity. */
    public String advice() {
        return severity() == Severity.DANGER ? tr("content.advice.danger") : tr("content.advice.caution");
    }

    /** Most of the real bytes are video, audio, images or books. */
    static boolean isMedia(TorrentPreview preview) {
        long total = 0;
        long media = 0;
        for (TorrentPreview.FileEntry f : preview.files()) {
            total += f.size();
            if (MEDIA.contains(FileKind.of(name(f.path()), false))) media += f.size();
        }
        return total > 0 && media * 2 >= total;
    }

    /** The torrent's name says film, episode or album: a resolution, a codec, a source, S01E02. */
    static boolean promisesMedia(String torrentName) {
        return PROMISES_MEDIA.matcher(words(torrentName)).find();
    }

    /**
     * A name with its bidi controls made visible: a name that displays as {@code vkm.exe} shows as
     * {@code ⟨U+202E⟩vkm.exe}, which is what the person needs to see.
     */
    static String visible(String name) {
        return BIDI.matcher(name)
                .replaceAll(m ->
                        String.format(Locale.ROOT, "⟨U+%04X⟩", (int) m.group().charAt(0)));
    }

    /** The bait words of the interface's language, a comma-separated list in the catalogue. */
    static Set<String> baitWords() {
        return parseWords(tr("content.baitWords"));
    }

    static Set<String> parseWords(String list) {
        return Arrays.stream(list.split(","))
                .map(w -> words(w).strip())
                .filter(w -> !w.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Pattern baitPattern(Set<String> words) {
        if (words.isEmpty()) return null;
        String alternatives = words.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        return Pattern.compile("(?:^| )(?:" + alternatives + ")(?: |$)");
    }

    /** Lower case, separators as single spaces: how names are matched against words. */
    private static String words(String text) {
        return SEPARATORS
                .matcher(text.toLowerCase(Locale.ROOT))
                .replaceAll(" ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** The last part of a path inside the torrent. */
    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** A name without its last extension. */
    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** A file inside a macOS application bundle: {@code Player.app/Contents/MacOS/Player}. */
    private static boolean insideAppBundle(String path) {
        String[] segments = path.split("/");
        return Arrays.stream(segments, 0, segments.length - 1)
                .anyMatch(folder -> folder.toLowerCase(Locale.ROOT).endsWith(".app"));
    }
}
