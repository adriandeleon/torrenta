package com.torrenta.session;

import java.nio.file.Path;
import java.util.Map;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.DownloadPlan;
import com.torrenta.engine.FileRenames;
import com.torrenta.engine.TorrentLabels;

/**
 * What the settings say a new torrent starts as: the Add dialog's starting positions, and the
 * whole request of an add without a dialog (a watch folder's, an RSS rule's, Add without asking).
 *
 * @param savePath the download folder
 * @param startPaused the Start paused box's starting position
 * @param sequential the Sequential box's
 * @param incompleteEnabled whether downloads start in the incomplete folder
 * @param incompleteDir that folder
 * @param partSuffix whether incomplete files carry {@code .part}
 * @param preallocate whether files are allocated in full at the start
 * @param layout how the files sit under the save path
 */
public record AddDefaults(
        Path savePath,
        boolean startPaused,
        boolean sequential,
        boolean incompleteEnabled,
        Path incompleteDir,
        boolean partSuffix,
        boolean preallocate,
        FileRenames.Layout layout) {

    public AddDefaults {
        if (layout == null) layout = FileRenames.Layout.ORIGINAL;
    }

    /** In the torrent's own layout. */
    public AddDefaults(
            Path savePath,
            boolean startPaused,
            boolean sequential,
            boolean incompleteEnabled,
            Path incompleteDir,
            boolean partSuffix,
            boolean preallocate) {
        this(
                savePath,
                startPaused,
                sequential,
                incompleteEnabled,
                incompleteDir,
                partSuffix,
                preallocate,
                FileRenames.Layout.ORIGINAL);
    }

    public static AddDefaults from(com.torrenta.store.Settings settings) {
        return new AddDefaults(
                settings.downloadDir(),
                settings.addStartPaused(),
                settings.addSequential(),
                settings.incompleteEnabled(),
                settings.incompleteDir(),
                settings.partSuffix(),
                settings.preallocate(),
                settings.addContentLayout());
    }

    /** The same defaults with another folder: a remote engine's own, when the files are not here. */
    public AddDefaults withSavePath(Path path) {
        return new AddDefaults(
                path, startPaused, sequential, incompleteEnabled, incompleteDir, partSuffix, preallocate);
    }

    /** Where a torrent saved to {@code destination} starts and ends. */
    public DownloadPlan plan(Path destination) {
        return DownloadPlan.of(destination, incompleteEnabled, incompleteDir);
    }

    public AddRequest.Storage storage(Path destination) {
        return AddRequest.Storage.of(plan(destination), partSuffix, preallocate).withLayout(layout);
    }

    /** The request an input is added with when the dialog does not open: these defaults, as they are. */
    public AddRequest withoutAsking(AddRequest.Source source) {
        return new AddRequest(
                source,
                savePath(),
                startPaused(),
                sequential(),
                false,
                AddRequest.StopCondition.NONE,
                Map.of(),
                TorrentLabels.NONE,
                storage(savePath()));
    }
}
