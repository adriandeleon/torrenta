package com.torrenta.session;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.torrenta.engine.Engine;

/**
 * What Remove with files hands the trash: one item per top-level entry of the torrent under its
 * save path, so a torrent's folder goes as one folder (and comes back as one) while a torrent laid
 * out without its folder goes file by file. The save path itself is never an item.
 */
public final class TrashPlan {

    private TrashPlan() {}

    /** The entries in the order the files name them, none inside another. */
    public static List<Path> entries(Engine.OnDisk onDisk) {
        Path base = onDisk.savePath().toAbsolutePath().normalize();
        Set<Path> tops = new LinkedHashSet<>();
        for (Path file : onDisk.files()) {
            Path f = file.toAbsolutePath().normalize();
            if (f.equals(base)) continue;
            tops.add(f.startsWith(base) ? base.resolve(base.relativize(f).getName(0)) : f);
        }
        List<Path> out = new ArrayList<>();
        for (Path p : tops) {
            boolean inside = false;
            for (Path q : tops) if (!q.equals(p) && p.startsWith(q)) inside = true;
            if (!inside) out.add(p);
        }
        return out;
    }
}
