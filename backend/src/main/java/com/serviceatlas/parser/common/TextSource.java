package com.serviceatlas.parser.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A text file held as lines, with the bookkeeping needed to turn a regex hit into
 * {@link com.serviceatlas.graph.model.Evidence} (file + 1-based line + snippet).
 *
 * <p>Scala comments are stripped before matching. Without that, a commented-out {@code ws.url(...)}
 * or a URL in a doc comment produces phantom edges — the most common false positive in a
 * regex-based extractor.
 */
public final class TextSource {

    private final String path;
    private final List<String> rawLines;
    private final List<String> codeLines;

    public TextSource(String path, String content) {
        this.path = path;
        this.rawLines = content.lines().toList();
        this.codeLines = stripComments(rawLines);
    }

    public String path() {
        return path;
    }

    /** Lines exactly as written, for snippets shown to the user. */
    public List<String> rawLines() {
        return rawLines;
    }

    /** Lines with comment content blanked out, for matching. */
    public List<String> codeLines() {
        return codeLines;
    }

    public int lineCount() {
        return rawLines.size();
    }

    /** The raw text of a 1-based line, or empty string if out of range. */
    public String rawLine(int lineNumber) {
        if (lineNumber < 1 || lineNumber > rawLines.size()) {
            return "";
        }
        return rawLines.get(lineNumber - 1);
    }

    /** Whole file (comment-stripped) as one string, for patterns that span lines. */
    public String code() {
        return String.join("\n", codeLines);
    }

    /** Runs {@code pattern} over every comment-stripped line and reports each hit. */
    public List<Match> matches(Pattern pattern) {
        List<Match> found = new ArrayList<>();
        for (int i = 0; i < codeLines.size(); i++) {
            Matcher matcher = pattern.matcher(codeLines.get(i));
            while (matcher.find()) {
                found.add(new Match(i + 1, matcher.group(), groups(matcher), rawLines.get(i)));
            }
        }
        return found;
    }

    private static List<String> groups(Matcher matcher) {
        List<String> groups = new ArrayList<>(matcher.groupCount());
        for (int g = 1; g <= matcher.groupCount(); g++) {
            groups.add(matcher.group(g));
        }
        return groups;
    }

    /**
     * Blanks out {@code //} line comments and {@code /* ... *}{@code /} blocks, preserving line
     * numbering and ignoring comment markers that appear inside string literals.
     */
    private static List<String> stripComments(List<String> lines) {
        List<String> stripped = new ArrayList<>(lines.size());
        boolean inBlockComment = false;
        for (String line : lines) {
            StringBuilder out = new StringBuilder(line.length());
            boolean inString = false;
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false;
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    out.append(' ');
                    i++;
                    continue;
                }
                if (inString) {
                    out.append(c);
                    if (c == '\\' && next != '\0') {
                        out.append(next);
                        i += 2;
                        continue;
                    }
                    if (c == '"') {
                        inString = false;
                    }
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = true;
                    out.append(c);
                    i++;
                    continue;
                }
                if (c == '/' && next == '/') {
                    break; // rest of line is a comment
                }
                if (c == '/' && next == '*') {
                    inBlockComment = true;
                    out.append("  ");
                    i += 2;
                    continue;
                }
                out.append(c);
                i++;
            }
            stripped.add(out.toString());
        }
        return stripped;
    }

    /**
     * One regex hit.
     *
     * @param line     1-based line number
     * @param text     the whole matched text
     * @param groups   capture groups 1..n
     * @param rawLine  the untouched source line, used as the evidence snippet
     */
    public record Match(int line, String text, List<String> groups, String rawLine) {

        public String group(int index) {
            return index <= groups.size() ? groups.get(index - 1) : null;
        }
    }
}
