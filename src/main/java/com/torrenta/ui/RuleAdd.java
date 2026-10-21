package com.torrenta.ui;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.rss.Rule;

/**
 * What a rule adds a torrent with: the rule's save path, else the category's folder, else the
 * default; paused as the rule says, else as the Add dialog's default; the rule's category; the
 * rest as "add without asking" would. Pure.
 */
final class RuleAdd {

    private RuleAdd() {}

    static AddRequest request(
            Rule rule, AddRequest.Source source, AddDialog.Defaults defaults, Optional<Path> categoryFolder) {
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
}
