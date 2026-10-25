# Search providers: definition files

Torrenta ships no site-specific code. A Torznab server (Prowlarr, Jackett) covers the long tail of
indexers; a site with a JSON API or a stable HTML listing is a **definition file**, one JSON file
interpreted by one generic provider. Definitions are data: when a site changes, the fix is an edit
to a file, not a release.

The files live in `search-providers/` inside the config folder (`~/.torrenta` unless moved with
`--config-dir` or `TORRENTA_CONFIG_DIR`), one per provider, named `<id>.json`. Every file that
loads is a provider chip in the Search tab; Settings → Search lists the files with their type and
whether they loaded (a file that did not says why, and the Event Log says so once), switches each
on or off (`search.disabled` in the settings file, a space-separated list of ids, which syncs),
reloads the folder and opens it. The folder is also re-read when the Search tab opens, so a file
dropped in is a chip at once.

Three examples ship and are copied into the folder on the first run. One you have edited is never
written over: the shipped text goes beside it as `<id>.json.example` instead (and is removed again
when your file matches the shipped one). Delete an example and it comes back on the next start;
switch it off in Settings to be rid of it.

With settings sync on (Settings → Sync), the files travel with the settings: one you add, change or
delete on one computer is added, changed or deleted on the others, a file at a time. An example
you have not changed is not sent, since every computer has it already; turning an edited example
back into the shipped text turns it back on the others too.

## The file

```json
{
  "id": "linuxtracker",
  "name": "LinuxTracker",
  "homepage": "https://linuxtracker.org",
  "type": "html",
  "search": {
    "url": "https://linuxtracker.org/torrents/?q={query}",
    "page": "https://linuxtracker.org/torrents/?q={query}&page={page}",
    "pages": 1,
    "delayMs": 1000,
    "localFilter": false
  },
  "headers": { "User-Agent": "Mozilla/5.0" },
  "categories": { "default": "", "Software": "12" },
  "fields": {
    "rows": "table.torrent-table tbody tr",
    "name": "a.torrent-name",
    "detailUrl": { "selector": "a.torrent-name", "attr": "href" },
    "infoHash": { "selector": "a.torrent-name", "attr": "href", "regex": "([0-9a-fA-F]{40})" },
    "date": { "selector": "td:nth-of-type(3)", "format": "MMM d, uuuu" },
    "size": "td:nth-of-type(4)",
    "seeds": "td.seeds",
    "leechers": "td.leeches"
  }
}
```

| Key | Meaning |
|---|---|
| `id` | Lower-case letters, digits, `.`, `_`, `-`; **must be the file's name** without `.json`. What `search.disabled` names. |
| `name` | What the chip, the Provider column and the status line say. |
| `homepage` | Optional; for people reading the file. |
| `type` | `json` or `html`. An RSS or XML feed is `html`: the parser is lenient enough. |
| `search.url` | The first page's URL. `{query}` is the query URL-encoded (spaces as `+`), `{category}` the category's value from the table below, URL-encoded. Required to contain `{query}` unless `localFilter` is on. |
| `search.page` | The URL of the pages after the first, with `{page}` (2, 3, …) and the same placeholders. Required when `pages` is more than 1. |
| `search.pages` | How many pages to fetch, 1 to 10; 1 by default. Fetching stops at the first page with no rows. |
| `search.delayMs` | A pause between pages, 0 to 10000 ms. Every page must land within the search's 20 s. |
| `search.localFilter` | `true` for a feed that cannot be asked: the URL is fetched as it is and the results whose name has every word of the query are kept. |
| `headers` | Request headers. `User-Agent` is `Torrenta/<version>` unless given here. |
| `categories` | The site's value per category, keyed by Torrenta's names (`Movies`, `TV`, `Music`, `Games`, `Software`, `Books`, `Anime`, `Other`, any case) plus `default` for the rest, `All` included. A category without a value and no default is the empty string. |
| `fields` | How to read a result, below. |

Unknown keys are errors, at every level, so a typo cannot pass as a setting that quietly does
nothing.

### Fields

`rows` is where the results are: for `json` a path to the array (or an object whose values are the
rows; the empty path is the document itself), for `html` a selector for the row elements. Every
other field is read **inside one row** the same way. The fields with a meaning:

| Field | Becomes |
|---|---|
| `name` | The torrent's name. Required; a row without one is skipped. |
| `size` | Bytes, from "1.4 GiB", "700 MB", "3.2G", "1,234,567", "96504312 bytes"; both "MB" and "MiB" are 1024-based, as every site means. `-1` when missing. |
| `seeds`, `leechers` | The digits in the text ("1,204" → 1204); `-1` when there are none. |
| `date` | ISO, RFC 1123, `2026-09-13`, `2026-09-13 08:30`, `Sep 13, 2026`, `13 Sep 2026`, epoch seconds or milliseconds, `2 days ago`, `an hour ago`, `yesterday`; or the field's own `format`, a `DateTimeFormatter` pattern (`dd.MM.uuuu HH:mm`). A date without a time is midnight UTC. Null when missing. |
| `magnet` | A `magnet:` link. |
| `torrentUrl` | A URL to a `.torrent`; a relative one is resolved against the page. |
| `infoHash` | 40 or 64 hex digits, or 32 base32 letters. |
| `detailUrl` | The site's page for the result, resolved against the page. |

