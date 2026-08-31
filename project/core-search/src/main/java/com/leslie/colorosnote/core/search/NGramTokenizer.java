package com.leslie.colorosnote.core.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tokenizer for mixed Chinese/latin content (spec 22.2).
 * <p>
 * CJK runs are emitted as overlapping bigrams (2-gram) so a continuous Chinese
 * query hits without word segmentation. Latin/digit runs are emitted as whole
 * words. Pure JVM.
 */
public final class NGramTokenizer {

    private NGramTokenizer() {
    }

    /** Tokenize already-normalized text into index terms (deduped, stable order). */
    public static List<String> tokenize(String normalized) {
        Set<String> out = new LinkedHashSet<>();
        if (normalized == null || normalized.isEmpty()) {
            return new ArrayList<>();
        }
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (TextNormalizer.isCjk(c)) {
                flushLatin(latin, out);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, out);
                latin.append(c);
            } else {
                flushLatin(latin, out);
                flushCjk(cjk, out);
            }
        }
        flushLatin(latin, out);
        flushCjk(cjk, out);
        return new ArrayList<>(out);
    }

    private static void flushLatin(StringBuilder latin, Set<String> out) {
        if (latin.length() > 0) {
            out.add(latin.toString());
            latin.setLength(0);
        }
    }

    private static void flushCjk(StringBuilder cjk, Set<String> out) {
        int n = cjk.length();
        if (n == 0) {
            return;
        }
        if (n == 1) {
            out.add(cjk.toString());
        } else {
            for (int i = 0; i + 2 <= n; i++) {
                out.add(cjk.substring(i, i + 2));
            }
            // Also index the whole run when short, so exact 2-4 char words rank well.
            if (n <= 4) {
                out.add(cjk.toString());
            }
        }
        cjk.setLength(0);
    }
}
