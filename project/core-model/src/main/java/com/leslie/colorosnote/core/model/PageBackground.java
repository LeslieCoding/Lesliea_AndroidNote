package com.leslie.colorosnote.core.model;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

/**
 * Page background layer (requirement 8): blank/template, a page of an imported
 * PDF/Office document, an image/scan, or a custom template.
 */
public final class PageBackground {

    public enum Type {
        BLANK, LINED, GRID, DOT, CORNELL,
        /** Cornell layout with a grid main area. */
        CORNELL_GRID,
        /** English four-line three-space handwriting guide. */
        ENGLISH,
        /** Chinese character square with dashed cross center lines. */
        TIANZI,
        /** Tianzi square plus dashed diagonals (米字格). */
        MIZI,
        /** Music staff, five lines per group. */
        MUSIC,
        /** Checkbox + line per row todo list. */
        TODO,
        /** Hourly timeline day planner. */
        DAILY,
        /** Seven-column weekly planner. */
        WEEKLY,
        /** Meeting minutes: header info, ruled body, summary. */
        MEETING,
        PDF, OFFICE, IMAGE, CUSTOM
    }

    @SerializedName("type")
    public Type type = Type.BLANK;
    /** 0-based page index into the source document when type is PDF/OFFICE. */
    @SerializedName("pdfPageIndex")
    public Integer pdfPageIndex;
    /** Resource id inside the container when type is IMAGE/CUSTOM. */
    @SerializedName("resourceId")
    public String resourceId;
    /** Template parameters (spacing, margin, colors, ...) as string key/values. */
    @SerializedName("templateParams")
    public Map<String, String> templateParams = new HashMap<>();

    public PageBackground() {
    }

    public static PageBackground blank() {
        return new PageBackground();
    }

    public static PageBackground ofType(Type type) {
        PageBackground bg = new PageBackground();
        bg.type = type;
        return bg;
    }
}
