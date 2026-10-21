package com.torrenta.engine;

import static com.torrenta.i18n.Messages.tr;

/** A file's download priority, as the dialog and the detail pane offer it. */
public enum Priority {
    IGNORE("skip"),
    LOW("low"),
    NORMAL("normal"),
    HIGH("high");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    /** The ladder's word in the interface language, read at call time (the language is chosen at start). */
    public String label() {
        return tr("priority." + label);
    }
}
