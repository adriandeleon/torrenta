package com.torrenta.i18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.torrenta.engine.WatchFolders;
import com.torrenta.rss.EpisodeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The string catalogues: six languages, one key set, one placeholder set per key. */
class MessagesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)");

    static Properties catalogue(String lang) throws Exception {
        Properties p = new Properties();
        try (InputStream in = MessagesTest.class.getResourceAsStream(Messages.resourceName(lang))) {
            assertTrue(in != null, "missing catalogue for " + lang);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @AfterEach
    void english() {
        Messages.init("en");
    }

    @Test
    void sixLanguagesEnglishFirst() {
        assertEquals(
                List.of("en", "de", "es", "fr", "it", "pt"),
                new ArrayList<>(Messages.available().keySet()));
        assertEquals("Español", Messages.languageName("es"));
        assertEquals("tlh", Messages.languageName("tlh"), "an unknown code shows itself");
    }

    @Test
    void everyLanguageHasExactlyTheKeysEnglishHas() throws Exception {
        // Drift is the failure mode, and it is silent: a key added to English and forgotten
        // elsewhere shows English to those users, which nobody testing in English will ever see.
        Set<String> english = new TreeSet<>(catalogue("en").stringPropertyNames());
        assertTrue(english.size() > 500, "the whole UI is in the catalogue, not a sample: " + english.size());
        for (String lang : Messages.available().keySet()) {
            if ("en".equals(lang)) continue;
            Set<String> theirs = new TreeSet<>(catalogue(lang).stringPropertyNames());
            Set<String> missing = new TreeSet<>(english);
            missing.removeAll(theirs);
            Set<String> extra = new TreeSet<>(theirs);
            extra.removeAll(english);
            assertEquals(Set.of(), missing, lang + " is missing keys");
            assertEquals(Set.of(), extra, lang + " has keys English does not");
        }
    }

    @Test
    void noValueIsEmpty() throws Exception {
        for (String lang : Messages.available().keySet()) {
            Properties theirs = catalogue(lang);
            for (String key : theirs.stringPropertyNames()) {
                assertFalse(theirs.getProperty(key).isBlank(), lang + "/" + key + " is empty");
            }
        }
    }

    @Test
    void placeholdersMatchAcrossLanguagesAndEveryPatternParses() throws Exception {
        // An apostrophe in a MessageFormat pattern quotes what follows, so a French or Italian
        // translation of a parameterised string eats its own {0} unless the quote is doubled. The
        // symptom is a name that simply never appears. A dropped or invented {1} is the same bug.
        Properties english = catalogue("en");
        for (String lang : Messages.available().keySet()) {
            Properties theirs = catalogue(lang);
            Locale locale = Locale.forLanguageTag(lang);
            for (String key : english.stringPropertyNames()) {
                Set<String> expected = indices(english.getProperty(key));
                String value = theirs.getProperty(key);
                if (value == null) continue; // the key-set test says so
                if (expected.isEmpty() && !value.contains("{")) continue;
                MessageFormat format = new MessageFormat(value, locale);
                assertEquals(expected, indices(value), lang + "/" + key + " placeholders differ");
                Object[] args = new Object[expected.size()];
                for (int i = 0; i < args.length; i++) args[i] = "XYZZY" + i;
                String formatted;
                try {
                    formatted = format.format(args);
                } catch (IllegalArgumentException e) {
                    // A choice or number placeholder wants a number: give it one and check the rest.
                    for (int i = 0; i < args.length; i++) args[i] = 1;
                    formatted = format.format(args);
                    continue;
                }
                for (String index : expected) {
                    assertTrue(
                            formatted.contains("XYZZY" + index),
                            lang + "/" + key + " lost its argument {" + index + "}: " + formatted);
                }
            }
        }
    }

    private static final Pattern CHOICE = Pattern.compile("\\{\\d+,choice,((?:[^{}]|\\{[^{}]*\\})*)\\}");

    @Test
    void apostrophesInsideChoiceLimbsAreQuotedForTheRightNumberOfPasses() throws Exception {
        // A choice limb that carries a {0} is parsed again as a pattern of its own, so an apostrophe
        // there needs four quotes to come out as one; a limb without a brace is taken as it is, so
        // two quotes are right there and four show two. Either slip is invisible in English.
        List<String> wrong = new ArrayList<>();
        for (String lang : Messages.available().keySet()) {
            Properties theirs = catalogue(lang);
            for (String key : theirs.stringPropertyNames()) {
                Matcher choice = CHOICE.matcher(theirs.getProperty(key));
                while (choice.find()) {
                    for (String limb : choice.group(1).split("\\|")) {
                        boolean nested = limb.contains("{");
                        boolean two =
                                Pattern.compile("(?<!')''(?!')").matcher(limb).find();
                        boolean four = limb.contains("''''");
                        if ((nested && two) || (!nested && four)) wrong.add(lang + "/" + key + ": " + limb);
                    }
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    private static Set<String> indices(String pattern) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(pattern);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void englishIsTheCatalogueBeforeAnyChoice() {
        assertEquals("en", Messages.current());
        assertEquals(Locale.ENGLISH, Messages.locale());
    }

    @Test
    void aTranslationIsActuallyUsedAndFallsBackPerKey() throws Exception {
        Properties english = catalogue("en");
        Properties spanish = catalogue("es");
        String key = "menu.file";
        Messages.init("es");
        assertEquals("es", Messages.current());
        assertEquals(spanish.getProperty(key), Messages.tr(key));
        assertNotEquals(english.getProperty(key), Messages.tr(key), "the Spanish word differs from the English one");
        Messages.init("en");
        assertEquals(english.getProperty(key), Messages.tr(key));
    }

    @Test
    void textsBuiltOutsideTheWindowFollowTheLanguageToo() {
        // Engine, rule and watch-folder texts are composed far from any control; they used to be
        // English under every language because nothing in a text position gave them away.
        Messages.init("de");
        assertEquals("Es werden keine Ordner überwacht.", WatchFolders.statusText(0, List.of(), true));
        assertEquals(
                "2 Ordner überwacht, alle 60 Sekunden und sobald Dateien erscheinen.",
                WatchFolders.statusText(2, List.of(), true));
        assertEquals(
                java.util.Optional.of("„1x5-2“ läuft rückwärts: der Bereich endet, bevor er beginnt"),
                EpisodeFilter.problem("1x5-2"));
        Messages.init("fr");
        assertEquals(
                "Ajouté, mais impossible de renommer a.torrent du dossier surveillé /w : x",
                Messages.tr("engine.watch.renameFailed", "a.torrent", "/w", "x"));
        assertEquals(" ; renommé en a.torrent.invalid", Messages.tr("engine.watch.setAside", "a.torrent.invalid"));
    }

    @Test
    void anUnknownKeyShowsItselfAndIsLoggedOnce() {
        Messages.init("en");
        List<LogRecord> records = new ArrayList<>();
        Logger log = Logger.getLogger(Messages.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        log.addHandler(handler);
        try {
            assertEquals("no.such.key", Messages.tr("no.such.key"));
            assertEquals("no.such.key", Messages.tr("no.such.key"));
            assertEquals("no.such.key", Messages.tr("no.such.key", "arg"));
        } finally {
            log.removeHandler(handler);
        }
        List<LogRecord> about = records.stream()
                .filter(r -> r.getMessage().contains("no.such.key"))
                .toList();
        assertEquals(1, about.size(), "logged once, not on every paint");
        assertEquals(Level.WARNING, about.get(0).getLevel());
    }

    @Test
    void aMissingTranslationFallsBackToEnglishAndIsLoggedOnce() {
        // The overlay is loaded, then one key is taken out of it: the English text shows, with
        // one INFO line, never the bare key and never an exception.
        Messages.init("es");
        List<LogRecord> records = new ArrayList<>();
        Logger log = Logger.getLogger(Messages.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        log.addHandler(handler);
        Level was = log.getLevel();
        log.setLevel(Level.ALL);
        try {
            String english = Messages.englishForTest("menu.file");
            Messages.dropForTest("menu.file");
            assertEquals(english, Messages.tr("menu.file"));
            assertEquals(english, Messages.tr("menu.file"));
        } finally {
            log.removeHandler(handler);
            log.setLevel(was);
        }
        List<LogRecord> about = records.stream()
                .filter(r -> r.getMessage().contains("menu.file"))
                .toList();
        assertEquals(1, about.size(), "logged once");
        assertEquals(Level.INFO, about.get(0).getLevel());
    }

    @Test
    void messageFormatArgumentsAreApplied() {
        Messages.init("en");
        assertEquals("no.such.key", Messages.tr("no.such.key", 1, 2));
        Messages.setForTest("test.plural", "{0,choice,0#no peers|1#one peer|1<{0} peers}");
        assertEquals("no peers", Messages.tr("test.plural", 0));
        assertEquals("one peer", Messages.tr("test.plural", 1));
        assertEquals("7 peers", Messages.tr("test.plural", 7));
        Messages.setForTest("test.broken", "{0,choice}");
        assertEquals("{0,choice}", Messages.tr("test.broken", 1), "a malformed pattern shows itself, no exception");
    }

    @Test
    void theLanguageIsResolvedFromThePreferenceThenTheSystem() {
        Set<String> available = Messages.available().keySet();
        assertEquals("en", Messages.resolve("kl", available, "kl"));
        assertEquals("de", Messages.resolve(null, available, "de"));
        assertEquals("fr", Messages.resolve("fr", available, "de"));
        assertTrue(Messages.isAutomatic("system"));
        assertTrue(Messages.isAutomatic(""));
        assertTrue(Messages.isAutomatic(null));
        assertFalse(Messages.isAutomatic("es"));
        Messages.init("tlh");
        assertEquals("en", Messages.current(), "an unbundled code is English");
    }

    @Test
    void automaticKeepsTheOperatingSystemsLocaleWhole() {
        // The country decides dates and numbers; de_AT reduced to de loses it, and a Klingon OS
        // that falls back to English menus is no reason to also change how its dates are written.
        Locale austria = Locale.forLanguageTag("de-AT");
        assertEquals(austria, Messages.defaultLocale("system", "de", austria));
        assertEquals(austria, Messages.defaultLocale("", "de", austria));
        Locale klingon = Locale.forLanguageTag("tlh-US");
        assertEquals(klingon, Messages.defaultLocale(null, "en", klingon));
    }

    @Test
    void anExplicitChoiceMovesTheLocaleButKeepsAMatchingCountry() {
        Locale austria = Locale.forLanguageTag("de-AT");
        assertEquals(Locale.forLanguageTag("fr"), Messages.defaultLocale("fr", "fr", austria));
        assertEquals(austria, Messages.defaultLocale("de", "de", austria), "same language: the OS country stays");
    }
}
