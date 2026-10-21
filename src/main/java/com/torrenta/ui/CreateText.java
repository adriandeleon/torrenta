package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.torrenta.engine.CreatedTorrent;
import com.torrenta.engine.PieceSize;

import static com.torrenta.i18n.Messages.tr;

/** The Create Torrent dialog's words and its text rules, pure so they are tested without a scene. */
final class CreateText {

    private CreateText() {}

    /**
     * Tracker tiers from the text area: one URL per line, a blank line starts the next tier.
     * Blank lines at the edges and runs of them make no empty tier; a URL seen before stays out.
     */
    static List<List<String>> tiers(String text) {
        List<List<String>> tiers = new ArrayList<>();
        List<String> tier = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : (text == null ? "" : text).split("\\R", -1)) {
            String url = line.trim();
            if (url.isEmpty()) {
                if (!tier.isEmpty()) tiers.add(List.copyOf(tier));
                tier = new ArrayList<>();
            } else if (seen.add(url)) {
                tier.add(url);
            }
        }
        if (!tier.isEmpty()) tiers.add(List.copyOf(tier));
        return List.copyOf(tiers);
    }

    /** The text area's content for tiers: one URL per line, a blank line between tiers. */
    static String tiersText(List<List<String>> tiers) {
        StringBuilder sb = new StringBuilder();
        for (List<String> tier : tiers) {
            if (tier.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(String.join("\n", tier));
        }
        return sb.toString();
    }

    /** One entry per line, trimmed, blanks and repeats out: the web seeds. */
    static List<String> lines(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : (text == null ? "" : text).split("\\R")) {
            String s = line.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return List.copyOf(out);
    }

    /** The default name of the file to save: the source's name with {@code .torrent} after it. */
    static String fileName(Path source) {
        Path name = source.getFileName();
        return (name == null ? "torrent" : name.toString()) + ".torrent";
    }

    /** Where "Save as" starts: the folder remembered from last time while it exists, else the source's parent. */
    static Path saveDir(String remembered, Path source) {
        if (remembered != null && !remembered.isBlank()) {
            try {
                Path dir = Path.of(remembered.trim());
                if (Files.isDirectory(dir)) return dir;
            } catch (RuntimeException e) {
                // not a path on this machine: the source's folder then
            }
        }
        Path parent = source.toAbsolutePath().normalize().getParent();
        return parent == null ? source.toAbsolutePath() : parent;
    }

    /**
     * The header's second line while the source is being sized and once it is: files, bytes, and
     * the piece count the piece size gives. {@code pieceSize} 0 means automatic, shown resolved.
     */
    static String summary(int files, long total, int pieceSize, long pieces) {
        if (files < 0) return tr("dialog.create.sizing");
        if (files == 0) return tr("dialog.create.empty");
        return tr(
                "dialog.create.summary",
                tr("dialog.create.summary.files", files),
                Bytes.human(total),
                tr("dialog.create.summary.pieces", pieces),
                PieceSize.label(pieceSize));
    }

    /** The piece-size choice's label: {@code Automatic (1 MiB)} for 0, the size otherwise. */
    static String pieceSizeLabel(int choice, long totalSize) {
        if (choice == 0) return tr("dialog.create.pieceSize.automatic", PieceSize.label(PieceSize.suggest(totalSize)));
        return PieceSize.label(choice);
    }

    /** The progress line: {@code Hashing · 120 of 1,500 pieces}. */
    static String hashing(int done, int total) {
        if (total <= 0) return tr("dialog.create.hashing");
        return tr("dialog.create.hashingProgress", done, total);
    }

    /** The outcome line under the hashes: where it went and whether it seeds. */
    static String outcome(Path saved, CreatedTorrent made, boolean seedingAsked) {
        String where = saved.toString();
        if (made.seeding().isPresent()) return tr("dialog.create.saved.seeding", where);
        if (seedingAsked) {
            String why = made.problem().isEmpty() ? tr("dialog.create.saved.notAdded.unknown") : made.problem();
            return tr("dialog.create.saved.notAdded", where, why);
        }
        return tr("dialog.create.saved", where);
    }

    /** Why Create cannot go ahead, or empty: the source must be a file or folder that exists. */
    static String problem(String sourceText) {
        String text = sourceText == null ? "" : sourceText.trim();
        if (text.isEmpty()) return tr("dialog.create.problem.noSource");
        try {
            Path path = Path.of(text);
            if (!Files.exists(path)) return tr("dialog.create.problem.notFound", text);
            if (path.toAbsolutePath().normalize().getParent() == null) return tr("dialog.create.problem.root");
        } catch (RuntimeException e) {
            return tr("dialog.create.problem.notPath", text);
        }
        return "";
    }
}
