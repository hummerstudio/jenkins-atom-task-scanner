package com.jenkinschina.atom.taskscanner;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * task-scanner as an atom: find the TODO/FIXME/XXX markers a build is supposed to keep an eye on.
 *
 * <p>Useful as a soft gate: report the count, fail only when the number grows past a budget the team agreed on.
 */
public class TaskScannerAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String includes = context.getParam("includes", "**/*.java, **/*.kt, **/*.js, **/*.ts, **/*.py, **/*.go, "
                + "**/*.rs, **/*.c, **/*.cpp, **/*.h, **/*.sh, **/*.rb, **/*.php, **/*.cs");
        String excludes = context.getParam("excludes", "**/target/**, **/build/**, **/node_modules/**, **/.git/**, "
                + "**/dist/**, **/out/**");
        String markers = context.getParam("markers", "TODO, FIXME, XXX, HACK");
        String label = context.getParam("label", "Outstanding tasks");
        String outDir = context.getParam("outputDir", "out/tasks");
        int maxMarkers = context.getIntParam("maxMarkers", -1);

        Pattern marker = pattern(markers);
        if (marker == null) {
            return AtomResult.error(ErrorType.USER, 40001, "no usable marker in '" + markers + "'");
        }

        List<PathMatcher> include = matchers(includes);
        List<PathMatcher> exclude = matchers(excludes);
        List<Marker> found = new ArrayList<>();
        int scanned = 0;

        try (var walk = Files.walk(workspace, 20)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                if (scanned >= 20_000) {
                    break;
                }
                Path relative = workspace.relativize(path);
                if (include.stream().noneMatch(m -> m.matches(relative) || m.matches(java.nio.file.Path.of("_").resolve(relative)) || m.matches(path.getFileName()))
                        || exclude.stream().anyMatch(m -> m.matches(relative) || m.matches(java.nio.file.Path.of("_").resolve(relative)))) {
                    continue;
                }
                scanned++;
                List<String> lines;
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    continue;
                }
                for (int i = 0; i < lines.size(); i++) {
                    Matcher matcher = marker.matcher(lines.get(i));
                    if (matcher.find()) {
                        found.add(new Marker(relative.toString(), i + 1, matcher.group(1).toUpperCase(Locale.ROOT),
                                abbreviate(lines.get(i))));
                    }
                }
            }
        } catch (IOException e) {
            return AtomResult.error(ErrorType.SYSTEM, 40002, "could not walk the workspace: " + e.getMessage());
        }

        Map<String, Integer> byKind = new LinkedHashMap<>();
        Map<String, Integer> byFile = new LinkedHashMap<>();
        for (Marker hit : found) {
            byKind.merge(hit.kind(), 1, Integer::sum);
            byFile.merge(hit.file(), 1, Integer::sum);
        }

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), render(label, scanned, byKind, byFile, found),
                StandardCharsets.UTF_8);
        context.log("[tasks] " + found.size() + " marker(s) in " + scanned + " file(s): " + byKind);

        AtomResult result = AtomResult.success()
                .data("markers", String.valueOf(found.size()))
                .data("files", String.valueOf(scanned))
                .data("todo", String.valueOf(byKind.getOrDefault("TODO", 0)))
                .data("fixme", String.valueOf(byKind.getOrDefault("FIXME", 0)))
                .data("report", found.isEmpty() ? "" : "out/tasks")
                .report("report", label, dir.toString(), "index.html");

        if (maxMarkers >= 0 && found.size() > maxMarkers) {
            return AtomResult.failure(found.size() + " marker(s), the budget is " + maxMarkers)
                    .data("markers", String.valueOf(found.size()))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    private static Pattern pattern(String markers) {
        List<String> words = new ArrayList<>();
        for (String part : markers.split(",")) {
            String trimmed = part.trim();
            if (trimmed.matches("[A-Za-z]{2,12}")) {
                words.add(Pattern.quote(trimmed));
            }
        }
        return words.isEmpty() ? null : Pattern.compile("\\b(" + String.join("|", words) + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    private static List<PathMatcher> matchers(String spec) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
            }
        }
        return matchers;
    }

    private static String abbreviate(String line) {
        String stripped = line.strip();
        return stripped.length() > 160 ? stripped.substring(0, 160) + "…" : stripped;
    }

    private static String render(String label, int scanned, Map<String, Integer> byKind, Map<String, Integer> byFile,
                                 List<Marker> markers) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:20px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:90px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3;vertical-align:top}")
                .append("th{background:#fafbfc;color:#6b7280}code{font-family:ui-monospace,Menlo,monospace}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(label)).append("</h1><p class=\"meta\">").append(markers.size())
                .append(" marker(s) in ").append(scanned).append(" file(s)</p><div class=\"cards\">");
        byKind.forEach((kind, count) -> html.append("<div class=\"card\"><b>").append(count).append("</b><span>")
                .append(escape(kind)).append("</span></div>"));
        html.append("</div><table><tr><th>File</th><th>Line</th><th>Marker</th><th>Text</th></tr>");
        for (Marker marker : markers.subList(0, Math.min(markers.size(), 400))) {
            html.append("<tr><td><code>").append(escape(marker.file())).append("</code></td><td>")
                    .append(marker.line()).append("</td><td>").append(escape(marker.kind())).append("</td><td>")
                    .append(escape(marker.text())).append("</td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Marker(String file, int line, String kind, String text) {}
}
