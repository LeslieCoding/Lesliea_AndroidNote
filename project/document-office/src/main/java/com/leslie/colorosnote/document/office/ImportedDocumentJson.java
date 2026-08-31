package com.leslie.colorosnote.document.office;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON codec for {@link ImportedDocument}: persists the fixed-layout pages
 * (size, name, text layer and every {@link DrawCommand}) so an imported
 * Office document can be re-rendered after the import sandbox is gone.
 *
 * <p>Bitmap payloads are NOT part of the JSON — they live in the .inknote
 * container's {@code resources/} extras under their resource id
 * ({@link DrawCommand.Image#resourceId}). The JSON only carries the id list;
 * {@link #fromBytes(byte[], File)} rebuilds the id→file map against a caller
 * supplied directory where the caller materializes those extras, keeping the
 * resourceId convention {@code OfficePagePainter} relies on.
 *
 * <p>Pure JVM (gson only). Lenient on read: unknown command kinds and unknown
 * fields are skipped, never fatal.
 */
public final class ImportedDocumentJson {

    /** Schema version of the JSON payload, bumped on incompatible changes. */
    public static final int VERSION = 1;

    private static final String KIND_TEXT = "text";
    private static final String KIND_IMAGE = "image";
    private static final String KIND_RECT = "rect";
    private static final String KIND_LINE = "line";

    private ImportedDocumentJson() {
    }

    /** Serializes the document (structure only, no bitmap bytes). */
    public static String toJson(ImportedDocument document) {
        DocDto dto = new DocDto();
        dto.version = VERSION;
        dto.sourceFileName = document.getSourceFileName();
        dto.type = document.getType() == null ? null : document.getType().name();
        dto.engineId = document.getEngineId();
        dto.warnings = new ArrayList<>(document.getWarnings());
        dto.resourceIds = new ArrayList<>(document.getResourceFiles().keySet());
        dto.pages = new ArrayList<>();
        for (ImportedPage page : document.getPages()) {
            PageDto p = new PageDto();
            p.pageNumber = page.getPageNumber();
            p.name = page.getName();
            p.widthPx = page.getWidthPx();
            p.heightPx = page.getHeightPx();
            p.textLayer = page.getTextLayer();
            p.commands = new ArrayList<>();
            for (DrawCommand cmd : page.getCommands()) {
                CmdDto c = toDto(cmd);
                if (c != null) {
                    p.commands.add(c);
                }
            }
            dto.pages.add(p);
        }
        return gson().toJson(dto);
    }

    /** UTF-8 bytes of {@link #toJson(ImportedDocument)}. */
    public static byte[] toBytes(ImportedDocument document) {
        return toJson(document).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Rebuilds a document from {@link #toJson(ImportedDocument)} output.
     * Resource ids map to files inside {@code resourceDir} (the caller
     * materializes the container's bitmap extras there); the directory need
     * not exist yet.
     *
     * @throws JsonSyntaxException  on malformed JSON
     * @throws IllegalStateException on a structurally invalid payload
     */
    public static ImportedDocument fromJson(String json, File resourceDir) {
        DocDto dto = gson().fromJson(json, DocDto.class);
        if (dto == null || dto.pages == null) {
            throw new IllegalStateException("Not an ImportedDocument payload");
        }
        List<ImportedPage> pages = new ArrayList<>();
        for (PageDto p : dto.pages) {
            List<DrawCommand> commands = new ArrayList<>();
            if (p.commands != null) {
                for (CmdDto c : p.commands) {
                    DrawCommand cmd = fromDto(c);
                    if (cmd != null) {
                        commands.add(cmd);
                    }
                }
            }
            pages.add(new ImportedPage(p.pageNumber, p.name, p.widthPx, p.heightPx,
                    commands, p.textLayer));
        }
        Map<String, File> resourceFiles = new LinkedHashMap<>();
        if (dto.resourceIds != null) {
            for (String id : dto.resourceIds) {
                if (id != null && !id.isEmpty()) {
                    resourceFiles.put(id, new File(resourceDir, id));
                }
            }
        }
        OfficeType type = null;
        if (dto.type != null) {
            try {
                type = OfficeType.valueOf(dto.type);
            } catch (IllegalArgumentException ignored) {
                // Unknown type from a newer schema: keep null, pages still render.
            }
        }
        return new ImportedDocument(dto.sourceFileName, type, dto.engineId, pages,
                dto.warnings == null ? new ArrayList<>() : dto.warnings,
                resourceDir, resourceFiles);
    }

    /** UTF-8 decoding variant of {@link #fromJson(String, File)}. */
    public static ImportedDocument fromBytes(byte[] data, File resourceDir) {
        return fromJson(new String(data, StandardCharsets.UTF_8), resourceDir);
    }

    // ------------------------------------------------------------------
    // DrawCommand <-> DTO
    // ------------------------------------------------------------------

    private static CmdDto toDto(DrawCommand cmd) {
        CmdDto c;
        if (cmd instanceof DrawCommand.Text) {
            DrawCommand.Text t = (DrawCommand.Text) cmd;
            c = new CmdDto(KIND_TEXT);
            c.x = t.x;
            c.y = t.y;
            c.text = t.text;
            c.textSizePx = t.textSizePx;
            c.colorArgb = t.colorArgb;
            c.bold = t.bold;
            c.italic = t.italic;
            c.align = t.align == null ? null : t.align.name();
        } else if (cmd instanceof DrawCommand.Image) {
            DrawCommand.Image i = (DrawCommand.Image) cmd;
            c = new CmdDto(KIND_IMAGE);
            c.x = i.x;
            c.y = i.y;
            c.width = i.width;
            c.height = i.height;
            c.resourceId = i.resourceId;
        } else if (cmd instanceof DrawCommand.Rect) {
            DrawCommand.Rect r = (DrawCommand.Rect) cmd;
            c = new CmdDto(KIND_RECT);
            c.x = r.x;
            c.y = r.y;
            c.width = r.width;
            c.height = r.height;
            c.colorArgb = r.colorArgb;
            c.fill = r.fill;
            c.strokeWidthPx = r.strokeWidthPx;
        } else if (cmd instanceof DrawCommand.Line) {
            DrawCommand.Line l = (DrawCommand.Line) cmd;
            c = new CmdDto(KIND_LINE);
            c.x1 = l.x1;
            c.y1 = l.y1;
            c.x2 = l.x2;
            c.y2 = l.y2;
            c.colorArgb = l.colorArgb;
            c.strokeWidthPx = l.strokeWidthPx;
        } else {
            return null; // unknown subclass: skip instead of failing the document
        }
        return c;
    }

    private static DrawCommand fromDto(CmdDto c) {
        if (c == null || c.kind == null) {
            return null;
        }
        switch (c.kind) {
            case KIND_TEXT: {
                TextAlign align = TextAlign.LEFT;
                if (c.align != null) {
                    try {
                        align = TextAlign.valueOf(c.align);
                    } catch (IllegalArgumentException ignored) {
                        // Unknown alignment from a newer schema: fall back to LEFT.
                    }
                }
                return new DrawCommand.Text(c.x, c.y, c.text == null ? "" : c.text,
                        c.textSizePx, c.colorArgb, c.bold, c.italic, align);
            }
            case KIND_IMAGE:
                if (c.resourceId == null) {
                    return null;
                }
                return new DrawCommand.Image(c.x, c.y, c.width, c.height, c.resourceId);
            case KIND_RECT:
                return new DrawCommand.Rect(c.x, c.y, c.width, c.height,
                        c.colorArgb, c.fill, c.strokeWidthPx);
            case KIND_LINE:
                return new DrawCommand.Line(c.x1, c.y1, c.x2, c.y2,
                        c.colorArgb, c.strokeWidthPx);
            default:
                return null; // forward compatibility
        }
    }

    private static Gson gson() {
        return Holder.GSON;
    }

    /** Lazy holder so the Gson instance is shared but only built on first use. */
    private static final class Holder {
        static final Gson GSON = new GsonBuilder().create();
    }

    // ------------------------------------------------------------------
    // DTO tree (Gson field mapping; one flat shape for all command kinds)
    // ------------------------------------------------------------------

    private static final class DocDto {
        int version;
        String sourceFileName;
        String type;
        String engineId;
        List<String> warnings;
        List<String> resourceIds;
        List<PageDto> pages;
    }

    private static final class PageDto {
        int pageNumber;
        String name;
        int widthPx;
        int heightPx;
        String textLayer;
        List<CmdDto> commands;
    }

    private static final class CmdDto {
        final String kind;
        float x;
        float y;
        float width;
        float height;
        float x1;
        float y1;
        float x2;
        float y2;
        float textSizePx;
        float strokeWidthPx;
        int colorArgb;
        boolean bold;
        boolean italic;
        boolean fill;
        String text;
        String resourceId;
        String align;

        CmdDto(String kind) {
            this.kind = kind;
        }
    }
}
