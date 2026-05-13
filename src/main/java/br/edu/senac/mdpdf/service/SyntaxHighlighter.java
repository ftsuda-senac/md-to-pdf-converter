package br.edu.senac.mdpdf.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies syntax highlighting to raw source code, returning an HTML fragment
 * with {@code <span class="hljs-*">} wrappers compatible with the hljs CSS classes
 * defined in {@code github.css}.
 *
 * <p>Works on unescaped source text; HTML-escapes all non-highlighted characters
 * so the result is safe to embed inside a {@code <code>} element.
 */
final class SyntaxHighlighter {

    private SyntaxHighlighter() {}

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
        "<pre><code class=\"([^\"]+)\">(.*?)</code></pre>",
        Pattern.DOTALL);

    /**
     * Replaces the content of every {@code <pre><code class="language-*">} block
     * in the given HTML string with highlighted spans. All other HTML is passed
     * through untouched — no full-document re-serialization occurs.
     */
    static String applyToFragment(String html) {
        Matcher m = CODE_BLOCK_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String lang = extractLanguage(m.group(1));
            if (lang != null) {
                String raw         = unescapeHtml(m.group(2));
                String highlighted = highlight(raw, lang);
                m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<pre><code class=\"" + m.group(1) + "\">" + highlighted + "</code></pre>"));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String extractLanguage(String className) {
        for (String cls : className.split("\\s+")) {
            if (cls.startsWith("language-")) {
                return cls.substring("language-".length());
            }
        }
        return null;
    }

    private record TokenDef(String regex, String cssClass) {}

    // -------------------------------------------------------------------------
    // Java
    // -------------------------------------------------------------------------

    private static final List<TokenDef> JAVA_TOKENS = List.of(
        new TokenDef("/\\*[\\s\\S]*?\\*/",                                              "hljs-comment"),
        new TokenDef("//[^\n]*",                                                        "hljs-comment"),
        new TokenDef("\"(?:[^\"\\\\]|\\\\.)*\"",                                       "hljs-string"),
        new TokenDef("'(?:[^'\\\\]|\\\\.)*'",                                          "hljs-string"),
        new TokenDef("@[A-Za-z_][A-Za-z0-9_]*",                                       "hljs-meta"),
        new TokenDef("\\b(?:abstract|assert|boolean|break|byte|case|catch|char|class"
                   + "|const|continue|default|do|double|else|enum|extends|final"
                   + "|finally|float|for|goto|if|implements|import|instanceof|int"
                   + "|interface|long|native|new|package|private|protected|public"
                   + "|return|short|static|strictfp|super|switch|synchronized|this"
                   + "|throw|throws|transient|try|var|void|volatile|while|record"
                   + "|sealed|permits|yield)\\b",                                      "hljs-keyword"),
        new TokenDef("\\b(?:true|false|null)\\b",                                      "hljs-literal"),
        new TokenDef("\\b(?:0x[0-9A-Fa-f]+[lL]?|\\d+(?:\\.\\d+)?"
                   + "(?:[eE][+-]?\\d+)?[lLfFdD]?)\\b",                               "hljs-number"),
        new TokenDef("\\b[A-Z][A-Za-z0-9_]*\\b",                                      "hljs-type")
    );

    // -------------------------------------------------------------------------
    // JavaScript / TypeScript
    // -------------------------------------------------------------------------

    private static final List<TokenDef> JS_TOKENS = List.of(
        new TokenDef("/\\*[\\s\\S]*?\\*/",                                              "hljs-comment"),
        new TokenDef("//[^\n]*",                                                        "hljs-comment"),
        new TokenDef("`(?:[^`\\\\]|\\\\.)*`",                                          "hljs-string"),
        new TokenDef("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'",                "hljs-string"),
        new TokenDef("\\b(?:async|await|break|case|catch|class|const|continue"
                   + "|debugger|default|delete|do|else|export|extends|finally|for"
                   + "|from|function|if|import|in|instanceof|let|new|of|return"
                   + "|static|super|switch|this|throw|try|typeof|var|void|while"
                   + "|with|yield)\\b",                                                "hljs-keyword"),
        new TokenDef("\\b(?:true|false|null|undefined|NaN|Infinity)\\b",               "hljs-literal"),
        new TokenDef("\\b(?:0x[0-9A-Fa-f]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b",  "hljs-number")
    );

    // -------------------------------------------------------------------------
    // HTML / XML
    // -------------------------------------------------------------------------

    private static final List<TokenDef> HTML_TOKENS = List.of(
        new TokenDef("<!--[\\s\\S]*?-->",                                               "hljs-comment"),
        new TokenDef("<!DOCTYPE[^>]*>",                                                "hljs-meta"),
        new TokenDef("</?[a-zA-Z][a-zA-Z0-9:-]*",                                     "hljs-selector-tag"),
        new TokenDef("[a-zA-Z][a-zA-Z0-9:-]*(?=\\s*=)",                               "hljs-attr"),
        new TokenDef("\"[^\"]*\"|'[^']*'",                                             "hljs-string")
    );

    // -------------------------------------------------------------------------
    // CSS
    // -------------------------------------------------------------------------

    private static final List<TokenDef> CSS_TOKENS = List.of(
        new TokenDef("/\\*[\\s\\S]*?\\*/",                                              "hljs-comment"),
        new TokenDef("@[a-zA-Z][a-zA-Z-]*",                                            "hljs-keyword"),
        new TokenDef("[.#]?[a-zA-Z][a-zA-Z0-9_-]*(?=\\s*\\{)",                        "hljs-selector-tag"),
        // Require ≥2 alpha chars before ':' to avoid false-positive on single-letter selectors (a:hover)
        new TokenDef("[a-zA-Z]{2}[a-zA-Z-]*(?=\\s*:)",                                "hljs-attr"),
        new TokenDef("\"[^\"]*\"|'[^']*'",                                             "hljs-string"),
        new TokenDef("#[0-9A-Fa-f]{3,8}\\b",                                           "hljs-number"),
        new TokenDef("\\b\\d+(?:\\.\\d+)?(?:px|em|rem|%|vh|vw|pt|mm|cm|s|ms)?\\b",   "hljs-number")
    );

    // Pre-built composite patterns (one per language)
    private static final Pattern JAVA_PAT = buildPattern(JAVA_TOKENS);
    private static final Pattern JS_PAT   = buildPattern(JS_TOKENS);
    private static final Pattern HTML_PAT = buildPattern(HTML_TOKENS);
    private static final Pattern CSS_PAT  = buildPattern(CSS_TOKENS);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    static String highlight(String rawCode, String language) {
        return switch (language.toLowerCase()) {
            case "java"                         -> apply(rawCode, JAVA_TOKENS, JAVA_PAT);
            case "javascript", "js", "ts",
                 "typescript"                   -> apply(rawCode, JS_TOKENS,   JS_PAT);
            case "html", "xml", "xhtml"         -> apply(rawCode, HTML_TOKENS, HTML_PAT);
            case "css"                          -> apply(rawCode, CSS_TOKENS,  CSS_PAT);
            default                             -> escapeHtml(rawCode);
        };
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static Pattern buildPattern(List<TokenDef> tokens) {
        StringBuilder sb = new StringBuilder();
        for (TokenDef t : tokens) {
            if (!sb.isEmpty()) sb.append("|");
            sb.append("(").append(t.regex()).append(")");
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    /**
     * Scans {@code code} with {@code pattern}, wraps each matched token in a
     * {@code <span class="hljs-*">}, and HTML-escapes all non-matched segments.
     */
    private static String apply(String code, List<TokenDef> tokens, Pattern pattern) {
        Matcher m = pattern.matcher(code);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

        while (m.find()) {
            sb.append(escapeHtml(code.substring(lastEnd, m.start())));
            for (int i = 1; i <= tokens.size(); i++) {
                if (m.group(i) != null) {
                    sb.append("<span class=\"").append(tokens.get(i - 1).cssClass()).append("\">");
                    sb.append(escapeHtml(m.group(i)));
                    sb.append("</span>");
                    break;
                }
            }
            lastEnd = m.end();
        }
        sb.append(escapeHtml(code.substring(lastEnd)));
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }

    private static String unescapeHtml(String html) {
        // &amp; must be last to avoid double-unescaping (e.g. &amp;lt; → &lt;, not <)
        return html.replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&apos;", "'")
                   .replace("&amp;", "&");
    }
}
