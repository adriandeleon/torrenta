package com.torrenta.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A deliberately small CSS-shaped selector: {@code tag}, {@code .class}, {@code #id},
 * {@code [attr]}, {@code [attr=value]} (also {@code ^=}, {@code $=}, {@code *=}, {@code ~=}),
 * {@code :nth-of-type(n)}, {@code :first-of-type}, {@code :last-of-type},
 * {@code :nth-child(n)}, {@code :first-child}, {@code :last-child}, {@code :contains(text)},
 * joined by the descendant (space) and child ({@code >}) combinators. A colon in a tag name is
 * written {@code \:}. No lists, no siblings, no {@code :not}: a listing table needs none of them.
 *
 * <p>Matched right to left, as browsers do; ancestors are looked for up to and including the
 * element it was asked of, never beyond. Pure; {@link #parse} throws
 * {@link IllegalArgumentException} with the reason for a selector it does not understand.
 */
public final class Selector {

    private record Condition(char kind, String name, String op, String value, int n) {}

    private record Compound(String tag, List<Condition> conditions) {
        boolean matches(HtmlNode node) {
            if (tag != null && !tag.equals("*") && !tag.equals(node.tag())) return false;
            for (Condition c : conditions) if (!Selector.matches(node, c)) return false;
            return true;
        }
    }

    private final List<Compound> compounds;
    /** {@code combinators.get(k)} joins compound {@code k} to {@code k + 1}: ' ' or '>'. */
    private final List<Character> combinators;

    private final String text;

    private Selector(List<Compound> compounds, List<Character> combinators, String text) {
        this.compounds = compounds;
        this.combinators = combinators;
        this.text = text;
    }

    public static Selector parse(String selector) {
        if (selector == null || selector.isBlank()) throw new IllegalArgumentException("the selector is empty");
        return new Parser(selector.trim()).parse();
    }

    @Override
    public String toString() {
        return text;
    }

    /** Every element beneath {@code scope} that matches, document order; {@code scope} itself never. */
    public List<HtmlNode> select(HtmlNode scope) {
        List<HtmlNode> out = new ArrayList<>();
        for (HtmlNode node : scope.descendants()) {
            if (matchesFrom(node, compounds.size() - 1, scope)) out.add(node);
        }
        return out;
    }

    public Optional<HtmlNode> selectFirst(HtmlNode scope) {
        for (HtmlNode node : scope.descendants()) {
            if (matchesFrom(node, compounds.size() - 1, scope)) return Optional.of(node);
        }
        return Optional.empty();
    }

    /** Whether {@code node} itself matches the whole selector, with {@code scope} as the top. */
    public boolean matches(HtmlNode node, HtmlNode scope) {
        return matchesFrom(node, compounds.size() - 1, scope);
    }

    private boolean matchesFrom(HtmlNode node, int index, HtmlNode scope) {
        if (!compounds.get(index).matches(node)) return false;
        if (index == 0) return true;
        char combinator = combinators.get(index - 1);
        if (node == scope) return false;
        HtmlNode parent = node.parent();
        if (combinator == '>') {
            return parent != null && matchesFrom(parent, index - 1, scope);
        }
        for (HtmlNode a = parent; a != null; a = a.parent()) {
            if (matchesFrom(a, index - 1, scope)) return true;
            if (a == scope) break;
        }
        return false;
    }

    private static boolean matches(HtmlNode node, Condition c) {
        switch (c.kind()) {
            case '.':
                return node.classes().contains(c.name());
            case '#':
                return node.attr("id").equals(c.name());
            case '[': {
                if (!node.hasAttr(c.name())) return false;
                if (c.op() == null) return true;
                String v = node.attr(c.name());
                return switch (c.op()) {
                    case "=" -> v.equals(c.value());
                    case "^=" -> v.startsWith(c.value());
                    case "$=" -> v.endsWith(c.value());
                    case "*=" -> v.contains(c.value());
                    case "~=" -> List.of(v.trim().split("\\s+")).contains(c.value());
                    default -> false;
                };
            }
            case ':':
                return pseudo(node, c);
            default:
                return false;
        }
    }

    private static boolean pseudo(HtmlNode node, Condition c) {
        HtmlNode parent = node.parent();
        List<HtmlNode> siblings = parent == null ? List.of(node) : parent.elements();
        switch (c.name()) {
            case "first-child":
                return !siblings.isEmpty() && siblings.get(0) == node;
            case "last-child":
                return !siblings.isEmpty() && siblings.get(siblings.size() - 1) == node;
            case "nth-child":
                return siblings.indexOf(node) + 1 == c.n();
            case "first-of-type", "last-of-type", "nth-of-type": {
                List<HtmlNode> ofType = new ArrayList<>();
                for (HtmlNode s : siblings) if (s.tag().equals(node.tag())) ofType.add(s);
                return switch (c.name()) {
                    case "first-of-type" -> ofType.get(0) == node;
                    case "last-of-type" -> ofType.get(ofType.size() - 1) == node;
                    default -> ofType.indexOf(node) + 1 == c.n();
                };
            }
            case "contains":
                return node.text().toLowerCase(Locale.ROOT).contains(c.value().toLowerCase(Locale.ROOT));
            default:
                return false;
        }
    }

    // ---- parsing --------------------------------------------------------------------------------

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Selector parse() {
            List<Compound> compounds = new ArrayList<>();
            List<Character> combinators = new ArrayList<>();
            compounds.add(compound());
            while (i < s.length()) {
                boolean space = false;
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    space = true;
                    i++;
                }
                if (i >= s.length()) break;
                if (s.charAt(i) == ',') throw fail("selector lists (a, b) are not supported");
                if (s.charAt(i) == '+' || s.charAt(i) == '~') throw fail("sibling combinators are not supported");
                char combinator = ' ';
                if (s.charAt(i) == '>') {
                    combinator = '>';
                    i++;
                    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                    if (i >= s.length()) throw fail("nothing after the combinator");
                } else if (!space) {
                    throw fail("unexpected '" + s.charAt(i) + "'");
                }
                combinators.add(combinator);
                compounds.add(compound());
            }
            return new Selector(compounds, combinators, s);
        }

        private Compound compound() {
            int start = i;
            String tag = null;
            if (i < s.length() && s.charAt(i) == '*') {
                tag = "*";
                i++;
            } else if (i < s.length() && isIdentStart(s.charAt(i))) {
                tag = ident().toLowerCase(Locale.ROOT);
            }
            List<Condition> conditions = new ArrayList<>();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.') {
                    i++;
                    conditions.add(new Condition('.', ident(), null, null, 0));
                } else if (c == '#') {
                    i++;
                    conditions.add(new Condition('#', ident(), null, null, 0));
                } else if (c == '[') {
                    i++;
                    conditions.add(attribute());
                } else if (c == ':') {
                    i++;
                    conditions.add(pseudo());
                } else {
                    break;
                }
            }
            if (i == start)
                throw fail(i >= s.length() ? "nothing after the combinator" : "unexpected '" + s.charAt(i) + "'");
            if (tag == null && conditions.isEmpty()) throw fail("nothing to match");
            return new Compound(tag, conditions);
        }

        private Condition attribute() {
            skipSpaces();
            String name = ident().toLowerCase(Locale.ROOT);
            skipSpaces();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return new Condition('[', name, null, null, 0);
            }
            String op;
            if (i < s.length() && s.charAt(i) == '=') {
                op = "=";
                i++;
            } else if (i + 1 < s.length() && "^$*~".indexOf(s.charAt(i)) >= 0 && s.charAt(i + 1) == '=') {
                op = s.substring(i, i + 2);
                i += 2;
            } else {
                throw fail("expected ']' or '=' in [" + name);
            }
            skipSpaces();
            String value;
            if (i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                char quote = s.charAt(i++);
                int end = s.indexOf(quote, i);
                if (end < 0) throw fail("unterminated quote in [" + name);
                value = s.substring(i, end);
                i = end + 1;
            } else {
                int start = i;
                while (i < s.length() && s.charAt(i) != ']' && !Character.isWhitespace(s.charAt(i))) i++;
                value = s.substring(start, i);
            }
            skipSpaces();
            if (i >= s.length() || s.charAt(i) != ']') throw fail("expected ']' after [" + name);
            i++;
            return new Condition('[', name, op, value, 0);
        }

        private Condition pseudo() {
            String name = ident().toLowerCase(Locale.ROOT);
            switch (name) {
                case "first-child", "last-child", "first-of-type", "last-of-type":
                    return new Condition(':', name, null, null, 0);
                case "nth-child", "nth-of-type": {
                    String arg = argument(name);
                    int n;
                    try {
                        n = Integer.parseInt(arg.trim());
                    } catch (NumberFormatException e) {
                        throw fail(":" + name + " takes a number, not '" + arg + "'");
                    }
                    if (n < 1) throw fail(":" + name + " counts from 1");
                    return new Condition(':', name, null, null, n);
                }
                case "contains": {
                    String arg = argument(name).trim();
                    if (arg.length() >= 2
                            && (arg.charAt(0) == '"' || arg.charAt(0) == '\'')
                            && arg.charAt(arg.length() - 1) == arg.charAt(0)) arg = arg.substring(1, arg.length() - 1);
                    return new Condition(':', name, null, arg, 0);
                }
                default:
                    throw fail("unknown pseudo-class :" + name);
            }
        }

        private String argument(String name) {
            if (i >= s.length() || s.charAt(i) != '(') throw fail(":" + name + " needs (…)");
            int end = s.indexOf(')', i);
            if (end < 0) throw fail(":" + name + " is missing its ')'");
            String arg = s.substring(i + 1, end);
            i = end + 1;
            return arg;
        }

        private static boolean isIdentStart(char c) {
            return Character.isLetter(c) || c == '_' || c == '\\';
        }

        private static boolean isIdentChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '\\';
        }

        private String ident() {
            int start = i;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    sb.append(s.charAt(i + 1));
                    i += 2;
                } else if (isIdentChar(c)) {
                    sb.append(c);
                    i++;
                } else {
                    break;
                }
            }
            if (i == start)
                throw fail(
                        i >= s.length()
                                ? "a name is missing at the end"
                                : "a name is missing before '" + s.charAt(i) + "'");
            return sb.toString();
        }

        private void skipSpaces() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private IllegalArgumentException fail(String why) {
            return new IllegalArgumentException("selector '" + s + "': " + why);
        }
    }
}
