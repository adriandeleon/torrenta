package com.torrenta.sync;

import java.util.List;

/** What one sync round did, or why it stopped. */
public record SyncReport(
        Status status, List<String> received, List<String> sent, List<String> conflicts, String detail) {

    public enum Status {
        OK,
        NO_GIT,
        FETCH_FAILED,
        PUSH_FAILED,
        NEWER_FORMAT,
        LOCAL_BUSY,
        FAILED
    }

    public boolean ok() {
        return status == Status.OK;
    }

    public boolean changedAnything() {
        return !received.isEmpty() || !sent.isEmpty();
    }

    static SyncReport failure(Status status, String detail) {
        return new SyncReport(status, List.of(), List.of(), List.of(), detail == null ? "" : detail);
    }

    /** One line for a status bar. */
    public String summary() {
        return switch (status) {
            case OK ->
                received.isEmpty() && sent.isEmpty()
                        ? "Settings in sync"
                        : "Settings synced: " + received.size() + " received, " + sent.size() + " sent"
                                + (conflicts.isEmpty() ? "" : ", " + conflicts.size() + " kept from this machine");
            case NO_GIT -> "Settings sync needs git on the PATH";
            case FETCH_FAILED -> "Settings sync could not reach the repository" + detailSuffix();
            case PUSH_FAILED -> "Settings sync could not push" + detailSuffix();
            case NEWER_FORMAT -> "Settings sync: the repository was written by a newer Torrenta";
            case LOCAL_BUSY -> "Settings sync: settings changed during the sync; trying again";
            case FAILED -> "Settings sync failed" + detailSuffix();
        };
    }

    private String detailSuffix() {
        if (detail.isBlank()) return "";
        String first = detail.strip().lines().findFirst().orElse("");
        return first.isEmpty() ? "" : ": " + first;
    }
}
