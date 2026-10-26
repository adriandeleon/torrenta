# Remote access: qBittorrent's WebUI API

Torrenta speaks qBittorrent's WebUI API at `/api/v2`, at the level of qBittorrent 5.0 (it says
`v5.0.0` and API `2.11`). Anything written for qBittorrent connects to it as it is: Sonarr,
Radarr, Lidarr, Readarr and Prowlarr as a download client, remote apps on a phone, and web
interfaces such as VueTorrent served from Torrenta itself. Settings → Remote access switches it on;
on a server, see [headless.md](headless.md) for the same keys in `settings.properties` and
`--set-remote-password`.

## The doors

Remote access is the widest door Torrenta has, so it is shut by default and narrow when open:

- **Off** until switched on, and on `127.0.0.1:8080` until another address is chosen. An address
  other machines reach over plain HTTP gets a warning on the page: choose a certificate (a PKCS#12
  file) or keep to loopback, or put a TLS proxy in front.
- **Sign-in always**, from every address, this machine's included: there is no localhost bypass.
  The password is kept only as a salted PBKDF2-SHA256 hash, on this machine, never synced. Five
  wrong tries in a row ban an address for an hour.
- **The `Host` header** must name this server (an address, `localhost`, or a name listed on the
  page), so a website cannot reach it through DNS rebinding; **a browser's `Origin`** (or `Referer`)
  must be this server, so a page elsewhere cannot use a signed-in browser's cookie. The cookie is
  `HttpOnly` and `SameSite=Strict`.
- **Files go only where they are allowed**: a save path, a new location, a category's folder and
  the default save path must be inside the download folder, a category's folder, or a folder listed
  under Allowed folders; links out of them and `..` are followed and refused. `urls` are magnets and
  http(s) links only, never a path on this machine, and every `.torrent` is checked for shape and
  size before the engine reads it.
- **The door's own settings** (the password, address, port, the bypasses) are changed only on this
  machine's Settings page: `app/setPreferences` reports them and ignores writes to them.
- **The web interface folder** serves `public/` to anyone (its login page) and `private/` only to a
  signed-in session; nothing outside the folder, hidden in it, or behind a link out of it.

## What is spoken

| Area | Endpoints |
|---|---|
| Sign-in | `auth/login`, `auth/logout` |
| The application | `app/version`, `webapiVersion`, `defaultSavePath`, `preferences`, `setPreferences` |
| The list | `torrents/info` (filter, category, tag, hashes, sort, reverse, offset, limit), `sync/maindata` |
| One torrent | `properties`, `files`, `trackers`, `webseeds`, `pieceStates`, `sync/torrentPeers`, `downloadLimit`, `uploadLimit` |
| Adding | `torrents/add`: `urls`, uploaded `torrents`, `savepath`, `category`, `tags`, `stopped`/`paused`, `skip_checking`, `contentLayout`/`root_folder`, `sequentialDownload`, `firstLastPiecePrio`, `rename`, `dlLimit`/`upLimit`, `ratioLimit`/`seedingTimeLimit` |
| Acting | `delete`, `stop`/`start` (`pause`/`resume`), `recheck`, `setLocation`, `rename`, `renameFile`/`renameFolder`, `filePrio`, `add`/`remove`/`editTracker`, `setDownloadLimit`/`setUploadLimit`, `toggleSequentialDownload`, `toggleFirstLastPiecePrio`, `setSuperSeeding`, `topPrio`/`increasePrio`/`decreasePrio`/`bottomPrio`, `setForceStart`, `setShareLimits` |
| Categories and tags | `categories`, `createCategory`, `editCategory`, `removeCategories`, `setCategory`, `tags`, `createTags`, `deleteTags`, `addTags`, `removeTags` |
| The session | `transfer/info`, `speedLimitsMode`, `toggleSpeedLimitsMode`, `setSpeedLimitsMode`, `downloadLimit`, `uploadLimit`, `setDownloadLimit`, `setUploadLimit`, `log/main` |

