package com.torrenta.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The UI string catalogue, Termina's.
 *
 * <p>English lives in {@code resources/com/torrenta/i18n/messages.properties} and is both the base
 * and the fallback; each other language is an overlay, {@code messages_<lang>.properties}. Loaded
 * through {@code getResourceAsStream} and a UTF-8 reader rather than {@code ResourceBundle}, the way
 * the fonts and stylesheets are read: our own module's resources, so no {@code opens} and none of
 * ResourceBundle's module-path surprises.
 *
 * <p>{@link #tr(String)} returns the active language's value, falling back to English, then to the
 * key itself, so a missing key is visible, never an exception; each gap is logged once.
 * {@link #tr(String, Object...)} applies {@link MessageFormat} in the active locale (e.g. {@code
 * status.added = Added {0}}). The language is chosen once by {@code App.start}, after the settings
 * load and before any window is built; a change in Settings relabels on restart. Until {@link
 * #init} runs, the catalogue is English, so the pure classes and their tests need no setup.
 */
public final class Messages {

    private static final Logger LOG = Logger.getLogger(Messages.class.getName());

    /** The automatic choice: follow the operating system. The settings file's default. */
    public static final String SYSTEM = "system";

    /** Bundled UI languages: code → endonym (its own name). English is the base catalogue. */
    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();

    static {
        LANGUAGES.put("en", "English");
        LANGUAGES.put("de", "Deutsch");
        LANGUAGES.put("es", "Español");
        LANGUAGES.put("fr", "Français");
        LANGUAGES.put("it", "Italiano");
        LANGUAGES.put("pt", "Português");
    }

    private static Map<String, String> base;
    private static Map<String, String> active;
    private static String currentLang = "en";
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Messages() {}

    /** Loads the English base + the chosen language overlay (English for an unknown code). */
    public static synchronized void init(String lang) {
        base = load("en");
        currentLang = LANGUAGES.containsKey(lang) ? lang : "en";
        active = "en".equals(currentLang) ? base : load(currentLang);
        REPORTED.clear();
    }

    private static synchronized void ensureLoaded() {
        if (base == null) init("en");
    }

    private static Map<String, String> load(String lang) {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream(resourceName(lang))) {
            if (in != null) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } else {
                LOG.warning("no catalogue for " + lang);
            }
        } catch (IOException e) {
            LOG.warning("unreadable catalogue for " + lang + ": " + e.getMessage());
        }
        Map<String, String> m = new HashMap<>();
        for (String key : p.stringPropertyNames()) {
            m.put(key, p.getProperty(key));
        }
        return m;
    }

    /** The classpath resource of a language's catalogue. */
    public static String resourceName(String lang) {
        return "en".equals(lang)
                ? "/com/torrenta/i18n/messages.properties"
                : "/com/torrenta/i18n/messages_" + lang + ".properties";
    }

    /** The localized string for {@code key} (active → English → the key itself). */
    public static String tr(String key) {
        ensureLoaded();
        String v = active.get(key);
        if (v == null) {
            v = base.get(key);
            if (v == null) {
                if (REPORTED.add(key)) LOG.warning("no text for " + key + " in any language");
                return key;
            }
            if (REPORTED.add(key)) LOG.info("no " + currentLang + " text for " + key + ", showing English");
        }
        return v;
    }

    /** The localized string for {@code key} with {@link MessageFormat} arguments, in the active locale. */
    public static String tr(String key, Object... args) {
        String pattern = tr(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return new MessageFormat(pattern, locale()).format(args);
        } catch (RuntimeException e) {
            return pattern; // a malformed pattern must not take the window down
        }
    }

    /** The active language code (e.g. {@code "es"}). */
    public static String current() {
        ensureLoaded();
        return currentLang;
    }

    /** The active language as a locale, for MessageFormat and the number formats. */
    public static Locale locale() {
        return Locale.forLanguageTag(current());
    }

    /** Bundled languages: code → endonym, in display order (English first). */
    public static Map<String, String> available() {
        return new LinkedHashMap<>(LANGUAGES);
    }

    /** The endonym for a language code ({@code "es"} → {@code "Español"}); the code itself if unknown. */
    public static String languageName(String code) {
        return LANGUAGES.getOrDefault(code, code);
    }

    /** Test hook: the English text of {@code key}, or null. */
    static synchronized String englishForTest(String key) {
        ensureLoaded();
        return base.get(key);
    }

    /** Test hook: forgets {@code key} in the active overlay, never in English. */
    static synchronized void dropForTest(String key) {
        ensureLoaded();
        if (active != base) active.remove(key);
    }

    /** Test hook: a key in both the active and the English catalogue. */
    static synchronized void setForTest(String key, String value) {
        ensureLoaded();
        base.put(key, value);
        active.put(key, value);
    }

    /**
     * The effective UI language: the explicit {@code pref} if bundled, else the {@code systemLang} if
     * bundled, else English. {@link #SYSTEM} and blank both mean "follow the system". Pure.
     */
    public static String resolve(String pref, Set<String> available, String systemLang) {
        if (pref != null && available.contains(pref)) {
            return pref;
        }
        if (systemLang != null && available.contains(systemLang)) {
            return systemLang;
        }
        return "en";
    }

    /** Whether {@code pref} asks to follow the operating system: blank or {@link #SYSTEM}. */
    public static boolean isAutomatic(String pref) {
        return pref == null || pref.isBlank() || SYSTEM.equalsIgnoreCase(pref.trim());
    }

    /**
     * The JVM default locale to run with, given what the user asked for.
     *
     * <p>Only an explicit choice moves the default off the OS locale. Automatic means "follow the
     * system", and the system's locale carries more than a language: the country decides number
     * and date formats, and {@code de_AT} collapsed to {@code de} loses it. Someone whose OS speaks
     * a language we do not bundle sees English menus either way; that is no reason to also change
     * how their dates are written. An explicit choice keeps the country when the language is the
     * system's own, for the same reason.
     *
     * @param pref the stored language preference, blank or {@link #SYSTEM} for automatic
     * @param resolved the language the catalogue was initialised with
     * @param system the OS locale
     */
    public static Locale defaultLocale(String pref, String resolved, Locale system) {
        if (isAutomatic(pref)) return system;
        if (system != null && system.getLanguage().equals(resolved)) return system;
        return Locale.forLanguageTag(resolved);
    }
}
