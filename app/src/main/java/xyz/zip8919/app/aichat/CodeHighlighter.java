package xyz.zip8919.app.aichat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeHighlighter {
    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
        "java", "py", "python", "js", "javascript", "ts", "typescript",
        "bash", "sh", "shell", "json", "xml", "html", "svg", "cpp", "c++", "c",
        "sql", "go", "rust", "kt", "kotlin", "swift", "cs", "csharp"
    ));

    // Token pattern: (type, regex)
    private static class Rule {
        final String type;
        final Pattern pattern;
        Rule(String type, String regex) {
            this.type = type;
            this.pattern = Pattern.compile(regex);
        }
    }

    private static final Map<String, Rule[]> LANG_RULES = new HashMap<>();
    private static final Map<String, Set<String>> LANG_KEYWORDS = new HashMap<>();

    static {
        // ---- Shared patterns ----
        Set<String> cStyleTypes = setOf("int","long","short","byte","float","double","char",
            "boolean","void","String","Integer","Long","Double","Float","Boolean","Byte",
            "Short","Character","Object","bool","string","auto","var","let","const",
            "int8","int16","int32","int64","uint8","uint16","uint32","uint64");

        // ---- Java ----
        LANG_KEYWORDS.put("java", setOf("abstract","assert","break","case","catch","class",
            "continue","default","do","else","enum","extends","final","finally","for",
            "if","implements","import","instanceof","interface","native","new","package",
            "private","protected","public","return","static","strictfp","super","switch",
            "synchronized","this","throw","throws","transient","try","void","volatile",
            "while","true","false","null","record","sealed","permits","var","yield"));
        LANG_KEYWORDS.put("java_types", cStyleTypes);

        // ---- Python ----
        LANG_KEYWORDS.put("python", setOf("False","None","True","and","as","assert","async",
            "await","break","class","continue","def","del","elif","else","except","finally",
            "for","from","global","if","import","in","is","lambda","nonlocal","not","or",
            "pass","raise","return","try","while","with","yield","print","self","cls"));
        LANG_KEYWORDS.put("py_types", setOf("int","float","str","bool","list","dict","tuple",
            "set","bytes","bytearray","type","object","NoneType","Any","Optional","Union"));

        // ---- JavaScript / TypeScript ----
        Set<String> jsKw = setOf("async","await","break","case","catch","class","const",
            "continue","debugger","default","delete","do","else","enum","export","extends",
            "finally","for","function","if","implements","import","in","instanceof",
            "interface","let","new","of","package","private","protected","public","return",
            "static","super","switch","this","throw","try","typeof","var","void","while",
            "with","yield","true","false","null","undefined","console","from","as","type");
        LANG_KEYWORDS.put("js", jsKw);
        LANG_KEYWORDS.put("ts", jsKw);

        // ---- Bash ----
        LANG_KEYWORDS.put("bash", setOf("if","then","else","elif","fi","for","while","do",
            "done","case","esac","in","function","return","exit","export","local","source",
            "echo","read","set","unset","shift","break","continue","alias","unalias"));

        // ---- C/C++ ----
        Set<String> cppKw = setOf("auto","break","case","const","continue","default","do",
            "else","enum","extern","for","goto","if","register","return","signed","sizeof",
            "static","struct","switch","typedef","union","unsigned","volatile","while",
            "class","namespace","template","typename","virtual","override","final",
            "public","private","protected","new","delete","this","throw","try","catch",
            "nullptr","true","false","include","define","ifdef","ifndef","endif","pragma");
        LANG_KEYWORDS.put("cpp", cppKw);
        LANG_KEYWORDS.put("cpp_types", cStyleTypes);

        // ---- Go ----
        LANG_KEYWORDS.put("go", setOf("break","case","chan","const","continue","default",
            "defer","else","fallthrough","for","func","go","goto","if","import","interface",
            "map","package","range","return","select","struct","switch","type","var",
            "true","false","nil","iota","make","new","len","cap","append","copy","close",
            "delete","panic","recover","print","println","error","string","int","bool"));

        // ---- Rust ----
        LANG_KEYWORDS.put("rust", setOf("as","break","const","continue","crate","else","enum",
            "extern","false","fn","for","if","impl","in","let","loop","match","mod","move",
            "mut","pub","ref","return","self","Self","static","struct","super","trait",
            "true","type","unsafe","use","where","while","async","await","dyn","abstract",
            "become","box","do","final","macro","override","priv","typeof","unsized",
            "virtual","yield","Some","None","Ok","Err","String","Vec","Option","Result"));

        // ---- Kotlin ----
        LANG_KEYWORDS.put("kotlin", setOf("as","break","class","continue","do","else","false",
            "for","fun","if","in","interface","is","null","object","package","return",
            "super","this","throw","true","try","typealias","val","var","when","while",
            "by","catch","constructor","delegate","dynamic","field","file","finally",
            "get","import","init","param","property","receiver","set","setparam","where",
            "actual","expect","companion","const","crossinline","data","enum","external",
            "infix","inline","inner","internal","lateinit","noinline","open","operator",
            "out","override","private","protected","public","reified","sealed","suspend",
            "tailrec","vararg","abstract","annotation","fun","it"));

        // ---- SQL ----
        LANG_KEYWORDS.put("sql", setOf("SELECT","FROM","WHERE","INSERT","UPDATE","DELETE",
            "CREATE","DROP","ALTER","TABLE","INDEX","VIEW","INTO","VALUES","SET","JOIN",
            "LEFT","RIGHT","INNER","OUTER","FULL","ON","AND","OR","NOT","NULL","IS",
            "IN","LIKE","BETWEEN","EXISTS","GROUP","BY","ORDER","ASC","DESC","HAVING",
            "LIMIT","OFFSET","UNION","ALL","DISTINCT","AS","COUNT","SUM","AVG","MAX",
            "MIN","CASE","WHEN","THEN","ELSE","END","PRIMARY","KEY","FOREIGN","REFERENCES",
            "CASCADE","DEFAULT","CHECK","UNIQUE","CONSTRAINT","TRIGGER","PROCEDURE",
            "FUNCTION","BEGIN","COMMIT","ROLLBACK","TRANSACTION","select","from","where"));

        // ---- Swift ----
        LANG_KEYWORDS.put("swift", setOf("class","deinit","enum","extension","func","import",
            "init","internal","let","operator","private","protocol","public","static",
            "struct","subscript","typealias","var","break","case","continue","default",
            "defer","do","else","fallthrough","for","guard","if","in","repeat","return",
            "switch","where","while","as","catch","false","is","nil","self","Self","super",
            "throws","true","try","associativity","convenience","dynamic","didSet",
            "final","get","infix","indirect","lazy","left","mutating","none","nonmutating",
            "optional","override","postfix","precedence","prefix","required","right",
            "set","Type","unowned","weak","willSet","async","await","actor","nonisolated"));

        // ---- C# ----
        LANG_KEYWORDS.put("csharp", setOf("abstract","as","base","bool","break","byte","case",
            "catch","char","checked","class","const","continue","decimal","default","delegate",
            "do","double","else","enum","event","explicit","extern","false","finally",
            "fixed","float","for","foreach","goto","if","implicit","in","int","interface",
            "internal","is","lock","long","namespace","new","null","object","operator",
            "out","override","params","private","protected","public","readonly","ref",
            "return","sbyte","sealed","short","sizeof","stackalloc","static","string",
            "struct","switch","this","throw","true","try","typeof","uint","ulong","unchecked",
            "unsafe","ushort","using","var","virtual","void","volatile","while","async",
            "await","record","init","required","global","file","nint","nuint","dynamic"));

        // ---- Rules per language ----
        // Priority order: comment > string > keyword > number > type > function > operator

        Rule[] cStyleRules = new Rule[] {
            new Rule("cmt", "/\\*[\\s\\S]*?\\*/|//[^\n]*"),
            new Rule("str", "\"(?:[^\"\\\\]|\\\\.)*\""),
            new Rule("str", "'(?:[^'\\\\]|\\\\.)*'"),
            new Rule("num", "\\b0[xX][0-9a-fA-F]+\\b|\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?[fFLl]?\\b"),
        };

        Rule[] pyRules = new Rule[] {
            new Rule("cmt", "#[^\n]*"),
            new Rule("str", "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''"),
            new Rule("str", "\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'"),
            new Rule("str", "f\"(?:[^\"\\\\]|\\\\.)*\"|f'(?:[^'\\\\]|\\\\.)*'"),
            new Rule("num", "\\b0[xX][0-9a-fA-F]+\\b|\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?j?\\b"),
        };

        Rule[] bashRules = new Rule[] {
            new Rule("cmt", "#[^\n]*"),
            new Rule("str", "\"(?:[^\"\\\\]|\\\\.)*\""),
            new Rule("str", "'[^']*'"),
            new Rule("num", "\\b\\d+\\b"),
        };

        Rule[] jsRules = new Rule[] {
            new Rule("cmt", "/\\*[\\s\\S]*?\\*/|//[^\n]*"),
            new Rule("str", "`(?:[^`\\\\]|\\\\.)*`"),
            new Rule("str", "\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'"),
            new Rule("num", "\\b0[xX][0-9a-fA-F]+\\b|\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?\\b"),
        };

        Rule[] xmlRules = new Rule[] {
            new Rule("cmt", "<!--[\\s\\S]*?-->"),
            new Rule("str", "\"[^\"]*\""),
        };

        Rule[] sqlRules = new Rule[] {
            new Rule("cmt", "--[^\n]*|/\\*[\\s\\S]*?\\*/"),
            new Rule("str", "'(?:[^'\\\\]|\\\\.)*'"),
            new Rule("num", "\\b\\d+\\.?\\d*\\b"),
        };

        // ---- SVG ----
        LANG_KEYWORDS.put("svg", setOf(
            "svg", "g", "defs", "symbol", "use", "a", "switch", "foreignObject",
            "path", "circle", "ellipse", "rect", "line", "polyline", "polygon",
            "text", "tspan", "tref", "textPath", "title", "desc", "metadata",
            "image", "clipPath", "mask", "pattern", "marker", "view",
            "linearGradient", "radialGradient", "stop",
            "filter", "feGaussianBlur", "feOffset", "feMerge", "feMergeNode",
            "feColorMatrix", "feBlend", "feComposite", "feFlood", "feImage",
            "feComponentTransfer", "feFuncR", "feFuncG", "feFuncB", "feFuncA",
            "feTurbulence", "feDisplacementMap", "feMorphology",
            "feConvolveMatrix", "feDiffuseLighting", "feSpecularLighting",
            "fePointLight", "feSpotLight", "feDistantLight",
            "animate", "animateTransform", "animateMotion", "set",
            "style", "script"
        ));
        LANG_KEYWORDS.put("svg_attrs", setOf(
            "id", "class", "style", "xmlns", "xmlns:xlink", "version",
            "x", "y", "width", "height", "viewBox",
            "preserveAspectRatio", "transform",
            "cx", "cy", "r", "rx", "ry",
            "x1", "y1", "x2", "y2",
            "d", "pathLength", "points",
            "fill", "fill-opacity", "fill-rule",
            "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin",
            "stroke-dasharray", "stroke-dashoffset", "stroke-opacity",
            "stroke-miterlimit",
            "font-family", "font-size", "font-weight", "font-style",
            "text-anchor", "dominant-baseline", "letter-spacing",
            "text-decoration", "textLength", "lengthAdjust",
            "offset", "stop-color", "stop-opacity",
            "gradientUnits", "gradientTransform", "spreadMethod",
            "stdDeviation", "in", "out", "result",
            "type", "operator", "mode",
            "dx", "dy", "radius",
            "opacity", "visibility", "display",
            "clip-path", "clip-rule",
            "href", "xlink:href",
            "marker-start", "marker-mid", "marker-end",
            "markerWidth", "markerHeight", "orient", "refX", "refY",
            "dur", "begin", "end", "repeatCount",
            "attributeName", "from", "to", "by", "values",
            "keyTimes", "keySplines", "calcMode",
            "requiredFeatures", "requiredExtensions", "systemLanguage",
            "baseProfile", "contentScriptType", "contentStyleType",
            "externalResourcesRequired", "preserveAlpha",
            "viewTarget", "zoomAndPan"
        ));
        LANG_KEYWORDS.put("xml_attrs", setOf(
            "xmlns", "xmlns:xsi", "xmlns:xsd", "xsi:schemaLocation",
            "version", "encoding", "standalone", "id", "name", "type",
            "href", "src", "alt", "lang", "dir", "title"
        ));
        LANG_KEYWORDS.put("html_attrs", setOf(
            "id", "class", "style", "href", "src", "alt", "title",
            "type", "name", "value", "placeholder", "disabled", "readonly",
            "checked", "selected", "required", "maxlength", "minlength",
            "width", "height", "target", "rel", "charset", "content",
            "http-equiv", "lang", "dir", "onclick", "onchange", "oninput",
            "onload", "onerror", "onsubmit", "data-"
        ));

        LANG_RULES.put("java", cStyleRules);
        LANG_RULES.put("cpp", cStyleRules);
        LANG_RULES.put("c", cStyleRules);
        LANG_RULES.put("go", cStyleRules);
        LANG_RULES.put("rust", cStyleRules);
        LANG_RULES.put("kotlin", cStyleRules);
        LANG_RULES.put("kt", cStyleRules);
        LANG_RULES.put("swift", cStyleRules);
        LANG_RULES.put("cs", cStyleRules);
        LANG_RULES.put("csharp", cStyleRules);
        LANG_RULES.put("python", pyRules);
        LANG_RULES.put("py", pyRules);
        LANG_RULES.put("bash", bashRules);
        LANG_RULES.put("sh", bashRules);
        LANG_RULES.put("shell", bashRules);
        LANG_RULES.put("js", jsRules);
        LANG_RULES.put("javascript", jsRules);
        LANG_RULES.put("ts", jsRules);
        LANG_RULES.put("typescript", jsRules);
        LANG_RULES.put("json", jsRules);
        LANG_RULES.put("xml", xmlRules);
        LANG_RULES.put("html", xmlRules);
        LANG_RULES.put("svg", xmlRules);
        LANG_RULES.put("sql", sqlRules);
    }

    @SafeVarargs
    private static Set<String> setOf(String... vals) {
        return new HashSet<>(Arrays.asList(vals));
    }

    public static String highlight(String code, String lang) {
        if (code == null || lang == null) return esc(code);
        lang = lang.trim().toLowerCase();
        if (!SUPPORTED.contains(lang)) return esc(code);

        Rule[] rules = LANG_RULES.get(lang);
        Set<String> keywords = LANG_KEYWORDS.get(lang);
        Set<String> types = LANG_KEYWORDS.get(lang + "_types");

        StringBuilder out = new StringBuilder();
        int i = 0;
        int len = code.length();

        while (i < len) {
            boolean matched = false;

            // 1. Try rules (comments, strings, numbers)
            if (rules != null) {
                for (Rule r : rules) {
                    Matcher m = r.pattern.matcher(code);
                    if (m.find(i) && m.start() == i) {
                        out.append("<span class=\"tk-").append(r.type).append("\">");
                        out.append(esc(m.group()));
                        out.append("</span>");
                        i = m.end();
                        matched = true;
                        break;
                    }
                }
            }
            if (matched) continue;

            // 2. Match identifiers (keywords, types, functions)
            if (i < len && Character.isJavaIdentifierStart(code.charAt(i))) {
                int start = i;
                while (i < len && Character.isJavaIdentifierPart(code.charAt(i))) i++;
                String word = code.substring(start, i);

                if (keywords != null && keywords.contains(word))
                    out.append("<span class=\"tk-kw\">").append(esc(word)).append("</span>");
                else if (types != null && types.contains(word))
                    out.append("<span class=\"tk-type\">").append(esc(word)).append("</span>");
                else if (i < len && code.charAt(i) == '(')
                    out.append("<span class=\"tk-fn\">").append(esc(word)).append("</span>");
                else
                    out.append(esc(word));
                continue;
            }

            // 3. XML / SVG / HTML tags — highlight element names and attributes
            if (("xml".equals(lang) || "html".equals(lang) || "svg".equals(lang))
                    && code.charAt(i) == '<') {
                Set<String> attrSet = LANG_KEYWORDS.get(lang + "_attrs");

                // Handle CDATA section
                if (i + 9 < len && code.startsWith("<![CDATA[", i)) {
                    int end = code.indexOf("]]>", i + 9);
                    if (end > i) {
                        out.append("<span class=\"tk-cmt\">");
                        out.append(esc(code.substring(i, end + 3)));
                        out.append("</span>");
                        i = end + 3; continue;
                    }
                }

                // Handle DOCTYPE / processing instructions <?...?>
                if (i + 1 < len && (code.charAt(i + 1) == '!' || code.charAt(i + 1) == '?')) {
                    int start = i; i++;
                    while (i < len && code.charAt(i) != '>') i++;
                    if (i < len) i++;
                    out.append("<span class=\"tk-cmt\">");
                    out.append(esc(code.substring(start, i)));
                    out.append("</span>");
                    continue;
                }

                // Closing tag: </tagname>
                if (i + 1 < len && code.charAt(i + 1) == '/') {
                    int start = i;
                    i += 2; // skip </
                    while (i < len && code.charAt(i) != '>') i++;
                    if (i < len) i++; // skip >
                    out.append("<span class=\"tk-kw\">");
                    out.append(esc(code.substring(start, i)));
                    out.append("</span>");
                    continue;
                }

                // Opening / self-closing tag: <tagname ...>
                int tagStart = i;
                i++; // skip <
                // Tag name
                int nameStart = i;
                while (i < len && !Character.isWhitespace(code.charAt(i))
                        && code.charAt(i) != '>' && code.charAt(i) != '/') i++;
                String tagName = code.substring(nameStart, i);
                out.append("&lt;<span class=\"tk-kw\">").append(esc(tagName)).append("</span>");

                // Parse attributes
                while (i < len && code.charAt(i) != '>' && code.charAt(i) != '/') {
                    // Whitespace before attribute
                    int wsStart = i;
                    while (i < len && Character.isWhitespace(code.charAt(i))) i++;
                    if (wsStart < i) out.append(code, wsStart, i);
                    if (i >= len || code.charAt(i) == '>' || code.charAt(i) == '/') break;

                    // Attribute name — stop at =, >, /, or whitespace
                    int attrStart = i;
                    while (i < len && code.charAt(i) != '=' && code.charAt(i) != '>'
                            && code.charAt(i) != '/' && !Character.isWhitespace(code.charAt(i))) i++;
                    String attrName = code.substring(attrStart, i);
                    if (attrName.isEmpty()) { out.append(code.charAt(i)); i++; continue; }
                    if (attrSet != null && (attrSet.contains(attrName) || attrName.startsWith("data-")))
                        out.append("<span class=\"tk-type\">").append(esc(attrName)).append("</span>");
                    else
                        out.append(esc(attrName));

                    // Skip whitespace before =
                    while (i < len && Character.isWhitespace(code.charAt(i))) { out.append(' '); i++; }

                    // = and value
                    if (i < len && code.charAt(i) == '=') {
                        out.append('='); i++;
                        if (i < len && code.charAt(i) == '"') {
                            int valStart = i; i++;
                            while (i < len && code.charAt(i) != '"') {
                                if (code.charAt(i) == '\\') i++;
                                i++;
                            }
                            if (i < len) i++; // closing "
                            String raw = code.substring(valStart, i);
                            String inner = code.substring(valStart + 1, i - 1);
                            // Color / number detection for SVG attr values
                            if ((lang.equals("svg") || lang.equals("xml")) &&
                                    (inner.matches("#[0-9a-fA-F]{3,8}") ||
                                     inner.matches("rgb\\(\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*(,\\s*[\\d.]+\\s*)?\\)") ||
                                     inner.matches("url\\([^)]*\\)") ||
                                     inner.matches("[\\d.]+(px|em|rem|pt|%|cm|mm|in)?")))
                                out.append("<span class=\"tk-num\">").append(esc(raw)).append("</span>");
                            else
                                out.append("<span class=\"tk-str\">").append(esc(raw)).append("</span>");
                        }
                    }
                }

                // Self-closing /> or closing >
                if (i < len && code.charAt(i) == '/') { out.append('/'); i++; }
                if (i < len && code.charAt(i) == '>') { out.append("&gt;"); i++; }
                continue;
            }

            // 4. Operators
            if ("=+-*/%<>!&|^~?:.,;{}[]()".indexOf(code.charAt(i)) >= 0) {
                int start = i;
                i++;
                while (i < len && "=+-*/%<>!&|^~?:.,;".indexOf(code.charAt(i)) >= 0) i++;
                out.append("<span class=\"tk-op\">").append(esc(code.substring(start, i))).append("</span>");
                continue;
            }

            // 5. Plain text
            out.append(esc(String.valueOf(code.charAt(i))));
            i++;
        }

        return out.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
