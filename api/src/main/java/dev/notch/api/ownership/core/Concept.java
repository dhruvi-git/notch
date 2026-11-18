package dev.notch.api.ownership.core;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Engineering ideas a piece of code can show, each with how to spot it, a question that makes the
 * author explain their reasoning about it, and what a good answer has to touch.
 *
 * <p>Questions are about decisions and consequences ("what breaks if…"), never about syntax, so
 * an answer needs real understanding of this code, not a definition from a search. Each rubric
 * entry is a group of interchangeable terms; an answer covers the entry by using any of them.
 * Ordered by how much a question about it reveals: earlier concepts win when a region shows several.
 */
public enum Concept {

    RECURSION("recursion", null,
            "`{name}` calls itself. What stops the recursion, and what happens on the input that makes it go deepest?",
            List.of(List.of("base case", "stops", "terminat", "return early", "until"),
                    List.of("stack", "depth", "overflow", "deep", "too many calls"))),

    SQL_JOIN("joins", Pattern.compile("(?i)\\bjoin\\b"),
            "Lines {lines} join tables. Why that kind of join, and which rows disappear or duplicate if you change it?",
            List.of(List.of("inner", "left", "outer", "right"),
                    List.of("missing", "null", "no match", "unmatched", "drop", "lose"),
                    List.of("duplicate", "multiple", "one-to-many", "many", "fan out"))),

    AGGREGATION("aggregation", Pattern.compile("(?i)group\\s+by|\\.groupby\\(|\\.agg\\(|\\bcount\\(|\\bsum\\(|\\bavg\\("),
            "Lines {lines} aggregate data. What exactly is one output row, and what happens to rows with missing values in the grouping?",
            List.of(List.of("per", "each", "one row", "group"),
                    List.of("null", "nan", "missing", "none", "empty"),
                    List.of("count", "sum", "average", "mean", "total"))),

    MODEL_TRAINING("model training", Pattern.compile("train_test_split|\\.fit\\(|\\.predict\\(|cross_val|model\\.train|optimizer"),
            "In `{name}`, how do you know the model isn't just memorising the training data? Point to what in this code guards against it.",
            List.of(List.of("test", "validation", "holdout", "split", "unseen"),
                    List.of("overfit", "memoris", "memoriz", "leak", "generaliz", "generalis"),
                    List.of("metric", "accuracy", "score", "f1", "precision", "recall", "rmse", "auc"))),

    NULL_HANDLING("missing values", Pattern.compile("(?i)dropna|fillna|isnull|is null|is none|== null|!= null|\\?\\?|optional|nullable"),
            "Lines {lines} deal with missing values. Why handle them this way here, and what would the result look like if you didn't?",
            List.of(List.of("null", "nan", "none", "missing", "empty"),
                    List.of("drop", "fill", "default", "skip", "ignore", "replace", "impute"),
                    List.of("bias", "wrong", "crash", "error", "skew", "fewer rows", "lose"))),

    CONCURRENCY("concurrency", Pattern.compile("synchronized|\\bThread\\b|Executor|\\block\\b|Mutex|threading|asyncio\\.gather|Promise\\.all"),
            "`{name}` runs work concurrently. What could go wrong if two of these run at the same time, and what in your code prevents it?",
            List.of(List.of("race", "same time", "simultaneous", "parallel", "concurrent"),
                    List.of("lock", "synchron", "atomic", "mutex", "order", "await"),
                    List.of("shared", "state", "data", "inconsistent", "corrupt"))),

    ASYNC("asynchronous code", Pattern.compile("\\basync\\b|\\bawait\\b|\\.then\\(|Promise<|CompletableFuture"),
            "`{name}` is asynchronous. What does the caller see while it's still running, and what happens if it fails halfway?",
            List.of(List.of("wait", "pending", "loading", "promise", "future", "before"),
                    List.of("fail", "error", "reject", "throw", "catch", "exception"),
                    List.of("retry", "handle", "show", "message", "fallback", "rollback"))),

