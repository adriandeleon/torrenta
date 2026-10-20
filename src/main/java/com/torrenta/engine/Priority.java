package com.torrenta.engine;

/** A file's download priority, as the dialog and the detail pane offer it. */
public enum Priority {
    IGNORE("Skip"),
    LOW("Low"),
    NORMAL("Normal"),
    HIGH("High");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
