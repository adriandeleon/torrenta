package com.torrenta.ui;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.torrenta.engine.Engine;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * A command typed in the omnibox after its {@code >}, with what it takes: {@code set-category
 * Films}, {@code add-tag keep}, {@code limit-down 500k}, {@code queue top}, {@code pause}. Typed
 * while the box holds a filter, it acts on every match ({@link Actions#targets}).
 *
 * <p>Pure: {@link #parse} reads the text into a verb and a value of the verb's own kind, or says
 * which part does not read. A first word that is no verb is not a command at all, and the palette
 * goes on matching the text against the commands' names as it always has.
 *
 * @param verb what to do
 * @param text the argument as typed, trimmed; empty for a verb that takes none
 * @param bytes for a rate: bytes per second, 0 for none
 * @param on for {@code sequential}
 * @param move for {@code queue}
 */
public record OmniCommand(Verb verb, String text, long bytes, boolean on, Engine.@Nullable QueueMove move) {

    /** What a verb takes. */
    enum Takes {
        NOTHING,
        NAME,
        PATH,
        RATE,
        ON_OFF,
        QUEUE_MOVE
    }

    /** The verbs, by the word typed. */
    public enum Verb {
        SET_CATEGORY("set-category", Takes.NAME, false),
        ADD_TAG("add-tag", Takes.NAME, false),
        REMOVE_TAG("remove-tag", Takes.NAME, false),
        SET_LOCATION("set-location", Takes.PATH, false),
        LIMIT_DOWN("limit-down", Takes.RATE, false),
        LIMIT_UP("limit-up", Takes.RATE, false),
        SEQUENTIAL("sequential", Takes.ON_OFF, false),
        QUEUE("queue", Takes.QUEUE_MOVE, false),
        PAUSE("pause", Takes.NOTHING, false),
        RESUME("resume", Takes.NOTHING, false),
        RECHECK("recheck", Takes.NOTHING, false),
        REANNOUNCE("reannounce", Takes.NOTHING, false),
        REMOVE("remove", Takes.NOTHING, true);

        private final String word;
        private final Takes takes;
        private final boolean destructive;

        Verb(String word, Takes takes, boolean destructive) {
            this.word = word;
            this.takes = takes;
            this.destructive = destructive;
        }

        public String word() {
            return word;
        }

        Takes takes() {
            return takes;
        }

        /** Asked about first, however few torrents it acts on. */
        public boolean destructive() {
            return destructive;
        }

        static Optional<Verb> of(String word) {
            for (Verb v : values()) if (v.word.equalsIgnoreCase(word)) return Optional.of(v);
            return Optional.empty();
        }
    }

    /** What was typed: a command, or a verb whose argument does not read and why. */
    public sealed interface Parsed {}

    public record Ready(OmniCommand command) implements Parsed {}

    public record Problem(Verb verb, String message) implements Parsed {}

    /** Above this many targets a command asks first, whatever it is. */
    public static final int ASK_ABOVE = 20;

    /**
     * The text after the {@code >}: empty when its first word is no verb.
     */
    public static Optional<Parsed> parse(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return Optional.empty();
        int space = firstSpace(t);
        String word = space < 0 ? t : t.substring(0, space);
        Optional<Verb> verb = Verb.of(word);
        if (verb.isEmpty()) return Optional.empty();
        String arg = space < 0 ? "" : unquote(t.substring(space + 1).strip());
        return Optional.of(read(verb.get(), arg));
    }

    private static Parsed read(Verb verb, String arg) {
        return switch (verb.takes()) {
            case NOTHING ->
                arg.isEmpty()
                        ? new Ready(new OmniCommand(verb, "", 0, false, null))
                        : new Problem(verb, tr("omni.takesNothing", verb.word()));
            case NAME ->
                arg.isEmpty()
                        ? new Problem(verb, tr("omni.needsName", verb.word()))
                        : new Ready(new OmniCommand(verb, arg, 0, false, null));
            case PATH ->
                arg.isEmpty()
                        ? new Problem(verb, tr("omni.needsPath", verb.word()))
                        : new Ready(new OmniCommand(verb, arg, 0, false, null));
            case RATE -> {
                Long bytes = arg.equalsIgnoreCase("none") ? Long.valueOf(0) : FilterQuery.bytes(arg);
                yield bytes == null || bytes > Integer.MAX_VALUE
                        ? new Problem(verb, tr("omni.needsRate", verb.word()))
                        : new Ready(new OmniCommand(verb, arg, bytes, false, null));
            }
            case ON_OFF ->
                switch (arg.toLowerCase(Locale.ROOT)) {
                    case "on", "yes", "true" -> new Ready(new OmniCommand(verb, arg, 0, true, null));
                    case "off", "no", "false" -> new Ready(new OmniCommand(verb, arg, 0, false, null));
                    default -> new Problem(verb, tr("omni.needsOnOff", verb.word()));
                };
            case QUEUE_MOVE -> {
                Engine.QueueMove move =
                        switch (arg.toLowerCase(Locale.ROOT)) {
                            case "top" -> Engine.QueueMove.TOP;
                            case "up" -> Engine.QueueMove.UP;
                            case "down" -> Engine.QueueMove.DOWN;
                            case "bottom" -> Engine.QueueMove.BOTTOM;
                            default -> null;
                        };
                yield move == null
                        ? new Problem(verb, tr("omni.needsQueueMove", verb.word()))
                        : new Ready(new OmniCommand(verb, arg, 0, false, move));
            }
        };
    }

    /** The palette's row for it: what it does, in words, before the targets are counted. */
    public String describe() {
        return switch (verb) {
            case SET_CATEGORY -> tr("omni.do.setCategory", text);
            case ADD_TAG -> tr("omni.do.addTag", text);
            case REMOVE_TAG -> tr("omni.do.removeTag", text);
            case SET_LOCATION -> tr("omni.do.setLocation", text);
            case LIMIT_DOWN -> bytes == 0 ? tr("omni.do.limitDownNone") : tr("omni.do.limitDown", Bytes.rate(bytes));
            case LIMIT_UP -> bytes == 0 ? tr("omni.do.limitUpNone") : tr("omni.do.limitUp", Bytes.rate(bytes));
            case SEQUENTIAL -> on ? tr("omni.do.sequentialOn") : tr("omni.do.sequentialOff");
            case QUEUE ->
                tr("omni.do.queue."
                        + Objects.requireNonNull(move, "a queue command has its move")
                                .name()
                                .toLowerCase(Locale.ROOT));
            case PAUSE -> tr("omni.do.pause");
            case RESUME -> tr("omni.do.resume");
            case RECHECK -> tr("omni.do.recheck");
            case REANNOUNCE -> tr("omni.do.reannounce");
            case REMOVE -> tr("omni.do.remove");
        };
    }

    /** It asks before it runs: a removal, or more than {@link #ASK_ABOVE} torrents. */
    public boolean asks(int targets) {
        return verb.destructive() || targets > ASK_ABOVE;
    }

    private static int firstSpace(String t) {
        for (int i = 0; i < t.length(); i++) if (Character.isWhitespace(t.charAt(i))) return i;
        return -1;
    }

    /** Quotes around the whole argument go: {@code set-category "Linux ISOs"}. */
    private static String unquote(String arg) {
        return arg.length() >= 2 && arg.startsWith("\"") && arg.endsWith("\"")
                ? arg.substring(1, arg.length() - 1).strip()
                : arg;
    }
}
