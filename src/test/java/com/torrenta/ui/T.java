package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;

/** A torrent for the state-language tests, built field by field from a downloading default. */
final class T {
    String id = "a";
    long size = 1000;
    long done = 0;
    double progress = 0;
    TorrentState state = TorrentState.DOWNLOADING;
    long down = 0;
    long up = 0;
    int peers = 0;
    int seeds = 0;
    long uploaded = 0;
    long downloaded = 0;
    boolean paused = false;
    boolean sequential = false;
    long seedingTime = 0;
    boolean queued = false;
    int queuePosition = -1;
    String error = "";
    boolean moving = false;
    boolean superSeeding = false;
    List<String> trackers = List.of();

    static T downloading() {
        return new T();
    }

    static T seeding() {
        T t = new T();
        t.state = TorrentState.SEEDING;
        t.progress = 1;
        t.done = t.size;
        t.downloaded = t.size;
        return t;
    }

    T state(TorrentState s) {
        state = s;
        return this;
    }

    T id(String value) {
        id = value;
        return this;
    }

    T progress(double p) {
        progress = p;
        done = (long) (size * p);
        return this;
    }

    T down(long rate) {
        down = rate;
        return this;
    }

    T up(long rate) {
        up = rate;
        return this;
    }

    T peers(int n, int s) {
        peers = n;
        seeds = s;
        return this;
    }

    T uploaded(long bytes) {
        uploaded = bytes;
        return this;
    }

    T paused() {
        paused = true;
        return this;
    }

    T sequential() {
        sequential = true;
        return this;
    }

    T queued(int position) {
        queued = true;
        queuePosition = position;
        return this;
    }

    T error(String message) {
        error = message;
        return this;
    }

    T superSeeding() {
        superSeeding = true;
        return this;
    }

    /** A download from a link on {@code host}, as the direct downloads build their rows. */
    T direct(String host) {
        id = com.torrenta.engine.direct.DirectDownload.idOf("https://" + host + "/" + id);
        trackers = List.of(host);
        return this;
    }

    T moving() {
        moving = true;
        return this;
    }

    Torrent build() {
        return new Torrent(
                id,
                "name",
                size,
                done,
                progress,
                state,
                down,
                up,
                peers,
                seeds,
                uploaded,
                downloaded,
                0,
                0,
                "",
                "/data",
                paused,
                sequential,
                seedingTime,
                "",
                List.of(),
                error,
                trackers,
                false,
                queued,
                queuePosition,
                moving,
                superSeeding);
    }
}
