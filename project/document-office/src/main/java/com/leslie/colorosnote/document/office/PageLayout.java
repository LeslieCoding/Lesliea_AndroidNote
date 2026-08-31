package com.leslie.colorosnote.document.office;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One fixed-layout page: an Android-independent list of draw commands plus the
 * extracted text layer (used by search/select) of this page.
 */
public final class PageLayout {

    private final int widthPx;
    private final int heightPx;
    /** Human-readable origin, e.g. "Slide 3" or "Sheet2 (part 1)"; may be null. */
    private final String name;
    private final List<DrawCommand> commands;
    private final String textLayer;

    public PageLayout(int widthPx, int heightPx, String name,
                      List<DrawCommand> commands, String textLayer) {
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.name = name;
        this.commands = Collections.unmodifiableList(new ArrayList<>(commands));
        this.textLayer = textLayer == null ? "" : textLayer;
    }

    public int getWidthPx() {
        return widthPx;
    }

    public int getHeightPx() {
        return heightPx;
    }

    public String getName() {
        return name;
    }

    public List<DrawCommand> getCommands() {
        return commands;
    }

    /** All visible text of this page in reading order, lines joined by '\n'. */
    public String getTextLayer() {
        return textLayer;
    }
}
