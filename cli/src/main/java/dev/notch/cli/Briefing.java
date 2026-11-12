package dev.notch.cli;

import dev.notch.guard.Decision;
import dev.notch.guard.Json;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** "Where you stopped": the server's memory of the project plus the repository's live state. */
final class Briefing {

    static final int MAX_CHARS = 6_000;

    private Briefing() {
    }

    static String build(ApiClient api, Path root, String projectName) throws NotchBackend.NotchException {
        Map<String, Object> r = api.resume();
        List<Decision> decisions = api.decisions();
        StringBuilder sb = new StringBuilder();
        String branch = root == null ? null : GitHistory.branch(root);
        sb.append("Where you stopped in ").append(projectName);
        if (branch != null && !branch.isBlank()) sb.append(" (branch ").append(branch).append(')');
        sb.append(":\n");
        for (Object line : Json.arr(r.get("whereYouStopped"))) sb.append("- ").append(line).append('\n');
        if (root != null) {
            List<String> changed = new java.util.ArrayList<>(GitHistory.uncommitted(root));
            // Files notch init itself writes aren't the work in progress.
            changed.removeIf(f -> f.equals(".notch") || f.equals(".mcp.json") || f.equals(".claude/") || f.startsWith(".claude/settings"));
            if (!changed.isEmpty()) {
                sb.append("- Uncommitted changes (").append(changed.size()).append("): ")
                        .append(String.join(", ", changed.subList(0, Math.min(8, changed.size()))))
                        .append(changed.size() > 8 ? " and more" : "").append('\n');
            }
            String last = GitHistory.lastCommitLine(root);
            if (last != null && !last.isBlank()) sb.append("- Last commit: ").append(last).append('\n');
        }
        List<Object> tasks = Json.arr(r.get("openTasks"));
        if (!tasks.isEmpty()) {
            sb.append("\nNext up:\n");
            for (Object t : tasks.subList(0, Math.min(5, tasks.size()))) sb.append("- ").append(Json.str(Json.obj(t), "title")).append('\n');
        }
        if (!decisions.isEmpty()) {
            sb.append("\nDecisions in force (follow these unless told otherwise):\n");
            for (Decision d : decisions.subList(0, Math.min(10, decisions.size()))) {
                sb.append("- ").append(d.title());
                if (!d.rejectedOptions().isEmpty()) sb.append(" (ruled out: ").append(String.join(", ", d.rejectedOptions())).append(')');
                sb.append('\n');
            }
        }
        List<Object> fixes = Json.arr(r.get("fixes"));
        if (!fixes.isEmpty()) {
            sb.append("\nFixes found before:\n");
            for (Object f : fixes.subList(0, Math.min(4, fixes.size()))) sb.append("- ").append(Json.str(Json.obj(f), "title")).append('\n');
        }
        Object drift = r.get("driftAlerts");
        if (drift instanceof Number n && n.intValue() > 0) {
            sb.append("\n").append(n.intValue()).append(" drift alert(s) are open in Notch: recent work goes against a decision.\n");
        }
        String text = sb.toString().strip();
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "\u2026";
    }
}
