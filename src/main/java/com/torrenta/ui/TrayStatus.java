package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;

import com.torrenta.engine.Engine;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import com.torrenta.tray.TrayDisplay;

import static com.torrenta.i18n.Messages.tr;

/**
 * What the tray shows for a snapshot: the icon's state and its one line of text, the rates and a
 * count of what the torrents are doing. Pure; computed on the poll thread, once a second, and
 * value-equal snapshots are not pushed to the tray at all.
 *
 * @param display the arrow's colour: teal while anything downloads, red while anything has an error
 * @param line {@code ↓ 1.2 MiB/s ↑ 0 B/s · 2 downloading, 5 seeding}; the tooltip, and the menu's
 *     disabled first item, since GNOME shows SNI menus but never SNI tooltips
 */
public record TrayStatus(TrayDisplay display, String line) {

    public static TrayStatus of(List<Torrent> torrents, Engine.Rates rates) {
        int downloading = 0;
        int seeding = 0;
        int queued = 0;
        int paused = 0;
        int errors = 0;
        for (Torrent t : torrents) {
            if (!t.error().isEmpty()) errors++;
            else if (t.paused()) paused++;
            else if (t.queued()) queued++;
            else if (t.state() == TorrentState.DOWNLOADING) downloading++;
            else if (t.state() == TorrentState.SEEDING) seeding++;
        }
        List<String> parts = new ArrayList<>();
        if (downloading > 0) parts.add(tr("tray.downloading", downloading));
        if (seeding > 0) parts.add(tr("tray.seeding", seeding));
        if (queued > 0) parts.add(tr("tray.queued", queued));
        if (paused > 0) parts.add(tr("tray.paused", paused));
        if (errors > 0) parts.add(tr("tray.errors", errors));
        String summary;
        if (torrents.isEmpty()) summary = tr("tray.noTorrents");
        else if (parts.isEmpty()) summary = tr("tray.torrents", torrents.size());
        else summary = String.join(", ", parts);
        String line = tr("tray.line", Bytes.rate(rates.download()), Bytes.rate(rates.upload()), summary);
        return new TrayStatus(new TrayDisplay(downloading > 0, errors > 0), line);
    }

    /** Before the engine is up: nothing to count, and the line says what is happening. */
    public static TrayStatus starting() {
        return new TrayStatus(TrayDisplay.idle(), tr("tray.starting"));
    }
}