A result's link is the magnet when there is one, else the `.torrent` URL, else a magnet made of the
info-hash and the name. A row with none of the three is skipped. Every other field is `-1`, empty
or null when it is missing, never a failure; a page that gives no rows is an empty answer, not an
error.

Any other key in `fields` is an **auxiliary field**, read like the rest and available to templates.

A field is a string, the path or selector, or an object:

| Key | Meaning |
|---|---|
| `path` / `selector` | The JSON path or the CSS-like selector (the two spellings mean the same thing). Empty means the row itself. |
| `attr` | `html` only: the attribute's value instead of the element's text (`href`, `title`, `data-size`). |
| `regex` | Applied to the text: the first group, or the whole match when there is no group; no match is a missing field. |
| `template` | The text made of other fields: `{identifier}`, `{infoHash}`, any auxiliary field, and `{query}`; a template whose field is missing is missing itself. A regex applies after. |
| `format` | `date` only: the pattern to parse it with. |
| `value` | A constant. |

**JSON paths** are JSON Pointer's shape: `/response/docs`, `/links/0/url`, with `~1` for a slash
and `~0` for a tilde in a key; the leading slash is optional. A number, a boolean and a string all
read as their text; a null, an object or an array is a missing field.

**Selectors** are a deliberately small subset of CSS: `tag`, `.class`, `#id`, `[attr]`,
`[attr=value]` (also `^=`, `$=`, `*=`, `~=`), `:nth-of-type(n)`, `:first-of-type`,
`:last-of-type`, `:nth-child(n)`, `:first-child`, `:last-child`, `:contains(text)`, joined by the
descendant (space) and child (`>`) combinators. No lists (`a, b`), no siblings, no `:not`. A colon
in a tag name (RSS namespaces) is written `\:` (`\\:` inside the JSON string). The first match
within the row is the field; selectors are checked when the file loads.

**The HTML parser** is a lenient tokeniser of our own, enough for a listing page and documented for
what it is, not a browser: tags with quoted, unquoted and bare attributes; comments, doctypes and
CDATA skipped; entities decoded (the common named ones and numeric); `<script>` and `<style>`
dropped; an unclosed `td`, `th`, `tr`, `li`, `dt`, `dd`, `option` or `a` closed by the next of its
kind and an open `p` closed by a block; an end tag closing the nearest open element of its name;
void elements and anything written `<x/>` childless, so a feed parses too. It invents no `tbody`:
a `tr` written directly under `table` stays there, so `table tr` finds rows either way where
`table > tbody > tr` finds them only on pages that have one. Tag and attribute names are
lower-cased. An element's text is every run of text beneath it, whitespace collapsed, block
elements and `<br>` separating words.

### Trying a file

Drop it in the folder, press Reload definitions in Settings → Search (or open the Search tab),
and search. A file that did not load says why in the table and once in the Event Log, with the
file and the key at fault: `linuxtracker.json: fields.size: selector 'td, span': selector lists
(a, b) are not supported`. A file that loads but finds nothing is most often a `rows` selector that
matches no element or a `name` selector that matches none inside a row; `scripts/search-stub.java`
serves a saved copy of a page so a file can be tried without asking the site again.

## The examples

**`archive-org.json` (`json`)** — the [Internet Archive](https://archive.org)'s advanced search,
which answers JSON. The query goes into `q=` with a `mediatype:` clause from the category table
(`Movies` → `movies`, `Music` → `audio`, `Books` → `texts`, `Software` and `Games` → `software`,
`Other` → `data`; `All` adds nothing), `rows=50`. The rows are `/response/docs`; each has an
`identifier` (an auxiliary field) from which two templates make the `.torrent` URL,
`https://archive.org/download/{identifier}/{identifier}_archive.torrent`, and the detail page. The
API says nothing of seeds, so Seeds and Leechers are dashes. Answered when built (2026-10-20).

**`linuxtracker.json` (`html`)** — [LinuxTracker](https://linuxtracker.org), a tracker of Linux
distribution images; its listing is a plain table (`table.torrent-table tbody tr`). The name is
the link, the info-hash is taken off the link's `href` by regex, size, seeds and leechers are
cells, the date is "Sep 13, 2026" through a `format`. The listing has no download links, so a
magnet is made of the hash and the name; the detail page is the link. `search.page` is given with
`pages` at 1: raise `pages` to walk further. Answered when built (2026-10-20).

**`academictorrents.json` (`html`)** — [Academic Torrents](https://academictorrents.com), datasets
and courses. Its browse page sits behind a browser check and asks, in a comment, not to be
scraped; it offers its RSS feed instead, so the definition fetches `rss.xml` with `localFilter`
on, reads the `item`s (name, `infohash`, `size`, `link`) with the HTML parser, and keeps the ones
whose title has every word of the query. The `.torrent` URL is a template on the hash. Only the
latest entries are in the feed, so a search finds recent datasets, not the whole catalogue. The
feed answered when built (2026-10-20); the search page did not, by design.

Each example is parsed and run against a copy of its site's answer in the tests
(`ExampleDefinitionsTest`, fixtures under `src/test/resources/com/torrenta/search/declarative`),
so a typo cannot ship and a site change shows up as a fixture that no longer matches.
