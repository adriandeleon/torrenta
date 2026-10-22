package com.torrenta.store;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.EngineProfile;

/**
 * The engine profiles: {@code profiles.json} in the config folder, one entry per remote daemon.
 *
 * <p>Written whole and atomically on every change, owner-readable only where the file system has
 * modes (a password is in it), never synced, never logged with its passwords. The local engine is
 * not in the file: {@link EngineProfile#LOCAL} is always first in {@link #all()}. Synchronized:
 * the Settings window writes on the FX thread while App reads at start.
 */
public final class ProfileStore {

    private static final Logger LOG = Logger.getLogger(ProfileStore.class.getName());
    private static final JsonFactory JSON = new JsonFactory();

    public static final String FILE = "profiles.json";

    private final Path dir;
    private final List<EngineProfile> entries = new ArrayList<>();

    public ProfileStore(Path dir) {
        this.dir = dir;
    }

    public Path file() {
        return dir.resolve(FILE);
    }

    /** Reads the file; missing is empty, unreadable is empty and a log line (without the contents). */
    public synchronized void load() {
        entries.clear();
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        try {
            entries.addAll(parse(Files.readString(file)));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read " + file + "; no remote engine profiles", e);
            entries.clear();
        }
    }

    /** The remote profiles, in the file's order. */
    public synchronized List<EngineProfile> entries() {
        return List.copyOf(entries);
    }

    /** The local engine first, then the remote profiles. */
    public synchronized List<EngineProfile> all() {
        List<EngineProfile> out = new ArrayList<>();
        out.add(EngineProfile.LOCAL);
        out.addAll(entries);
        return List.copyOf(out);
    }

    /** The profile with that id, the local one for its id, else empty. */
    public synchronized Optional<EngineProfile> byId(String id) {
        if (id == null || id.isBlank() || id.equals(EngineProfile.LOCAL_ID)) return Optional.of(EngineProfile.LOCAL);
        return entries.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Replaces the remote profiles and writes them at once; the local one is dropped if given. */
    public synchronized void set(List<EngineProfile> list) throws IOException {
        entries.clear();
        for (EngineProfile p : list) if (!p.isLocal()) entries.add(p);
        write(file(), format(entries));
    }

    /** Beside the file and moved into place; owner-only where the file system has modes. */
    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".part");
        try {
            restrict(tmp);
            Files.writeString(tmp, text);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** {@code rw-------}; a file system without POSIX modes (Windows) is left as it is. */
    static void restrict(Path file) {
        try {
            Set<PosixFilePermission> owner = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, owner);
        } catch (UnsupportedOperationException | IOException e) {
            LOG.log(Level.FINE, "No POSIX modes for " + file, e);
        }
    }

    // ---- the format, pure -----------------------------------------------------------------------

    /** {@code [{"id":"…","name":"…","kind":"remote","url":"…","username":"…","password":"…"}, …]}. */
    public static String format(Collection<EngineProfile> entries) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (EngineProfile p : entries) {
            if (p.isLocal()) continue;
            sb.append(i++ == 0 ? "\n  " : ",\n  ").append(formatEntry(p));
        }
        if (i > 0) sb.append('\n');
        return sb.append("]\n").toString();
    }

    private static String formatEntry(EngineProfile p) {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("id", p.id());
            g.writeStringField("name", p.name());
            g.writeStringField("kind", p.kind().name().toLowerCase(java.util.Locale.ROOT));
            g.writeStringField("url", p.url());
            g.writeStringField("username", p.username());
            g.writeStringField("password", p.password());
            g.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** The inverse; an entry without an id or a URL is skipped; anything unreadable throws. */
    public static List<EngineProfile> parse(String text) throws IOException {
        List<EngineProfile> out = new ArrayList<>();
        try (JsonParser p = JSON.createParser(text == null ? "" : text)) {
            if (p.nextToken() != JsonToken.START_ARRAY) throw new IOException("not a list of profiles");
            JsonToken entry;
            while ((entry = p.nextToken()) != JsonToken.END_ARRAY) {
                if (entry == null) throw new IOException("the list is cut short");
                if (entry != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }
                String id = "";
                String name = "";
                String url = "";
                String username = "";
                String password = "";
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String field = p.currentName();
                    JsonToken value = p.nextToken();
                    if (value == JsonToken.VALUE_STRING) {
                        switch (field) {
                            case "id" -> id = p.getText();
                            case "name" -> name = p.getText();
                            case "url" -> url = p.getText();
                            case "username" -> username = p.getText();
                            case "password" -> password = p.getText();
                            default -> {}
                        }
                    } else {
                        p.skipChildren();
                    }
                }
                if (!id.isBlank() && !url.isBlank() && !id.equals(EngineProfile.LOCAL_ID)) {
                    out.add(new EngineProfile(id, name, EngineProfile.Kind.REMOTE, url, username, password));
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("not a list of profiles: " + e.getMessage(), e);
        }
        return out;
    }
}
