# Headless: Torrenta without a window

`torrenta --headless` runs the same session the window does, with no window and without the
toolkit ever loading: on a server, a NAS, a machine reached over SSH. The packaged app has a
second launcher for it, `torrenta-nox` (`bin/torrenta-nox` beside `bin/Torrenta` on Linux; a
console program on Windows), which is `torrenta --headless` under another name, whatever else
it is given.

```bash
torrenta-nox --config-dir /srv/torrenta 'magnet:?xt=urn:btih:…'
```

What it does differently:

- **Everything is added without asking.** There is no dialog to ask in: a `.torrent` path, a URL
  to one or a magnet on the command line, one handed over by another Torrenta, a watch folder's,
  an RSS rule's, all go in with the settings' defaults. The content check still applies
  (Settings → Adding torrents → Flagged files when adding without a dialog, `add.unsafeContent`:
  skip the flagged files by default, refuse the torrent, or add them anyway). A link that is not
  a torrent goes to curl, as from the window.
- **The log is stdout.** One line per event, `HH:MM:SS LEVEL message`, the same lines the window's
  Event Log shows, and the same lines in `torrenta-session.log` in the config folder (with every
  warning from the libraries beside them).
- **SIGTERM stops it cleanly**, and so does Ctrl+C: the engine stops as it does at a window's Quit,
  waiting (up to five seconds) for the last resume data, so nothing re-checks on the next start.
  An engine that cannot start ends the process with status 1.

Everything else is the window's: the settings file, the watch folders, the RSS feeds and rules,
the IP filter, the speed schedule, share limits, run-on-event commands and notifications, a remote
profile (`engine.profile` picks a Transmission daemon). Edit the settings with the window on the
same config folder, or by hand while Torrenta is stopped: `settings.properties` is a plain
properties file, and hand edits are kept.

## Remote access: Sonarr, Radarr and the phone apps

A server is where the *arr apps live, and they speak qBittorrent's WebUI API, which Torrenta
speaks at `/api/v2` (Settings → Remote access in the window). With no window to type a password
into, set it from the command line while Torrenta is stopped: asked twice at a terminal, or read
as one line from stdin. Only its salted PBKDF2 hash is kept.

```bash
torrenta-nox --config-dir /srv/torrenta --set-remote-password
```

Then switch it on in `settings.properties` (all `webapi.*` keys are this machine's and never sync):

```properties
webapi.enabled=true
# 127.0.0.1 until you choose: this machine alone. 0.0.0.0 is every network it is on.
webapi.address=127.0.0.1
webapi.port=8080
webapi.username=admin
# Names besides addresses and localhost that requests may name, for DNS-rebinding protection.
webapi.domains=nas.lan
# HTTPS from a PKCS#12 file; without one, use it on 127.0.0.1 or behind a TLS proxy.
webapi.httpsFile=/srv/torrenta/cert.p12
webapi.httpsPassword=…
```

The start says `Remote access is on at http://127.0.0.1:8080/`, or why not (a port in use, no
password). In Sonarr or Radarr, add a qBittorrent download client with that host, port, user and
password. There is no localhost bypass: every client signs in. Five failed sign-ins ban an
address for an hour.

## One Torrenta per config folder

As with the window, the first Torrenta on a config folder holds it. A second one, headless or not,
hands its command line to the first and exits: `torrenta-nox --config-dir /srv/torrenta 'magnet:…'`
run while the server is up adds the magnet to it, and is gone. A browser's magnet link reaches a
headless Torrenta the same way: the Torrenta the browser starts, on the same config folder (the
default one, unless told otherwise), finds it running and hands the link over.

## A systemd user unit

`~/.config/systemd/user/torrenta.service`, for the packaged app in `/opt/torrenta`:

```ini
[Unit]
Description=Torrenta, headless
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/opt/torrenta/bin/torrenta-nox --config-dir %h/.torrenta
# SIGTERM is the clean stop; give the last resume data its time.
KillSignal=SIGTERM
TimeoutStopSec=20
Restart=on-failure

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
```

```bash
systemctl --user enable --now torrenta
```

```bash
journalctl --user -u torrenta -f
```

To keep it running while nobody is logged in, enable lingering for the user:

```bash
loginctl enable-linger "$USER"
```

A unit bound to a VPN can bind Torrenta too: Settings → Connection → Interface (`connection.interface`,
say `wg0`), and the leak check's lines (a binding down or missing, the address peers see) are in
the log like any other.
