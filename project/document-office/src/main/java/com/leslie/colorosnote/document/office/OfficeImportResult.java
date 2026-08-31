package com.leslie.colorosnote.document.office;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Output of an engine import: fixed-layout pages, the image resource table and warnings. */
public final class OfficeImportResult {

    private final List<PageLayout> pages;
    private final List<ImageResource> images;
    private final List<String> warnings;

    public OfficeImportResult(List<PageLayout> pages, List<ImageResource> images, List<String> warnings) {
        this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
        this.images = Collections.unmodifiableList(new ArrayList<>(images));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public List<PageLayout> getPages() {
        return pages;
    }

    /** All embedded images; {@link DrawCommand.Image#resourceId} indexes this list by id. */
    public List<ImageResource> getImages() {
        return images;
    }

    /** Non-fatal issues, e.g. font substitution, dropped media, unsupported shapes. */
    public List<String> getWarnings() {
        return warnings;
    }
}
