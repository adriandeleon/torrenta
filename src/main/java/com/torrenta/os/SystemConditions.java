package com.torrenta.os;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;

/**
 * This machine's {@link Conditions.Source}: on Linux UPower and NetworkManager over the system bus,
 * read when asked and with their {@code PropertiesChanged} as the nudge; on macOS {@code pmset -g
 * batt}; on Windows {@code GetSystemPowerStatus} through FFM and the connection cost through
 * PowerShell, asked at most every five minutes on a virtual thread since PowerShell takes its time
 * to start. Whatever cannot be read reads as the mains and an unmetered network: a rule never acts
 * on a guess. {@code -Dtorrenta.conditions} (capture only) replaces all of it with fixed values.
 */
public final class SystemConditions {

    private static final Logger LOG = Logger.getLogger(SystemConditions.class.getName());

    private SystemConditions() {}

    /** The source for this machine; never throws, {@link Conditions.Source#NONE} at worst. */
    public static Conditions.Source create() {
        String fixed = System.getProperty("torrenta.conditions");
        if (fixed != null) {
            Conditions c = Conditions.parse(fixed);
            return () -> c;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("linux")) return new Linux();
            if (os.contains("mac")) return new Mac();
            if (os.contains("win")) return new Windows();
        } catch (Exception | LinkageError e) {
            LOG.log(Level.INFO, "Battery and network cost cannot be read here: " + e.getMessage(), e);
        }
        return Conditions.Source.NONE;
    }

    /** UPower's {@code OnBattery} and NetworkManager's {@code Metered}, on the system bus. */
    static final class Linux implements Conditions.Source {
        private static final String UPOWER = "org.freedesktop.UPower";
        private static final String UPOWER_PATH = "/org/freedesktop/UPower";
        private static final String NM = "org.freedesktop.NetworkManager";
        private static final String NM_PATH = "/org/freedesktop/NetworkManager";

        private final DBusConnection conn;
        private final Properties upower;
        private final Properties nm;

        Linux() throws Exception {
            conn = DBusConnectionBuilder.forSystemBus().build();
            upower = conn.getRemoteObject(UPOWER, UPOWER_PATH, Properties.class);
            nm = conn.getRemoteObject(NM, NM_PATH, Properties.class);
        }

        @Override
        public Conditions read() {
            boolean battery = Boolean.TRUE.equals(get(upower, UPOWER, "OnBattery"));
            Object metered = get(nm, NM, "Metered");
            return new Conditions(battery, metered instanceof Number n && NetworkCost.networkManager(n.longValue()));
        }

        /** A property, or null: the service is not there (a desktop without UPower), or answers oddly. */
        private static Object get(Properties props, String iface, String name) {
            try {
                Object v = props.Get(iface, name);
                return v instanceof Variant<?> var ? var.getValue() : v;
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public void onChange(Runnable listener) {
            try {
                conn.addSigHandler(Properties.PropertiesChanged.class, signal -> {
                    String path = signal.getPath();
                    Map<String, Variant<?>> changed = signal.getPropertiesChanged();
                    if ((UPOWER_PATH.equals(path) && changed.containsKey("OnBattery"))
                            || (NM_PATH.equals(path) && changed.containsKey("Metered"))) listener.run();
                });
            } catch (Exception e) {
                LOG.log(Level.FINE, "No PropertiesChanged from the system bus; read once a minute", e);
            }
        }

        @Override
        public void close() {
            try {
                conn.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Closing the system bus", e);
            }
        }
    }

    /** {@code pmset -g batt}, at most once every fifty seconds. */
    static final class Mac implements Conditions.Source {
        private volatile Conditions last = Conditions.NORMAL;
        private volatile long readAt;

        @Override
        public Conditions read() {
            long now = System.nanoTime();
            if (readAt != 0 && now - readAt < TimeUnit.SECONDS.toNanos(50)) return last;
            readAt = now;
            Optional<Boolean> battery = PowerState.pmset(run(List.of("pmset", "-g", "batt")));
            last = new Conditions(battery.orElse(false), false);
            return last;
        }
    }

    /** {@code GetSystemPowerStatus} each time; the cost through PowerShell every five minutes. */
    static final class Windows implements Conditions.Source {
        private volatile boolean metered;
        private volatile long costAt;
        private volatile boolean asking;

        @Override
        public Conditions read() {
            long now = System.nanoTime();
            if (!asking && (costAt == 0 || now - costAt > TimeUnit.MINUTES.toNanos(5))) {
                asking = true;
                costAt = now;
                Thread.ofVirtual().name("network-cost").start(() -> {
                    try {
                        metered = NetworkCost.windows(run(NetworkCost.windowsCommand()))
                                .orElse(false);
                    } finally {
                        asking = false;
                    }
                });
            }
            return new Conditions(PowerState.readWindows().orElse(false), metered);
        }
    }

    /** A command's output, or "" when it cannot be run or takes over fifteen seconds. No shell. */
    static String run(List<String> argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
