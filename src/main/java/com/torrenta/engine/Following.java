package com.torrenta.engine;

/**
 * A torrent added from an updatable address ({@link MutableSource}), kept in its {@code .labels}
 * sidecar: where newer versions are looked for, the version it is, and the older torrent it is to
 * replace once it is complete.
 *
 * @param source the address, {@link MutableSource#uri()}
 * @param seq the item's sequence number this torrent came from
 * @param replaces the id of the version before, while that one is still to be let go of; empty for none
 */
public record Following(String source, long seq, String replaces) {

    public Following {
        source = source == null ? "" : source.trim();
        replaces = replaces == null ? "" : replaces.trim();
    }

    public Following withReplaces(String id) {
        return new Following(source, seq, id);
    }
}
