package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal offline renderer for a subset of Mermaid flowchart syntax.
 *
 * Supports the shapes of diagram an LLM emits most often:
 * <pre>
 *   flowchart TD | graph LR | flowchart TB   (also BT / RL)
 *   A[Rectangle]  B(Rounded)  C([Stadium])  D((Circle))  E{Diamond}
 *   F{{Hexagon}}  G[/Parallelogram/]  H[\AltParallelogram\]
 *   A --&gt; B   A -&gt;|label| B   A --- B   A -->|text| B   A -.-> B   A ==> B
 *   %% comments
 * </pre>
 * The result is a self-contained SVG document string (ES3-free — everything is
 * computed in Java so the old API 19 WebView only has to parse static markup).
 */
final class FlowchartRenderer {

    private static final int NODE_PAD_X = 14;
    private static final int NODE_PAD_Y = 10;
    private static final int CHAR_W = 8;
    private static final int NODE_H = 34;
    private static final int RANK_GAP = 56;
    private static final int SIBLING_GAP = 28;
    private static final int MARGIN = 16;
    private static final int MIN_W = 48;

    private FlowchartRenderer() {
    }

    private static final class Node {
        String id;
        String label;
        String shape = "rect"; // rect | round | stadium | circle | diamond | hexagon | para
        int w, h;
        int x, y;      // top-left of the rendered box (in the rotated layout space)
        int rank;      // distance from a source, along the flow direction
        int order;     // insertion order inside its rank
        int depth;     // coordinate along the rank axis (x of the un-rotated box)
        int cross;     // coordinate across ranks (y of the un-rotated box)
    }

    private static final class Edge {
        Node from, to;
        String label;
        boolean dashed;
    }

    /**
     * Splits {@code left <connector> right} out of one arrow chain. The connector alternates
     * between the two mermaid label syntaxes, both of which must be consumed whole or the
     * label text leaks into the chain as a phantom node:
     * <ul>
     *   <li>{@code A -- text --> B} — label embedded between the dash runs</li>
     *   <li>{@code A -->|text| B} — label encoded as a pipe-quoted segment</li>
     * </ul>
     * Also accepts unlabelled {@code -->}, {@code ---}, {@code -.->} and {@code ==>}.
     * Groups: 1 = left node, 2 = label (nullable), 3 = arrow kind ({@code '.'} marks dashed).
     */
    private static final Pattern EDGE_RE = Pattern.compile(
            "^(.*?)\\s*(?:-{1,3}\\s+([^-|]+?)\\s+-{1,3}>?|\\|([^|]+)\\|\\s*|(\\.?-{1,3}>?|\\.-+>|={1,3}>?))\\s*(.*)$");

    static String isFlowchartLang(String info) {
        if (info == null) return null;
        String l = info.trim().toLowerCase();
        if (l.startsWith("mermaid")) return "mermaid";
        if (l.equals("flowchart") || l.equals("flow") || l.equals("graph")) return l;
        return null;
    }

    /** Returns true when the body looks like a flowchart rather than a sequence/pie/etc. */
    static boolean looksLikeFlowchart(String code) {
        if (code == null) return false;
        String[] lines = firstMeaningfulLines(code, 3);
        for (String ln : lines) {
            String s = ln.trim().toLowerCase();
            if (s.startsWith("flowchart") || s.startsWith("graph ")) return true;
            if (s.startsWith("graph\t") || s.equals("graph")) return true;
            if (s.contains("-->") || s.contains("---") || s.contains("==>")) return true;
        }
        return false;
    }

    private static String[] firstMeaningfulLines(String code, int max) {
        List<String> out = new ArrayList<String>();
        String[] all = code.split("\n");
        for (String raw : all) {
            String s = stripComment(raw).trim();
            if (s.isEmpty()) continue;
            out.add(s);
            if (out.size() >= max) break;
        }
        return out.toArray(new String[0]);
    }

    private static String stripComment(String line) {
        int i = line.indexOf("%%");
        return i < 0 ? line : line.substring(0, i);
    }

