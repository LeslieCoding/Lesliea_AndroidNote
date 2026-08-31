package com.leslie.colorosnote.feature.handwriting;

/** One recognition candidate. Immutable. */
public final class Candidate {

    public final String text;
    /** Engine score, nullable when the engine does not report one. */
    public final Float score;

    public Candidate(String text, Float score) {
        this.text = text;
        this.score = score;
    }
}
