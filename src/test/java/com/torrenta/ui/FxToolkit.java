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
 *
 * <p>{@code glass.platform} and {@code prism.order} come from surefire's
 * {@code systemPropertyVariables}, set before any test class loads. Setting them here is too late
 * in a full run: Prism reads them once, in static initialisers, and any earlier test that makes a
 * {@code Node} ({@code IconsTest}, …) runs those without starting the toolkit. ES2 was then chosen
 * and the start crashed the fork in {@code libGLX_nvidia} on a desktop with an NVIDIA GPU. The
 * lines below cover a run outside Maven, such as an IDE's of this class alone.
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
