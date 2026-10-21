package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.torrenta.engine.AddRequest;
import com.torrenta.rss.Rule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleAddTest {

    private static final AddRequest.Source MAGNET = new AddRequest.Magnet("magnet:?xt=urn:btih:" + "0".repeat(40));

    private static Rule rule(Rule.AddPaused paused, String category, String savePath) {
        return new Rule("r", true, "", "", false, "", false, List.of(), 0, paused, category, savePath, null, List.of());
    }

    @Test
    void theSavePathIsTheRulesThenTheCategorysThenTheDefault() {
        Path downloads = Path.of("downloads").toAbsolutePath();
        Path incomplete = Path.of("incomplete").toAbsolutePath();
        Path tv = Path.of("tv").toAbsolutePath();
        Path own = Path.of("own").toAbsolutePath();
        AddDialog.Defaults defaults = new AddDialog.Defaults(downloads, true, true, true, incomplete, true, false);

        AddRequest plain = RuleAdd.request(rule(Rule.AddPaused.DEFAULT, "", ""), MAGNET, defaults, Optional.empty());
        assertEquals(downloads, plain.savePath());
        assertTrue(plain.startPaused(), "as the default says");
        assertTrue(plain.sequential(), "the default's");
        assertEquals("", plain.labels().category());
        assertEquals(incomplete, plain.storage().incompletePath(), "the incomplete folder applies");
        assertTrue(plain.storage().partSuffix());
        assertFalse(plain.skipCheck());
        assertEquals(AddRequest.StopCondition.NONE, plain.stopCondition());

        AddRequest filed = RuleAdd.request(rule(Rule.AddPaused.NO, "TV", ""), MAGNET, defaults, Optional.of(tv));
        assertEquals(tv, filed.savePath(), "the category's folder");
        assertFalse(filed.startPaused());
        assertEquals("TV", filed.labels().category());

        AddRequest own1 =
                RuleAdd.request(rule(Rule.AddPaused.YES, "TV", own.toString()), MAGNET, defaults, Optional.of(tv));
        assertEquals(own, own1.savePath(), "the rule's own path wins");
        assertTrue(own1.startPaused());
        assertEquals(incomplete, own1.storage().incompletePath());

        AddDialog.Defaults direct = new AddDialog.Defaults(downloads, false, false, false, incomplete, false, false);
        AddRequest d = RuleAdd.request(rule(Rule.AddPaused.DEFAULT, "", ""), MAGNET, direct, Optional.empty());
        assertFalse(d.startPaused());
        assertNull(d.storage().incompletePath());
    }
}
