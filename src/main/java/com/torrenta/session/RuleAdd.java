package com.torrenta.session;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.rss.Article;
import com.torrenta.rss.Rule;

import static com.torrenta.i18n.Messages.tr;

/**
 * An RSS article into an add. What a rule adds a torrent with: the rule's save path, else the
 * category's folder, else the default; paused as the rule says, else as the defaults say; the
 * rule's category; the rest as "add without asking" would. And what the article's torrent is: a
 * magnet as it is, a {@code .torrent} link fetched. Pure, the fetch aside.
 */
public final class RuleAdd {

    /** Downloads a {@code .torrent}: {@link com.torrenta.net.TorrentFetch}, or a test's stand-in. */
    public interface Fetcher {
        byte[] fetch(URI url) throws IOException;
    }

    private RuleAdd() {}

    public static AddRequest request(
            Rule rule, AddRequest.Source source, AddDefaults defaults, Optional<Path> categoryFolder) {
        Path save = rule.savePath().isEmpty()
                ? categoryFolder.orElse(defaults.savePath())
                : Path.of(rule.savePath()).toAbsolutePath();
        boolean paused =
                switch (rule.addPaused()) {
                    case DEFAULT -> defaults.startPaused();
                    case YES -> true;
                    case NO -> false;
                };
        return new AddRequest(
                source,
                save,
                paused,
                defaults.sequential(),
                false,
                AddRequest.StopCondition.NONE,
                Map.of(),
                TorrentLabels.NONE.withCategory(rule.category()),
                defaults.storage(save));
    }

    /**
     * The article's torrent as an add's source: a magnet as it is; a {@code .torrent} link fetched
     * (on the caller's thread) and the file source with the URL as its origin. An article without
     * a torrent is refused.
     */
    public static AddRequest.Source sourceOf(Article article, Fetcher fetcher) throws IOException {
        if (!article.hasTorrent()) throw new IOException(tr("rss.noTorrentIn", article.displayTitle()));
        if (article.isMagnet()) return new AddRequest.Magnet(article.torrentLink());
        URI uri;
        try {
            uri = URI.create(article.torrentLink());
        } catch (IllegalArgumentException e) {
            throw new IOException(tr("search.notUrl", article.torrentLink()));
        }
        return new AddRequest.TorrentFile(fetcher.fetch(uri), article.torrentLink());
    }
}
