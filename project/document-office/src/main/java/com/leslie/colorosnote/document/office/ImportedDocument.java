package com.leslie.colorosnote.document.office;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The coordinator-facing result of an Office import: fixed pages plus the image
 * resources already persisted inside the caller's sandbox directory.
 */
public final class ImportedDocument {

    private final String sourceFileName;
    private final OfficeType type;
    private final String engineId;
    private final List<ImportedPage> pages;
    private final List<String> warnings;
    /** Directory inside the sandbox that holds the extracted image files. */
    private final File resourceDir;
    /** resource id (as referenced by {@link DrawCommand.Image#resourceId}) -> file. */
    private final Map<String, File> resourceFiles;

    public ImportedDocument(String sourceFileName, OfficeType type, String engineId,
                            List<ImportedPage> pages, List<String> warnings,
                            File resourceDir, Map<String, File> resourceFiles) {
        this.sourceFileName = sourceFileName;
        this.type = type;
        this.engineId = engineId;
        this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.resourceDir = resourceDir;
        this.resourceFiles = Collections.unmodifiableMap(new LinkedHashMap<>(resourceFiles));
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public OfficeType getType() {
        return type;
    }

    public String getEngineId() {
        return engineId;
    }

    public List<ImportedPage> getPages() {
        return pages;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public File getResourceDir() {
        return resourceDir;
    }

    /** Resolves the on-disk file for a resource id, or null when unknown. */
    public File resourceFile(String resourceId) {
        return resourceFiles.get(resourceId);
    }

    public Map<String, File> getResourceFiles() {
        return resourceFiles;
    }
}