    STATE_EFFECTS("state and effects", Pattern.compile("useEffect|useState|useMemo|setState|@State|ref\\("),
            "In `{name}`, when does this component re-render, and what would happen if the effect's dependencies were wrong?",
            List.of(List.of("render", "re-render", "rerender", "update"),
                    List.of("depend", "dependency", "array", "changes"),
                    List.of("loop", "infinite", "stale", "every render", "too often", "never runs"))),

    HTTP_ENDPOINT("an API endpoint", Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping|app\\.(get|post|put|delete)\\(|router\\.(get|post)|@app\\.route|@router\\.|fetch\\("),
            "Lines {lines} handle or call an HTTP endpoint. What does it return when the input is invalid, and how would a client know what went wrong?",
            List.of(List.of("400", "404", "500", "status", "error code"),
                    List.of("invalid", "validat", "bad input", "missing field", "check"),
                    List.of("message", "json", "response", "body", "error"))),

    ERROR_HANDLING("error handling", Pattern.compile("\\btry\\b|\\bexcept\\b|\\bcatch\\b|\\bthrow\\b|\\braise\\b"),
            "Lines {lines} handle an error. Which failure did you expect here, and what does the user or caller end up seeing?",
            List.of(List.of("fail", "error", "exception", "crash", "timeout"),
                    List.of("user", "caller", "message", "log", "see", "return"),
                    List.of("retry", "default", "fallback", "skip", "stop", "re-raise", "rethrow"))),

    SORTING("ordering", Pattern.compile("(?i)\\bsort|sorted\\(|order\\s+by|comparator|compareTo"),
            "Lines {lines} put things in order. What decides the order when two items tie, and how does the cost grow as the input gets bigger?",
            List.of(List.of("tie", "equal", "same", "stable"),
                    List.of("n log n", "nlogn", "o(n", "linear", "quadratic", "grow", "slow"))),

    LOOKUP("hash lookups", Pattern.compile("HashMap|HashSet|\\bdict\\(|\\bset\\(|new Map\\(|new Set\\(|defaultdict|Counter\\("),
            "`{name}` uses a map or set. Why that instead of a list here, and what would the running time be without it?",
            List.of(List.of("constant", "o(1)", "fast", "lookup", "hash"),
                    List.of("list", "array", "scan", "o(n", "linear", "slower", "quadratic"),
                    List.of("duplicate", "unique", "key", "collision"))),

    VALIDATION("input validation", Pattern.compile("(?i)validat|\\bassert\\b|isBlank|is_valid|regex|Pattern\\.compile|re\\.match"),
            "Lines {lines} check input. Which bad input is this guarding against, and what slips through that it doesn't catch?",
            List.of(List.of("empty", "blank", "negative", "invalid", "malformed", "wrong type", "too long"),
                    List.of("slip", "miss", "not catch", "doesn't catch", "edge case", "still")));

    /** Shown in the question when no concept stands out. */
    public static final String GENERAL_PROMPT =
            "Walk me through `{name}` with a concrete input: what goes in, what comes out, and one input that would break it.";
    public static final List<List<String>> GENERAL_RUBRIC = List.of(
            List.of("input", "takes", "receives", "given", "pass"),
            List.of("return", "output", "result", "produces", "gives"),
            List.of("break", "fail", "edge", "empty", "null", "crash", "error", "wrong"));

    private final String label;
    private final Pattern detector;
    private final String prompt;
    private final List<List<String>> rubric;

    Concept(String label, Pattern detector, String prompt, List<List<String>> rubric) {
        this.label = label;
        this.detector = detector;
        this.prompt = prompt;
        this.rubric = rubric;
    }

    public String label() {
        return label;
    }

    /** Whether the code shows this concept. Recursion is detected by {@link RegionExtractor}, which knows the region's name. */
    public boolean foundIn(String code) {
        return detector != null && detector.matcher(code).find();
    }

    public String prompt() {
        return prompt;
    }

    public List<List<String>> rubric() {
        return rubric;
    }
}
