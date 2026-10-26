package com.torrenta.search;

import org.jspecify.annotations.Nullable;

/**
 * A definition file that cannot be used, said with the file and the key at fault:
 * "linuxtracker.json: search.url: is missing".
 */
public final class DefinitionException extends Exception {

    private final String file;
    private final String field;

    public DefinitionException(@Nullable String file, @Nullable String field, String problem) {
        super((file == null || file.isEmpty() ? "" : file + ": ")
                + (field == null || field.isEmpty() ? "" : field + ": ")
                + problem);
        this.file = file == null ? "" : file;
        this.field = field == null ? "" : field;
    }

    /** The whole sentence, never null. */
    @Override
    public String getMessage() {
        return java.util.Objects.requireNonNullElse(super.getMessage(), "");
    }

    /** The file's name, as given to the parser; empty when none was. */
    public String file() {
        return file;
    }

    /** The key at fault, dotted ("search.url", "fields.name.regex"); empty for the whole file. */
    public String field() {
        return field;
    }
}
