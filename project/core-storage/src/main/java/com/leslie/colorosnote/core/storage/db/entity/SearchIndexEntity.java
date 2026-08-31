package com.leslie.colorosnote.core.storage.db.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Fts4;
import androidx.room.PrimaryKey;

/**
 * FTS4 full-text search index (requirement 22/34). One row per searchable
 * segment (text box / OCR block / handwriting run / transcript / imported
 * office text) so section 35 incremental re-indexing can replace a single
 * document+page+source segment without rebuilding the whole index.
 *
 * <p>Room 2.6 natively supports FTS via {@link Fts4}; the backing virtual
 * table is created and schema-exported by Room itself.
 */
@Fts4
@Entity(tableName = "search_index")
public final class SearchIndexEntity {

    /** Segment origin, e.g. "text_box", "ocr", "handwriting", "transcript", "office". */
    @ColumnInfo(name = "source")
    public String source = "";

    @ColumnInfo(name = "documentId")
    public String documentId;

    @ColumnInfo(name = "pageId")
    public String pageId;

    @ColumnInfo(name = "content")
    public String content = "";

    /** FTS tables use the implicit rowid as primary key. */
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    public long rowid;

    public SearchIndexEntity(long rowid) {
        this.rowid = rowid;
    }
}
