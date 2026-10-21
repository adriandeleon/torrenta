package com.torrenta.ui;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import com.torrenta.engine.DownloadPlan;

import static com.torrenta.i18n.Messages.tr;

/** The volume a path is (or will be) on: its free space, and what a torrent would leave of it. */
public final class FreeSpace {

    private FreeSpace() {}

    /**
     * One volume, as the Add dialog's disk bar draws it.
     *
     * @param total the volume's size in bytes
     * @param free bytes usable by this process
     * @param volume what the person calls it: the mount point, or the drive
     */
    public record Disk(long total, long free, String volume) {

        public Disk {
            total = Math.max(0, total);
            free = Math.max(0, Math.min(free, total));
        }
    }

    /**
     * The disk bar's two segments as fractions of the volume: what is used already, and what the
     * torrent will take on top of it, cut at what is free.
     */
    public record Bar(double used, double torrent, boolean fits) {}

    /**
     * The Add dialog's lines under the save path: the free space where the files end up and,
     * when they start in the incomplete folder, that folder and its free space on a line of its
     * own (the download has to fit there first). Pure: the dialog hands in what it measured.
     */
    public static String hint(DownloadPlan plan, OptionalLong freeAtStart, OptionalLong freeAtDestination) {
        if (!plan.viaIncomplete()) {
            return freeAtDestination.isPresent()
                    ? tr("freeSpace.free", Bytes.human(freeAtDestination.getAsLong()))
                    : "";
        }
        String start = freeAtStart.isPresent()
                ? tr("freeSpace.startsInFree", plan.start(), Bytes.human(freeAtStart.getAsLong()))
                : tr("freeSpace.startsIn", plan.start());
        String then = freeAtDestination.isPresent()
                ? tr("freeSpace.movedWhenCompleteFree", Bytes.human(freeAtDestination.getAsLong()))
                : tr("freeSpace.movedWhenComplete");
        return start + "\n" + then;
    }

    /**
     * Walks up to the nearest existing ancestor, since the save folder usually does not exist yet
     * while it is being typed. Empty when nothing on the way exists or the store cannot be read.
     */
    public static OptionalLong of(Path path) {
        return disk(path).map(d -> OptionalLong.of(d.free())).orElse(OptionalLong.empty());
    }

    /** The volume under {@link #of}'s nearest existing ancestor, with its size and name. */
    public static Optional<Disk> disk(Path path) {
        Path probe = path.toAbsolutePath();
        while (probe != null && !Files.exists(probe)) probe = probe.getParent();
        if (probe == null) return Optional.empty();
        try {
            FileStore store = Files.getFileStore(probe);
            return Optional.of(
                    new Disk(store.getTotalSpace(), store.getUsableSpace(), volume(store.toString(), probe)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The JDK's Unix stores print as {@code "/data (/dev/sdb1)"}: the mount point is the useful
     * half. Windows' print as {@code "Data (D:)"}, where the drive is; the path's root says it
     * plainly either way.
     */
    static String volume(String store, Path probe) {
        int paren = store.lastIndexOf(" (");
        if (store.startsWith("/") && paren > 0) return store.substring(0, paren);
        Path root = probe.getRoot();
        return root == null ? store : root.toString();
    }

    /** {@code wanted} is negative while unknown (a magnet before its metadata): no torrent segment. */
    public static Bar bar(Disk disk, long wanted) {
        if (disk.total() <= 0) return new Bar(0, 0, true);
        double used = (double) (disk.total() - disk.free()) / disk.total();
        long taken = Math.max(0, Math.min(wanted, disk.free()));
        return new Bar(used, (double) taken / disk.total(), wanted <= disk.free());
    }

    /**
     * "412 GB free on /data · 5.9 GB leaves 406 GB", or "… · 5.9 GB is 1.2 GB more than that"
     * when it does not fit; the first half alone while the size is unknown ({@code wanted < 0}).
     */
    public static String sentence(Disk disk, long wanted) {
        String free = Bytes.human(disk.free());
        if (wanted < 0) return tr("freeSpace.freeOn", free, disk.volume());
        if (wanted <= disk.free())
            return tr("freeSpace.leaves", free, disk.volume(), Bytes.human(wanted), Bytes.human(disk.free() - wanted));
        return tr("freeSpace.moreThan", free, disk.volume(), Bytes.human(wanted), Bytes.human(wanted - disk.free()));
    }
}
