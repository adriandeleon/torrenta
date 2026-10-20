package com.torrenta;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.torrenta.ui.MainWindow;

/**
 * Development-only screen capture, driven entirely by system properties so it costs nothing when
 * unused.
 *
 * <p>A window's failure modes are visual and none of them throw. This renders the real window,
 * writes what came out to a PNG, prints a few facts about the scene, and exits — which is what the
 * CI smoke test and a headless review need, and what a unit test cannot do.
 *
 * <p>Run it through {@code scripts/dev-run.sh}, which can pass arbitrary {@code -D} options:
 * {@code scripts/dev-run.sh -Dtorrenta.capture=/tmp/shot.png}, or through the plugin with the
 * environment variable: {@code TORRENTA_CAPTURE=/tmp/shot.png ./mvnw javafx:run}.
 */
final class DevCapture {

    static final String CAPTURE_PROPERTY = "torrenta.capture";
    /** The same as the property, for launchers that cannot pass one ({@code mvn javafx:run}). */
    static final String CAPTURE_ENV = "TORRENTA_CAPTURE";

    static final String DELAY_PROPERTY = "torrenta.captureAfterMs";

    private DevCapture() {}

    static boolean requested() {
        return target() != null;
    }

    private static String target() {
        return System.getProperty(CAPTURE_PROPERTY, System.getenv(CAPTURE_ENV));
    }

    /**
     * Waits for the first frames to paint and the engine to come up, then snapshots the scene and
     * exits.
     *
     * <p>The delay is the point: the renderer paints on the next animation frame rather than
     * synchronously, so capturing straight after {@code show()} photographs an empty window, and
     * the engine starts on a worker after the window is shown.
     */
    static void schedule(Stage stage, MainWindow window) {
        long delay = Long.getLong(DELAY_PROPERTY, 3000);
        PauseTransition settle = new PauseTransition(Duration.millis(delay));
        settle.setOnFinished(e -> {
            try {
                capture(stage, window, new File(target()));
            } catch (IOException | RuntimeException ex) {
                System.err.println("capture failed: " + ex);
            }
            Platform.exit();
        });
        settle.play();
    }

    private static void capture(Stage stage, MainWindow window, File out) throws IOException {
        Scene scene = stage.getScene();
        WritableImage shot = scene.snapshot(null);
        int w = (int) shot.getWidth();
        int h = (int) shot.getHeight();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = shot.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        ImageIO.write(image, "png", out);
        // Read by the smoke test; a screenshot alone proves only that a window appeared.
        System.out.println("capture: " + out + " " + w + "x" + h);
        System.out.println("capture: title=" + stage.getTitle());
        System.out.println("capture: focus=" + scene.getFocusOwner());
        System.out.println("capture: engine=" + window.engineDescription());
        System.out.println("capture: status=" + window.statusText());
    }
}
