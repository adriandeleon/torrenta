package com.torrenta.sync;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.torrenta.search.Definition;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.store.SearchProviderStore;
import org.jspecify.annotations.Nullable;

/**
 * The search providers that travel, as entries {@link SyncMerge} merges one at a time. Pure.
 *
 * <p>A Torznab endpoint is {@code torznab.<url>}, its value the line the repository's
 * {@value #TORZNAB_FILE} holds for it: name, URL, enabled, and {@code keyNeeded} when the server
 * takes a key. Never the key, which is a secret, and never the Test's category count, which is
 * what this machine last saw. A machine that receives an endpoint whose server takes a key keeps
 * its own key for that URL when it has one; when it has none the endpoint is
 * {@link TorznabEndpoint#keyNeeded} until the key is typed in there. The URL is the identity, so a
 * URL edited on one machine is a removal and an addition on the others, the key to be typed again.
 *
 * <p>A definition file is {@code definition.<id>}, its value the file's text, in
 * {@value #DEFINITIONS}{@code <id>.json} in the repository as in the config folder. A file whose
 * text is the shipped example's is left out, as a default is: every machine has it already, and a
 * machine whose copy is unchanged would otherwise send its copy against another's edit. So an
 * example that returns to its shipped text is a removal, which another machine applies by
 * writing the shipped text back, not by deleting its file. Only names a definition can have
 * ({@link Definition#ID}) travel.
 */
public final class SyncSearch {

    public static final String TORZNAB_FILE = SearchProviderStore.FILE;
    public static final String DEFINITIONS = "search-providers/";
    static final String TORZNAB = "torznab.";
    static final String DEFINITION = "definition.";
    private static final String SUFFIX = ".json";
    private static final JsonFactory JSON = new JsonFactory();

    /**
     * What a sync changes in this machine's definition folder.
     *
     * @param put file texts to write, by id
     * @param removed ids gone from the merge: deleted, or the shipped text written back for an example
     */
    public record DefinitionChange(Map<String, String> put, List<String> removed) {
        public boolean isEmpty() {
            return put.isEmpty() && removed.isEmpty();
        }
    }

    private SyncSearch() {}

    // ---- this machine's side --------------------------------------------------------------------

    /**
     * The entries for this machine's lists.
     *
     * @param definitions each definition file's text by id (the file's name without {@code .json})
     * @param shipped the shipped examples' texts by id
     */
    public static Map<String, String> entries(
            Collection<TorznabEndpoint> endpoints, Map<String, String> definitions, Map<String, String> shipped) {
        Map<String, String> out = new TreeMap<>();
        for (TorznabEndpoint e : endpoints) {
            if (!e.url().isEmpty()) out.putIfAbsent(TORZNAB + e.url(), line(e));
        }
        definitions.forEach((id, text) -> {
            if (isDefinitionId(id) && text != null && !text.equals(shipped.get(id))) out.put(DEFINITION + id, text);
        });
        return out;
    }

    /** Whether {@code id} can name a definition file that travels. */
    public static boolean isDefinitionId(String id) {
        return id != null && Definition.ID.matcher(id).matches();
    }

