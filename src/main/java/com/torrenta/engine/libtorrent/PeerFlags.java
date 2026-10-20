package com.torrenta.engine.libtorrent;

import org.libtorrent4j.PeerInfo;
import org.libtorrent4j.swig.peer_info;

/**
 * A peer's flags as a short string, the way qBittorrent and Deluge show them, so a glance says
 * what is going on with a connection:
 *
 * <ul>
 *   <li>{@code D} downloading from it, {@code d} we are interested but it chokes us
 *   <li>{@code U} uploading to it, {@code u} it is interested but we choke it
 *   <li>{@code S} it is a seed, {@code I} incoming connection, {@code E} encrypted, {@code P} uTP
 *   <li>{@code H} found by DHT, {@code X} by PEX, {@code L} by LSD, {@code W} a web seed
 * </ul>
 *
 * <p>The bit values come from libtorrent's own constants at run time, read once after the native
 * has loaded (the holder), never hard-coded.
 */
final class PeerFlags {

    private PeerFlags() {}

    private static final class Bits {
        static final int INTERESTING = peer_info.interesting.to_int();
        static final int CHOKED = peer_info.choked.to_int();
        static final int REMOTE_INTERESTED = peer_info.remote_interested.to_int();
        static final int REMOTE_CHOKED = peer_info.remote_choked.to_int();
        static final int LOCAL_CONNECTION = peer_info.local_connection.to_int();
        static final int SEED = peer_info.seed.to_int();
        static final int UTP = peer_info.utp_socket.to_int();
        static final int RC4 = peer_info.rc4_encrypted.to_int();
        static final int PLAINTEXT = peer_info.plaintext_encrypted.to_int();
        static final int SRC_DHT = peer_info.dht.to_int();
        static final int SRC_PEX = peer_info.pex.to_int();
        static final int SRC_LSD = peer_info.lsd.to_int();
    }

    static String text(int flags, int source, PeerInfo.ConnectionType type) {
        StringBuilder out = new StringBuilder();
        boolean interesting = (flags & Bits.INTERESTING) != 0;
        boolean choked = (flags & Bits.CHOKED) != 0;
        boolean remoteInterested = (flags & Bits.REMOTE_INTERESTED) != 0;
        boolean remoteChoked = (flags & Bits.REMOTE_CHOKED) != 0;
        if (interesting) out.append(choked ? 'd' : 'D');
        if (remoteInterested) out.append(remoteChoked ? 'u' : 'U');
        if ((flags & Bits.SEED) != 0) out.append('S');
        if ((flags & Bits.LOCAL_CONNECTION) == 0) out.append('I');
        if ((flags & (Bits.RC4 | Bits.PLAINTEXT)) != 0) out.append('E');
        if ((flags & Bits.UTP) != 0) out.append('P');
        if ((source & Bits.SRC_DHT) != 0) out.append('H');
        if ((source & Bits.SRC_PEX) != 0) out.append('X');
        if ((source & Bits.SRC_LSD) != 0) out.append('L');
        if (type == PeerInfo.ConnectionType.WEB_SEED || type == PeerInfo.ConnectionType.HTTP_SEED) out.append('W');
        return out.toString();
    }
}