Anything else under `/api/` is 404, which clients read as "not supported". Not spoken: `search/*`
and `rss/*` (Tier 3, after 1.2), `log/peers`, and the few torrent edits with no Torrenta
counterpart. Where the engine in use lacks something (a Transmission daemon cannot move, rename a
torrent or super seed), the answer is qBittorrent's 409 with the reason. Downloads from links are
not torrents and are not listed.

Two answers that clients lean on: `hash` is a torrent's v1 info-hash where it has one, since that
is what an app computes from the `.torrent` or magnet it sent (Torrenta's own id for a hybrid is
its v2 hash); and `content_path` is the file of a single-file torrent and the folder of a
multi-file one, which is what the apps import from.

## Connecting Sonarr or Radarr

Settings → Download Clients → + → qBittorrent: the host and port of Torrenta's Remote access, its
user name and password, and a category (`tv-sonarr`, `radarr`). Test, then save. The category is
created in Torrenta on the first grab; give it a folder in Torrenta if downloads should land
somewhere other than the download folder. "Remove Completed" works as with qBittorrent: once a
torrent has reached Torrenta's share limit and stopped, the app removes it, with its files, since
it has imported them by then.

When the app runs in a container, mount the download folder at the same path inside, or map the
path in its Remote Path Mappings: `content_path` is Torrenta's path.

## The contract and how it is kept

`QbittorrentContractTest` holds Torrenta to a real qBittorrent's answers, recorded under
`src/test/resources/com/torrenta/webapi/qbittorrent-5.1/` by `scripts/webapi-record.py`. The
recorder plays one scenario against any server speaking the API: two `.torrent` files (one whose
data is already there, one stopped in a category with a tag) and a magnet nobody serves, then every
endpoint above and the error cases clients test for. The test plays the same scenario against
Torrenta through its router and compares the shapes: every field qBittorrent writes, nothing it
does not, each the same kind of value, and the statuses and words of the answers. It runs
everywhere, with no qBittorrent installed.

To record again (a new qBittorrent, a new endpoint in the scenario), run a throwaway qBittorrent
with a profile of its own and its WebUI on, `qbittorrent-nox --profile=DIR` or the desktop build
with `QT_QPA_PLATFORM=offscreen`, copy the seeding torrent's data into its default save path, and:

```bash
scripts/webapi-record.py http://127.0.0.1:18090 admin PASSWORD seed.torrent pack.torrent recording/
```

Scrub the recording of the machine's paths and addresses before it goes into the test resources:
the log records the addresses qBittorrent listened on. `scripts/webapi-compare.py` compares two
recordings by shape (qBittorrent's against a live headless Torrenta's, recorded the same way), the
check behind the test's fixtures: on 2026-10-25 it found nothing between qBittorrent 5.1.0 and
Torrenta.

## The manual pass

Done on 2026-10-25 with Sonarr 4.0.20.3014 and Radarr 6.4.4.10685 (the linuxserver images, run with
podman on the host's network) against a headless Torrenta, each through its own API as a person
would through its pages:

- **Test** of the qBittorrent download client: passed in both.
- **Grab**: a release pushed to each (`release/push`, as Prowlarr hands one over) with a `.torrent`
  link served from loopback; each app fetched it and added it to Torrenta through `torrents/add`,
  multipart, with its category. The data was already in the download folder, so each finished at
  once.
- **Import**: Radarr imported *Big Buck Bunny* into its movie folder; Sonarr imported *The Expanse*
  S01E01 into `The Expanse/Season 1/`, both from `content_path`. (Sonarr first held a 6-minute file
  back as a sample: its rule wants over ten minutes for an hour-long show. A real episode passes.)
- **Remove after seeding**: with a one-minute seeding limit that stops a torrent, Torrenta stopped
  each on time and the app removed it (`torrents/delete`, with its files) half a minute later; the
  imported copies stayed. Removing a grab from Sonarr's queue removed it from Torrenta too.

A pass with a remote app on a phone is still to be written down here.
