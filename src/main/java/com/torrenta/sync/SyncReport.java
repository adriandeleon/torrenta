package com.torrenta.sync;

import java.util.List;

import static com.torrenta.i18n.Messages.tr;

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
                        ? tr("sync.report.inSync")
                        : conflicts.isEmpty()
                                ? tr("sync.report.synced", received.size(), sent.size())
                                : tr("sync.report.syncedKept", received.size(), sent.size(), conflicts.size());
            case NO_GIT -> tr("sync.report.noGit");
            case FETCH_FAILED -> withDetail("sync.report.fetchFailed");
            case PUSH_FAILED -> withDetail("sync.report.pushFailed");
            case NEWER_FORMAT -> tr("sync.report.newerFormat");
            case LOCAL_BUSY -> tr("sync.report.localBusy");
            case FAILED -> withDetail("sync.report.failed");
        };
    }

    /** The sentence, with the first line of the detail after a colon when there is one. */
    private String withDetail(String key) {
        String first =
                detail.isBlank() ? "" : detail.strip().lines().findFirst().orElse("");
        return first.isEmpty() ? tr(key) : tr("sync.report.withDetail", tr(key), first);
    }
}
