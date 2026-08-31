package com.leslie.colorosnote.feature.search;

import com.leslie.colorosnote.core.search.NGramTokenizer;
import com.leslie.colorosnote.core.search.TextNormalizer;

import java.util.List;

/**
 * Turns raw user input into an SQLite FTS4 MATCH query string (spec 22.2).
 * Index content is tokenized with the same {@link NGramTokenizer} (see
 * {@link SearchIndexScheduler}), so query terms must be produced identically:
 * CJK runs become overlapping bigrams, latin/digit runs stay whole words.
 * Terms are space-joined (FTS4 implicit AND); latin terms carry a trailing
 * '*' for prefix matching. Pure JVM.
 */
public final class SearchQueryBuilder {

    private SearchQueryBuilder() {
    }

    /** True when the input has no searchable content. */
    public static boolean isBlank(String input) {
        return input == null || TextNormalizer.normalize(input).isEmpty()
                || NGramTokenizer.tokenize(TextNormalizer.normalize(input)).isEmpty();
    }

    /**
     * Normalized, deduped query terms in stable order (bigrams for CJK runs,
     * whole words for latin). Used to build the FTS query and later to score
     * coverage and highlight snippets. Empty list for blank input.
     */
    public static List<String> terms(String input) {
        return NGramTokenizer.tokenize(TextNormalizer.normalize(input));
    }

    /**
     * Builds the FTS4 MATCH query string, or "" when there is nothing to
     * search. Every term is double-quote wrapped (embedded quotes escaped by
     * doubling, though the tokenizer already strips them); latin terms get a
     * trailing '*' so a typed prefix hits the full indexed word.
     */
    public static String buildFtsQuery(String input) {
        List<String> terms = terms(input);
        if (terms.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String term : terms) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('"').append(escape(term)).append('"');
            if (isLatinTerm(term)) {
                sb.append('*');
            }
        }
        return sb.toString();
    }

    /** Latin/digit terms qualify for prefix matching; CJK bigrams do not. */
    private static boolean isLatinTerm(String term) {
        for (int i = 0; i < term.length(); i++) {
            if (TextNormalizer.isCjk(term.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** FTS4 escapes a double quote inside a quoted string by doubling it. */
    private static String escape(String term) {
        return term.replace("\"", "\"\"");
    }
}
