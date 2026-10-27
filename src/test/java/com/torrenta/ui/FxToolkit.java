package com.torrenta.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Application;
import javafx.application.Platform;

import com.torrenta.i18n.Messages;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The JavaFX toolkit for tests, started once per JVM on JavaFX's own headless platform
 * ({@code glass.platform=Headless}, software rendering): no display, no Xvfb, the same on every
 * CI machine. Started as the app starts it, minus what reaches outside: the English catalogue,
 * Inter, the default theme. The toolkit outlives every window ({@code implicitExit} off), and
 * every window is hidden after each test so the next one starts with none.
 */
public final class FxToolkit implements BeforeAllCallback, AfterEachCallback {

    private static boolean started;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        start();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Fx.closeAll();
    }

    static synchronized void start() throws Exception {
        if (started) return;
        System.setProperty("glass.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("java.awt.headless", "true");
        CompletableFuture<Void> up = new CompletableFuture<>();
        Platform.startup(() -> up.complete(null));
        up.get(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        Platform.setImplicitExit(false);
        Fx.run(() -> {
            Messages.init("en");
            Fonts.load();
            Application.setUserAgentStylesheet(Theme.DEFAULT.stylesheet());
        });
        started = true;
    }
}
