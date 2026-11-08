package dev.notch.engine.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exact substring search across many documents, backed by one {@link SuffixArray}.
 *
 * <p>Documents are joined with a separator character that cannot appear in a query, so a
 * match can never span two documents. Search is case-insensitive: text and queries are
 * lower-cased character by character, which keeps every offset aligned with the original.
 *
 * <p>The index is immutable. Callers rebuild it after a batch of imports.
 */
public final class CorpusIndex {

    /** Joins documents. Lower than any printable character, never allowed in a query. */
    static final char SEPARATOR = '\u0001';

    private final String[] docIds;
    private final int[] starts;      // starts[i] = offset of document i in the joined text
    private final int[] lengths;
    private final SuffixArray sa;

    private CorpusIndex(String[] docIds, int[] starts, int[] lengths, SuffixArray sa) {
        this.docIds = docIds;
        this.starts = starts;
        this.lengths = lengths;
        this.sa = sa;
    }

    /** A document to index: a unique id and its text. */
    public record Doc(String id, String text) {
        public Doc {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
        }
    }

    /** All matches inside one document, offsets in ascending order. */
    public record DocMatches(String docId, int count, int[] offsets) {
    }

    public static CorpusIndex build(List<Doc> docs) {
        Objects.requireNonNull(docs, "docs");
        Set<String> seen = new HashSet<>();
        StringBuilder joined = new StringBuilder();
        String[] ids = new String[docs.size()];
        int[] starts = new int[docs.size()];
        int[] lengths = new int[docs.size()];
        for (int i = 0; i < docs.size(); i++) {
            Doc d = docs.get(i);
            if (!seen.add(d.id())) {
                throw new IllegalArgumentException("duplicate document id: " + d.id());
            }
            ids[i] = d.id();
            starts[i] = joined.length();
            lengths[i] = d.text().length();
            appendNormalized(joined, d.text());
            joined.append(SEPARATOR);
        }
        return new CorpusIndex(ids, starts, lengths, SuffixArray.build(joined.toString()));
    }

    private static void appendNormalized(StringBuilder out, CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\0' || ch == SEPARATOR) ch = ' ';
            out.append(Character.toLowerCase(ch));
        }
    }

    /** Normalizes a query exactly as document text is normalized. */
    static String normalizeQuery(CharSequence query) {
        StringBuilder sb = new StringBuilder(query.length());
        appendNormalized(sb, query);
        return sb.toString();
    }

    /**
     * Finds every document containing {@code query}.
     *
     * @param maxOffsetsPerDoc cap on offsets returned per document (counts stay exact)
     * @return matches grouped by document, in document order
     */
    public List<DocMatches> search(String query, int maxOffsetsPerDoc) {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (maxOffsetsPerDoc < 0) {
            throw new IllegalArgumentException("maxOffsetsPerDoc must be >= 0");
        }
        if (docIds.length == 0) {
            return List.of();
        }
        int[] positions = sa.occurrences(normalizeQuery(query));
        Arrays.sort(positions);  // text order, so offsets come out ascending per document

        Map<Integer, List<Integer>> byDoc = new LinkedHashMap<>();
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int pos : positions) {
            int doc = docAt(pos);
            counts.merge(doc, 1, Integer::sum);
            List<Integer> offs = byDoc.computeIfAbsent(doc, k -> new ArrayList<>());
            if (offs.size() < maxOffsetsPerDoc) offs.add(pos - starts[doc]);
        }
        List<DocMatches> out = new ArrayList<>(counts.size());
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            int doc = e.getKey();
            int[] offs = byDoc.get(doc).stream().mapToInt(Integer::intValue).toArray();
            out.add(new DocMatches(docIds[doc], e.getValue(), offs));
        }
        return out;
    }

    /** Total number of occurrences across all documents. */
    public int count(String query) {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        return docIds.length == 0 ? 0 : sa.count(normalizeQuery(query));
    }

    /** Index of the document containing joined-text position {@code pos}. */
    private int docAt(int pos) {
        int idx = Arrays.binarySearch(starts, pos);
        return idx >= 0 ? idx : -idx - 2;
    }

    public int documentCount() {
        return docIds.length;
    }

    /** Characters indexed, including one separator per document. */
    public int indexedLength() {
        return sa.length();
    }

    int documentLength(int i) {
        return lengths[i];
    }
}
