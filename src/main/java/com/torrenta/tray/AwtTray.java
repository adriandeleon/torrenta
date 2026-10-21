package com.torrenta.tray;

import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;

import static com.torrenta.i18n.Messages.tr;

/**
 * The AWT tray backend for Windows and macOS. All AWT mutations are marshaled onto the EDT; the
 * icon is a multi-resolution image so HiDPI trays pick a crisp variant. The menu mirrors the SNI
 * one: a disabled live status line, Show Torrenta, Quit.
 *
 * <p>Needs AWT to be headful: {@code App.main} lifts the {@code java.awt.headless} guard only where
 * this backend is wanted, which is why Linux never reaches it (see {@link Trays}).
 */
public final class AwtTray implements TorrentaTray {

    private static final int[] ICON_SIZES = {16, 24, 32, 48};

    private final SystemTray tray;
    private final TrayIcon trayIcon;
    private final MenuItem statusItem;

    public AwtTray(Runnable onActivate, Runnable onQuit) throws Exception {
        if (!SystemTray.isSupported()) {
            throw new UnsupportedOperationException("the AWT system tray is unsupported on this desktop");
        }
        tray = SystemTray.getSystemTray();

        statusItem = new MenuItem(tr("tray.menu.starting"));
        statusItem.setEnabled(false);
        MenuItem open = new MenuItem(tr("tray.menu.show"));
        open.addActionListener(e -> onActivate.run());
        MenuItem quit = new MenuItem(tr("tray.menu.quit"));
        quit.addActionListener(e -> onQuit.run());
        PopupMenu menu = new PopupMenu();
        menu.add(statusItem);
        menu.addSeparator();
        menu.add(open);
        menu.add(quit);

        trayIcon = new TrayIcon(multiRes(TrayDisplay.idle()), "Torrenta", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> onActivate.run());
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) onActivate.run();
            }
        });
        // add() on the EDT, but fail construction synchronously if the desktop refuses.
        Exception[] failure = new Exception[1];
        EventQueue.invokeAndWait(() -> {
            try {
                tray.add(trayIcon);
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
    }

    @Override
    public void update(TrayDisplay display, String line) {
        BaseMultiResolutionImage image = multiRes(display);
        EventQueue.invokeLater(() -> {
            trayIcon.setImage(image);
            trayIcon.setToolTip(line);
            statusItem.setLabel(line);
        });
    }

    @Override
    public void dispose() {
        EventQueue.invokeLater(() -> tray.remove(trayIcon));
    }

    @Override
    public String backend() {
        return "awt";
    }

    private static BaseMultiResolutionImage multiRes(TrayDisplay display) {
        BufferedImage[] variants = new BufferedImage[ICON_SIZES.length];
        for (int i = 0; i < ICON_SIZES.length; i++) {
            variants[i] = TrayIconRenderer.render(display, ICON_SIZES[i]);
        }
        return new BaseMultiResolutionImage(variants);
    }
}