    /**
     * Renders the mermaid source. Returns null when the source has no usable
     * nodes, so the caller can fall back to a plain code preview.
     */
    static String renderSvg(String code, String bgColor, String fgColor) {
        if (code == null) return null;
        String[] rawLines = code.split("\n");
        List<String> lines = new ArrayList<String>();
        for (String raw : rawLines) {
            String s = stripComment(raw).trim();
            if (s.isEmpty()) continue;
            lines.add(s);
        }
        if (lines.isEmpty()) return null;

        boolean vertical = true;
        List<String> body = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            String low = s.toLowerCase();
            if (low.startsWith("flowchart") || low.startsWith("graph")) {
                if (low.contains("lr")) vertical = false;
                if (low.contains("rl")) vertical = false;
                if (low.contains("bt")) vertical = true;
                continue;
            }
            if (low.startsWith("subgraph") || low.equals("end")
                    || low.startsWith("style") || low.startsWith("classdef")
                    || low.startsWith("class ") || low.startsWith("linkstyle")
                    || low.startsWith("click")) {
                continue;
            }
            body.add(s);
        }
        if (body.isEmpty()) return null;

        Map<String, Node> nodes = new LinkedHashMap<String, Node>();
        List<Edge> edges = new ArrayList<Edge>();
        int order = 0;

        for (String stmt : body) {
            // A single line may chain several arrows: A --> B --> C
            List<String> parts = splitChain(stmt);
            Node prev = null;
            for (int i = 0; i < parts.size(); i++) {
                String seg = parts.get(i);
                if (i % 2 == 0) {
                    Node n = parseNodeToken(seg, nodes, order++);
                    if (n == null) {
                        prev = null;
                        break;
                    }
                    if (prev != null) {
                        String conn = parts.get(i - 1);
                        // splitChain stores a labelled connector as "arrow|label|";
                        // the label must be extracted here or the raw arrow token
                        // ends up rendered as the edge caption.
                        int bar = conn.indexOf('|');
                        int end = conn.lastIndexOf('|');
                        Edge e = new Edge();
                        e.from = prev;
                        e.to = n;
                        e.dashed = conn.indexOf('.') >= 0;
                        if (bar >= 0 && end > bar) {
                            String l = conn.substring(bar + 1, end).trim();
                            e.label = l.isEmpty() ? null : l;
                        } else {
                            e.label = null;
                        }
                        edges.add(e);
                    }
                    prev = n;
                }
            }
        }
        if (nodes.isEmpty()) return null;

        if ("BT".equals(hintDirection(lines))) vertical = true;

