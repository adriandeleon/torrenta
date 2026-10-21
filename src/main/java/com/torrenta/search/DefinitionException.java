package com.torrenta.search;

/**
 * A definition file that cannot be used, said with the file and the key at fault:
 * "linuxtracker.json: search.url: is missing".
 */
public final class DefinitionException extends Exception {

    private final String file;
    private final String field;

    public DefinitionException(String file, String field, String problem) {
        super((file == null || file.isEmpty() ? "" : file + ": ")
                + (field == null || field.isEmpty() ? "" : field + ": ")
                + problem);
        this.file = file == null ? "" : file;
        this.field = field == null ? "" : field;
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
