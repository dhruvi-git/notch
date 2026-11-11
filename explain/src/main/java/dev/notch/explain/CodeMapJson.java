package dev.notch.explain;

import java.util.List;
import java.util.Map;

/**
 * Writes a {@link CodeMap} as JSON, field for field, in the shape the API reads it back in.
 * Hand-written so the CLI stays a single dependency-free jar.
 */
public final class CodeMapJson {

    private CodeMapJson() {
    }

    public static String write(CodeMap m) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("{\"stats\":");
        CodeMap.Stats s = m.stats();
        sb.append("{\"files\":").append(s.files()).append(",\"codeFiles\":").append(s.codeFiles())
                .append(",\"lines\":").append(s.lines()).append(",\"languages\":");
        counts(sb, s.languages());
        sb.append(",\"skipped\":").append(s.skipped()).append(",\"truncated\":").append(s.truncated())
                .append(",\"filesWithSecrets\":");
        strings(sb, s.filesWithSecrets());
        sb.append("},\"files\":[");
        for (int i = 0; i < m.files().size(); i++) {
            if (i > 0) sb.append(',');
            CodeMap.FileInfo f = m.files().get(i);
            sb.append("{\"path\":");
            quote(sb, f.path());
            sb.append(",\"language\":");
            quote(sb, f.language());
            sb.append(",\"lines\":").append(f.lines()).append(",\"role\":");
            quote(sb, f.role().name());
            sb.append(",\"route\":");
            quote(sb, f.route());
            sb.append(",\"summary\":");
            quote(sb, f.summary());
            sb.append(",\"symbols\":");
            strings(sb, f.symbols());
            sb.append(",\"endpoints\":");
            strings(sb, f.endpoints());
            sb.append(",\"imports\":");
            strings(sb, f.imports());
            sb.append(",\"importedBy\":");
            strings(sb, f.importedBy());
            sb.append(",\"packages\":");
            strings(sb, f.packages());
            sb.append(",\"tools\":");
            strings(sb, f.tools());
            sb.append(",\"module\":");
            quote(sb, f.module());
            sb.append(",\"secrets\":").append(f.secrets()).append('}');
        }
        sb.append("],\"modules\":[");
        for (int i = 0; i < m.modules().size(); i++) {
            if (i > 0) sb.append(',');
            CodeMap.Module mod = m.modules().get(i);
            sb.append("{\"path\":");
            quote(sb, mod.path());
            sb.append(",\"files\":").append(mod.files()).append(",\"lines\":").append(mod.lines()).append(",\"dependsOn\":");
            strings(sb, mod.dependsOn());
            sb.append(",\"usedBy\":");
            strings(sb, mod.usedBy());
            sb.append('}');
        }
        sb.append("],\"areas\":[");
        for (int i = 0; i < m.areas().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"files\":").append(m.areas().get(i).files()).append(",\"modules\":");
            strings(sb, m.areas().get(i).modules());
            sb.append('}');
        }
        sb.append("],\"startHere\":");
        strings(sb, m.startHere());
        sb.append(",\"entryPoints\":");
        strings(sb, m.entryPoints());
        sb.append(",\"possiblyUnused\":");
        strings(sb, m.possiblyUnused());
        return sb.append('}').toString();
    }

    private static void counts(StringBuilder sb, Map<String, Integer> m) {
        sb.append('{');
        int i = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (i++ > 0) sb.append(',');
            quote(sb, e.getKey());
            sb.append(':').append(e.getValue());
        }
        sb.append('}');
    }

    private static void strings(StringBuilder sb, List<String> l) {
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            quote(sb, l.get(i));
        }
        sb.append(']');
    }

    static void quote(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