        assignRanks(nodes, edges);
        layout(nodes, edges, vertical);
        return buildSvg(nodes, edges, bgColor, fgColor, vertical);
    }

    private static String hintDirection(List<String> lines) {
        for (String s : lines) {
            String low = s.trim().toLowerCase();
            if (low.startsWith("flowchart") || low.startsWith("graph")) {
                if (low.contains("bt")) return "BT";
                if (low.contains("rl")) return "RL";
            }
        }
        return "";
    }

    /** Splits "A --> B --> C" into [A, "-->", B, "-->", C]; returns null-ish safe list. */
    private static List<String> splitChain(String stmt) {
        List<String> out = new ArrayList<String>();
        String rest = stmt;
        while (true) {
            Matcher m = EDGE_RE.matcher(rest);
            if (!m.matches()) {
                out.add(rest.trim());
                return out;
            }
            String left = m.group(1).trim();
            String labelA = m.group(2);
            String labelB = m.group(3);
            String arrow = m.group(4);
            String right = m.group(5) == null ? "" : m.group(5).trim();
            String label = labelA != null ? labelA.trim()
                    : (labelB != null ? labelB.trim() : null);
            if (arrow == null || arrow.isEmpty()) arrow = "-->";
            out.add(left);
            out.add(label == null || label.isEmpty() ? arrow : arrow + "|" + label + "|");
            if (right.isEmpty()) return out;
            // Guard against a connector that cannot consume input: without this the
            // same remainder matches forever and renderSvg never returns.
            if (right.length() >= rest.length()) {
                out.add(right);
                return out;
            }
            rest = right;
        }
    }

    private static Node parseNodeToken(String token, Map<String, Node> nodes, int order) {
        String t = token.trim();
        if (t.isEmpty()) return null;

        String id;
        String label = null;
        String shape = "rect";

        int open = indexOfShapeOpen(t);
        if (open < 0 || !t.endsWith(")") && !t.endsWith("]") && !t.endsWith("}")) {
            id = t;
        } else {
            id = t.substring(0, open).trim();
            String inner = t.substring(open);
            char c0 = inner.charAt(0);
            if (c0 == '[') {
                String body = inner.substring(1, inner.length() - 1);
                if (body.startsWith("(/") && body.endsWith("/)")) {
                    shape = "para";
                    label = body.substring(2, body.length() - 2);
                } else {
                    shape = "rect";
                    label = body;
                }
            } else if (c0 == '(') {
                String body = inner.substring(1, inner.length() - 1);
                if (body.startsWith("(") && body.endsWith(")")) {
                    shape = "circle";
                    label = body.substring(1, body.length() - 1);
                } else if (body.startsWith("[") && body.endsWith("]")) {
                    shape = "stadium";
                    label = body.substring(1, body.length() - 1);
                } else {
                    shape = "round";
                    label = body;
                }
            } else if (c0 == '{') {
                String body = inner.substring(1, inner.length() - 1);
                if (body.startsWith("{") && body.endsWith("}")) {
                    shape = "hexagon";
                    label = body.substring(1, body.length() - 1);
                } else {
                    shape = "diamond";
                    label = body;
                }
            }
        }
        if (id.isEmpty()) return null;

        label = cleanLabel(label == null ? id : label);

        Node n = nodes.get(id);
        if (n == null) {
            n = new Node();
            n.id = id;
            n.label = label;
            n.shape = shape;
            n.order = order;
            nodes.put(id, n);
        } else if (label != null && !label.equals(id)) {
            n.label = label;
            n.shape = shape;
        }
        return n;
    }

    private static int indexOfShapeOpen(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '[' || c == '(' || c == '{') return i;
        }
        return -1;
    }

    /** Strips mermaid quoting/&lt;br&gt; and HTML-escapes for the SVG text node. */
    private static String cleanLabel(String s) {
        String v = s.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        v = v.replace("<br/>", " ").replace("<br>", " ");
        return v;
    }

    private static void assignRanks(Map<String, Node> nodes, List<Edge> edges) {
        for (Node n : nodes.values()) {
            n.rank = 0;
            n.depth = 0;
        }
        // Keep only the edges of an acyclic backbone: mermaid flowcharts routinely loop
        // back (retry paths), and relaxing ranks over a cycle makes them diverge.
        // Order edges by insertion so the forward flow wins over back references.
        List<Edge> acyclic = new ArrayList<Edge>();
        Map<String, Integer> index = new HashMap<String, Integer>();
        int seq = 0;
        for (Node n : nodes.values()) index.put(n.id, seq++);
        for (Edge e : edges) {
            Integer a = index.get(e.from.id);
            Integer b = index.get(e.to.id);
            if (a == null || b == null || a.intValue() >= b.intValue()) continue;
            acyclic.add(e);
        }
        // Relax ranks along the edge direction; bounded by node count.
        int maxIter = nodes.size() + 1;
        for (int it = 0; it < maxIter; it++) {
            boolean changed = false;
            for (Edge e : acyclic) {
                if (e.from == e.to) continue;
                if (e.to.rank < e.from.rank + 1) {
                    e.to.rank = e.from.rank + 1;
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    private static void layout(Map<String, Node> nodes, List<Edge> edges, boolean vertical) {
        int maxRank = 0;
        for (Node n : nodes.values()) if (n.rank > maxRank) maxRank = n.rank;

        List<List<Node>> ranks = new ArrayList<List<Node>>();
        for (int i = 0; i <= maxRank; i++) ranks.add(new ArrayList<Node>());
        for (Node n : nodes.values()) ranks.get(n.rank).add(n);

        // Pass 1: un-rotated box, rank axis = y (vertical flow).
        int y = MARGIN;
        int maxCross = 0;
        for (int r = 0; r <= maxRank; r++) {
            List<Node> row = ranks.get(r);
            int cross = MARGIN;
            int rowH = 0;
            for (Node n : row) {
                measure(n);
                n.y = y;
                n.x = cross;
                cross += n.w + SIBLING_GAP;
                if (n.h > rowH) rowH = n.h;
            }
            if (cross - SIBLING_GAP + MARGIN > maxCross) {
                maxCross = cross - SIBLING_GAP + MARGIN;
            }
            y += rowH + RANK_GAP;
        }
        int totalH = y - RANK_GAP + MARGIN;
        int totalW = maxCross;

        // Centre every rank horizontally in the drawing box.
        for (int r = 0; r <= maxRank; r++) {
            List<Node> row = ranks.get(r);
            int rowW = 0;
            for (int i = 0; i < row.size(); i++) {
                rowW += row.get(i).w;
                if (i < row.size() - 1) rowW += SIBLING_GAP;
            }
            int off = MARGIN + (totalW - 2 * MARGIN - rowW) / 2;
            for (Node n : row) {
                n.x = off;
                off += n.w + SIBLING_GAP;
            }
        }

        if (!vertical) {
            // Rotate the layout 90°: flow runs along x, siblings stack along y.
            for (Node n : nodes.values()) {
                int bx = n.y - MARGIN;
                int by = n.x - MARGIN;
                int oldW = n.w, oldH = n.h;
                n.w = oldH;
                n.h = oldW;
                n.x = bx;
                n.y = by;
            }
        }
    }

    private static void measure(Node n) {
        int textW = n.label.length() * CHAR_W;
        switch (n.shape) {
            case "diamond":
            case "hexagon":
                n.w = Math.max(MIN_W, (int) (textW * 1.4) + NODE_PAD_X * 2);
                n.h = NODE_H + 10;
                break;
            case "circle": {
                int side = Math.max(NODE_H, textW + NODE_PAD_X * 2);
                n.w = side;
                n.h = side;
                break;
            }
            default:
                n.w = Math.max(MIN_W, textW + NODE_PAD_X * 2);
                n.h = NODE_H;
        }
    }

    private static String buildSvg(Map<String, Node> nodes, List<Edge> edges,
                                   String bgColor, String fgColor, boolean vertical) {
        int maxX = 0, maxY = 0;
        for (Node n : nodes.values()) {
            if (n.x + n.w > maxX) maxX = n.x + n.w;
            if (n.y + n.h > maxY) maxY = n.y + n.h;
        }
        maxX += MARGIN;
        maxY += MARGIN;

        // Reserve the first RANK_GAP band for edge routing so an arrow that leaves
        // a node's long side (the axis-aligned anchor) is drawn as an orthogonal
        // elbow instead of cutting diagonally across the diagram.
        int lane = RANK_GAP;
        maxX += lane;
        maxY += lane;

        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(maxX)
                .append("\" height=\"").append(maxY)
                .append("\" viewBox=\"0 0 ").append(maxX).append(' ').append(maxY)
                .append("\" preserveAspectRatio=\"xMidYMin meet\">");
        sb.append("<defs><marker id=\"fc-arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\"")
                .append(" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">")
                .append("<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"").append(fgColor)
                .append("\"/></marker></defs>");
        sb.append("<rect x=\"0\" y=\"0\" width=\"").append(maxX).append("\" height=\"")
                .append(maxY).append("\" fill=\"").append(bgColor).append("\" id=\"fc-bg\"/>");

        for (Edge e : edges) {
            appendEdge(sb, e, fgColor);
        }
        for (Node n : nodes.values()) {
            appendNode(sb, n, bgColor, fgColor);
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private static void appendEdge(StringBuilder sb, Edge e, String fgColor) {
        int[] a = anchor(e.from, e.to);
        int[] b = anchor(e.to, e.from);
        String dash = e.dashed ? " stroke-dasharray=\"6,4\"" : "";

        // Route down the dedicated lane band reserved by buildSvg. A vertical edge
        // gets no bend (a == b on x); the rest leave from the node's top or bottom
        // and jog sideways inside the lane, which also pushes their labels apart.
        int laneY = anchor(e.from, e.to)[1];
        boolean fromBottom = a[1] >= e.from.y + e.from.h;
        int turn = fromBottom ? laneY + RANK_GAP / 2 : laneY - RANK_GAP / 2;

        sb.append("<path d=\"M ").append(a[0]).append(' ').append(a[1])
                .append(" L ").append(a[0]).append(' ').append(turn)
                .append(" L ").append(b[0]).append(' ').append(turn)
                .append(" L ").append(b[0]).append(' ').append(b[1])
                .append("\" fill=\"none\" stroke=\"").append(fgColor)
                .append("\" stroke-width=\"1.5\"").append(dash)
                .append(" marker-end=\"url(#fc-arrow)\"/>");

        if (e.label != null && !e.label.isEmpty()) {
            int lx = (int) Math.round((a[0] + b[0]) / 2.0);
            int ly = turn - 3;
            sb.append("<rect x=\"").append(lx - e.label.length() * 4 - 3)
                    .append("\" y=\"").append(ly - 8)
                    .append("\" width=\"").append(e.label.length() * 8 + 6)
                    .append("\" height=\"16\" fill=\"").append(fgColor).append("\" opacity=\"0\"/>");
            sb.append("<text x=\"").append(lx).append("\" y=\"").append(ly + 4)
                    .append("\" font-size=\"11\" font-family=\"sans-serif\" text-anchor=\"middle\"")
                    .append(" fill=\"").append(fgColor).append("\">")
                    .append(escape(e.label)).append("</text>");
        }
    }

    /** Chooses the border point of {@code n} facing {@code other} (axis-aligned only). */
    private static int[] anchor(Node n, Node other) {
        int cx = n.x + n.w / 2;
        int cy = n.y + n.h / 2;
        int ox = other.x + other.w / 2;
        int oy = other.y + other.h / 2;
        if (Math.abs(ox - cx) >= Math.abs(oy - cy)) {
            return new int[]{ox > cx ? n.x + n.w : n.x, cy};
        }
        return new int[]{cx, oy > cy ? n.y + n.h : n.y};
    }

    private static void appendNode(StringBuilder sb, Node n, String bgColor, String fgColor) {
        int x = n.x, y = n.y, w = n.w, h = n.h;
        String stroke = " stroke=\"" + fgColor + "\" stroke-width=\"1.5\" fill=\"" + bgColor + "\"";
        sb.append("<g>");
        if ("diamond".equals(n.shape)) {
            sb.append("<polygon points=\"")
                    .append(x + w / 2).append(',').append(y).append(' ')
                    .append(x + w).append(',').append(y + h / 2).append(' ')
                    .append(x + w / 2).append(',').append(y + h).append(' ')
                    .append(x).append(',').append(y + h / 2)
                    .append("\"").append(stroke).append("/>");
        } else if ("hexagon".equals(n.shape)) {
            int c = h / 2;
            sb.append("<polygon points=\"")
                    .append(x + c).append(',').append(y).append(' ')
                    .append(x + w - c).append(',').append(y).append(' ')
                    .append(x + w).append(',').append(y + h / 2).append(' ')
                    .append(x + w - c).append(',').append(y + h).append(' ')
                    .append(x + c).append(',').append(y + h).append(' ')
                    .append(x).append(',').append(y + h / 2)
                    .append("\"").append(stroke).append("/>");
        } else if ("para".equals(n.shape)) {
            int s = h / 2;
            sb.append("<polygon points=\"")
                    .append(x).append(',').append(y).append(' ')
                    .append(x + w).append(',').append(y).append(' ')
                    .append(x + w - s).append(',').append(y + h).append(' ')
                    .append(x - s + s).append(',').append(y + h)
                    .append("\"").append(stroke).append("/>");
        } else if ("stadium".equals(n.shape) || "round".equals(n.shape)) {
            int r = "stadium".equals(n.shape) ? h / 2 : 6;
            sb.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" width=\"").append(w).append("\" height=\"").append(h)
                    .append("\" rx=\"").append(r).append("\" ry=\"").append(r)
                    .append("\"").append(stroke).append("/>");
        } else if ("circle".equals(n.shape)) {
            sb.append("<ellipse cx=\"").append(x + w / 2).append("\" cy=\"").append(y + h / 2)
                    .append("\" rx=\"").append(w / 2).append("\" ry=\"").append(h / 2)
                    .append("\"").append(stroke).append("/>");
        } else {
            sb.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" width=\"").append(w).append("\" height=\"").append(h)
                    .append("\" rx=\"4\" ry=\"4\"").append(stroke).append("/>");
        }
        sb.append("<text x=\"").append(x + w / 2).append("\" y=\"").append(y + h / 2 + 4)
                .append("\" font-size=\"12\" font-family=\"sans-serif\" text-anchor=\"middle\"")
                .append(" fill=\"").append(fgColor).append("\">")
                .append(escape(n.label)).append("</text>");
        sb.append("</g>");
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
