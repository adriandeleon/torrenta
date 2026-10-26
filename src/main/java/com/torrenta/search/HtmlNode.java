package com.torrenta.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * One node of a parsed page: an element with a lower-case tag, attributes and children, or a run
 * of text. What a {@link Selector} walks and a definition's fields read from.
 */
public final class HtmlNode {

    /** Elements whose text reads as separate words from their neighbours'. */
    private static final Set<String> BLOCKS = Set.of(
            "address",
            "article",
            "aside",
            "blockquote",
            "br",
            "dd",
            "div",
            "dl",
            "dt",
            "fieldset",
            "footer",
            "form",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "header",
            "hr",
            "li",
            "main",
            "nav",
            "ol",
            "p",
            "pre",
            "section",
            "table",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "tr",
            "ul",
            "item",
            "title",
            "link",
            "description",
            "pubdate",
            "guid",
            "category",
            "size",
            "infohash");

    private final @Nullable String tag;
    private final @Nullable String text;
    private final Map<String, String> attrs;
    private final List<HtmlNode> children = new ArrayList<>();
    private @Nullable HtmlNode parent;

    private HtmlNode(@Nullable String tag, @Nullable String text, Map<String, String> attrs) {
        this.tag = tag;
        this.text = text;
        this.attrs = attrs;
    }

    static HtmlNode element(String tag, Map<String, String> attrs) {
        return new HtmlNode(tag.toLowerCase(Locale.ROOT), null, new LinkedHashMap<>(attrs));
    }

    static HtmlNode text(String text) {
        return new HtmlNode(null, text, Map.of());
    }

    /** The document itself: an element with no tag of its own, called {@code #root}. */
    static HtmlNode root() {
        return new HtmlNode("#root", null, new LinkedHashMap<>());
    }

    void append(HtmlNode child) {
        child.parent = this;
        children.add(child);
    }

    public boolean isElement() {
        return tag != null;
    }

    /** The lower-case tag; null for text. */
    public @Nullable String tag() {
        return tag;
    }

    public @Nullable HtmlNode parent() {
        return parent;
    }

    public List<HtmlNode> children() {
        return Collections.unmodifiableList(children);
    }

    /** The attribute's value, entities decoded; empty when absent or valueless. */
    public String attr(String name) {
        String v = attrs.get(name.toLowerCase(Locale.ROOT));
        return v == null ? "" : v;
    }

    public boolean hasAttr(String name) {
        return attrs.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, String> attrs() {
        return Collections.unmodifiableMap(attrs);
    }

    /** The {@code class} attribute's words. */
    public List<String> classes() {
        String c = attr("class").trim();
        return c.isEmpty() ? List.of() : List.of(c.split("\\s+"));
    }

    /**
     * Every run of text beneath, in order, whitespace collapsed to single spaces and trimmed;
     * block-level elements and {@code <br>} separate words.
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        collectText(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private void collectText(StringBuilder sb) {
        if (text != null) {
            sb.append(text);
            return;
        }
        boolean block = BLOCKS.contains(tag);
        if (block) sb.append(' ');
        for (HtmlNode child : children) child.collectText(sb);
        if (block) sb.append(' ');
    }

    /** The element children only, in order. */
    public List<HtmlNode> elements() {
        List<HtmlNode> out = new ArrayList<>();
        for (HtmlNode child : children) if (child.isElement()) out.add(child);
        return out;
    }

    /** Every element beneath, document order, this one left out. */
    public List<HtmlNode> descendants() {
        List<HtmlNode> out = new ArrayList<>();
        collectDescendants(out);
        return out;
    }

    private void collectDescendants(List<HtmlNode> out) {
        for (HtmlNode child : children) {
            if (!child.isElement()) continue;
            out.add(child);
            child.collectDescendants(out);
        }
    }

    /** The elements beneath that {@code selector} matches, document order. */
    public List<HtmlNode> select(String selector) {
        return Selector.parse(selector).select(this);
    }

    public Optional<HtmlNode> selectFirst(String selector) {
        return Selector.parse(selector).selectFirst(this);
    }

    @Override
    public String toString() {
        if (text != null) return "\"" + text + "\"";
        StringBuilder sb = new StringBuilder("<").append(tag);
        attrs.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(v).append('"'));
        return sb.append('>').toString();
    }
}
