package dev.notch.explain;

/** One file of the codebase being mapped. Paths use forward slashes, relative to the root. */
public record SourceFile(String path, String content) {
    public SourceFile {
        path = path.replace('\\', '/').replaceAll("^\\./", "");
    }
}
