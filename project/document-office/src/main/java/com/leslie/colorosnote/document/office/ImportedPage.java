package com.leslie.colorosnote.document.office;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Imported page ready for the editor: size, draw commands, text layer. */
public final class ImportedPage {

    private final int pageNumber; // 1-based
    private final String name;
    private final int widthPx;
    private final int heightPx;
    private final List<DrawCommand> commands;
    private final String textLayer;

    public ImportedPage(int pageNumber, String name, int widthPx, int heightPx,
                        List<DrawCommand> commands, String textLayer) {
        this.pageNumber = pageNumber;
        this.name = name;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.commands = Collections.unmodifiableList(new ArrayList<>(commands));
        this.textLayer = textLayer == null ? "" : textLayer;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public String getName() {
        return name;
    }

    public int getWidthPx() {
        return widthPx;
    }

    public int getHeightPx() {
        return heightPx;
    }

    public List<DrawCommand> getCommands() {
        return commands;
    }

    public String getTextLayer() {
        return textLayer;
    }
}
