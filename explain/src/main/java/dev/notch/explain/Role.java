package dev.notch.explain;

/** What a file is for. Several roles are "roots": code the framework runs without an import. */
public enum Role {
    ENTRY("Entry point", true),
    PAGE("Page", true),
    LAYOUT("Layout", true),
    API_ROUTE("API route", true),
    CONTROLLER("API controller", true),
    SERVICE("Framework-managed class", true),
    COMPONENT("Component", false),
    CODE("Code", false),
    TEST("Tests", true),
    SCRIPT("Script", true),
    CONFIG("Configuration", true),
    MIGRATION("Database migration", true),
    STYLE("Styles", true),
    DOC("Documentation", true),
    DATA("Data", true);

    public final String label;
    /** Reached by the framework or a person directly, so never "unused" for lack of importers. */
    public final boolean root;

    Role(String label, boolean root) {
        this.label = label;
        this.root = root;
    }
}
