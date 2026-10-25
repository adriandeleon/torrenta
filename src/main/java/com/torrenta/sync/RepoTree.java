package com.torrenta.sync;

import java.io.IOException;
import java.util.List;

/** A tree of files a sync part reads its entries from: the clone's working tree, or a commit's. */
interface RepoTree {

    /** The file's text, or null when there is none. */
    String file(String path) throws IOException;

    /** The names of the files directly in {@code folder} (a path ending in {@code /}); none when it is absent. */
    List<String> list(String folder) throws IOException;
}