    /** One endpoint as the repository holds it: no key, no count. */
    static String line(TorznabEndpoint e) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("type", "torznab");
            g.writeStringField("name", e.name());
            g.writeStringField("url", e.url());
            g.writeBooleanField("enabled", e.enabled());
            g.writeBooleanField("keyNeeded", e.takesKey());
            g.writeEndObject();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out.toString();
    }

    /**
     * This machine's endpoints after a sync that merged to {@code merged}: those still there in
     * their order, the merge's name and switch on them, then the ones that arrived. A key stays where
     * the server still takes one; an arrival that takes one, and an endpoint here that had none, is
     * left waiting for it. Two endpoints here with one URL are both kept and both follow the merge.
     * {@link #entries} of the answer gives {@code merged}'s endpoints back.
     */
    public static List<TorznabEndpoint> endpoints(List<TorznabEndpoint> local, Map<String, String> merged)
            throws IOException {
        Map<String, TorznabEndpoint> theirs = new LinkedHashMap<>();
        for (var entry : new TreeMap<>(merged).entrySet()) {
            if (!entry.getKey().startsWith(TORZNAB)) continue;
            for (TorznabEndpoint e : SearchProviderStore.parse("[" + entry.getValue() + "]")) {
                theirs.putIfAbsent(e.url(), e);
            }
        }
        List<TorznabEndpoint> out = new ArrayList<>();
        Set<String> here = new HashSet<>();
        for (TorznabEndpoint mine : local) {
            TorznabEndpoint t = theirs.get(mine.url());
            if (t == null) continue;
            here.add(mine.url());
            String key = t.keyNeeded() ? mine.apiKey() : "";
            out.add(new TorznabEndpoint(t.name(), t.url(), key, t.enabled(), mine.categories(), t.keyNeeded()));
        }
        for (TorznabEndpoint t : theirs.values()) {
            if (!here.contains(t.url())) {
                out.add(new TorznabEndpoint(t.name(), t.url(), "", t.enabled(), -1, t.keyNeeded()));
            }
        }
        return out;
    }

    /** How many of {@code after}'s endpoints wait for a key that {@code before} did not wait for. */
    public static int newlyWaiting(List<TorznabEndpoint> before, List<TorznabEndpoint> after) {
        Set<String> waiting = new HashSet<>();
        for (TorznabEndpoint e : before) if (e.keyNeeded()) waiting.add(e.url());
        int n = 0;
        for (TorznabEndpoint e : after) if (e.keyNeeded() && waiting.add(e.url())) n++;
        return n;
    }

    /** What turns the definition files {@code before} into {@code after}, both as {@link #entries}. */
    public static DefinitionChange definitionChange(Map<String, String> before, Map<String, String> after) {
        Map<String, String> put = new TreeMap<>();
        List<String> removed = new ArrayList<>();
        TreeMap<String, String> keys = new TreeMap<>(before);
        keys.putAll(after);
        for (String key : keys.keySet()) {
            if (!key.startsWith(DEFINITION)) continue;
            String id = key.substring(DEFINITION.length());
            String now = after.get(key);
            if (now == null) removed.add(id);
            else if (!now.equals(before.get(key))) put.put(id, now);
        }
        return new DefinitionChange(Map.copyOf(put), List.copyOf(removed));
    }

    // ---- the repository's side ------------------------------------------------------------------

    /**
     * The entries a repository tree holds. A {@value #TORZNAB_FILE} that does not read throws: read
     * as empty, it would be every endpoint removed, on every machine.
     */
    static Map<String, String> read(RepoTree tree) throws IOException {
        Map<String, String> out = new TreeMap<>();
        String torznab = tree.file(TORZNAB_FILE);
        if (torznab != null) {
            List<TorznabEndpoint> endpoints;
            try {
                endpoints = SearchProviderStore.parse(torznab);
            } catch (IOException e) {
                throw new IOException(TORZNAB_FILE + " in the repository: " + e.getMessage(), e);
            }
            for (TorznabEndpoint e : endpoints) out.putIfAbsent(TORZNAB + e.url(), line(e));
        }
        for (String name : tree.list(DEFINITIONS)) {
            if (!name.endsWith(SUFFIX)) continue;
            String id = name.substring(0, name.length() - SUFFIX.length());
            if (!isDefinitionId(id)) continue;
            String text = tree.file(DEFINITIONS + name);
            if (text != null) out.put(DEFINITION + id, text);
        }
        return out;
    }

    /** The repository's endpoints file for {@code entries}: one endpoint per line, by URL; null when there are none. */
    static @Nullable String torznabFile(Map<String, String> entries) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (var entry : new TreeMap<>(entries).entrySet()) {
            if (!entry.getKey().startsWith(TORZNAB)) continue;
            sb.append(i++ == 0 ? "\n  " : ",\n  ").append(entry.getValue());
        }
        return i == 0 ? null : sb.append("\n]\n").toString();
    }

    /** The definition files for {@code entries}, by file name. */
    static Map<String, String> definitionFiles(Map<String, String> entries) {
        Map<String, String> out = new TreeMap<>();
        entries.forEach((key, text) -> {
            if (key.startsWith(DEFINITION)) out.put(key.substring(DEFINITION.length()) + SUFFIX, text);
        });
        return out;
    }
}
