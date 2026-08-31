package com.leslie.colorosnote.core.storage.db.dao;

import androidx.room.ColumnInfo;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.leslie.colorosnote.core.storage.db.entity.AudioRecordEntity;
import com.leslie.colorosnote.core.storage.db.entity.HandwritingIndexEntity;
import com.leslie.colorosnote.core.storage.db.entity.OcrBlockEntity;
import com.leslie.colorosnote.core.storage.db.entity.ResourceEntity;
import com.leslie.colorosnote.core.storage.db.entity.SearchIndexEntity;
import com.leslie.colorosnote.core.storage.db.entity.TranscriptEntity;

import java.util.List;

/**
 * Incremental search-index maintenance (requirement 35): every writer replaces
 * exactly one document+page+source segment instead of rebuilding the index.
 */
@Dao
public abstract class IndexDao {

    /** Flat projection of a search hit. */
    public static final class SearchMatch {
        @ColumnInfo(name = "documentId")
        public String documentId;
        @ColumnInfo(name = "pageId")
        public String pageId;
        @ColumnInfo(name = "source")
        public String source;
        @ColumnInfo(name = "content")
        public String content;
    }

    // -- resource / audio metadata -----------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertResource(ResourceEntity resource);

    @Query("SELECT * FROM resources WHERE documentId = :documentId")
    public abstract List<ResourceEntity> listResources(String documentId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertAudioRecord(AudioRecordEntity record);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertTranscript(TranscriptEntity transcript);

    @Query("SELECT * FROM audio_records WHERE documentId = :documentId")
    public abstract List<AudioRecordEntity> listAudioRecords(String documentId);

    // -- OCR / handwriting segments -----------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertOcrBlocks(List<OcrBlockEntity> blocks);

    @Query("DELETE FROM ocr_blocks WHERE pageId = :pageId")
    public abstract void deleteOcrBlocksForPage(String pageId);

    /** Replaces all OCR blocks of one page (incremental re-index, section 35). */
    @Transaction
    public void replaceOcrBlocksForPage(String pageId, List<OcrBlockEntity> blocks) {
        deleteOcrBlocksForPage(pageId);
        if (blocks != null && !blocks.isEmpty()) {
            upsertOcrBlocks(blocks);
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertHandwritingIndex(List<HandwritingIndexEntity> entries);

    @Query("DELETE FROM handwriting_index WHERE pageId = :pageId")
    public abstract void deleteHandwritingIndexForPage(String pageId);

    @Transaction
    public void replaceHandwritingIndexForPage(String pageId, List<HandwritingIndexEntity> entries) {
        deleteHandwritingIndexForPage(pageId);
        if (entries != null && !entries.isEmpty()) {
            upsertHandwritingIndex(entries);
        }
    }

    // -- FTS search index -----------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insertSearchSegment(SearchIndexEntity segment);

    /** Removes one document+page+source segment before re-inserting it. */
    @Query("DELETE FROM search_index WHERE documentId = :documentId AND pageId = :pageId AND source = :source")
    public abstract void deleteSearchSegment(String documentId, String pageId, String source);

    /** Replaces one index segment (text box edit, OCR done, transcript done, ...). */
    @Transaction
    public void replaceSearchSegment(SearchIndexEntity segment) {
        deleteSearchSegment(segment.documentId, segment.pageId, segment.source);
        insertSearchSegment(segment);
    }

    /** Drops all segments of a document (before permanent delete or re-import). */
    @Query("DELETE FROM search_index WHERE documentId = :documentId")
    public abstract void deleteSearchSegmentsForDocument(String documentId);

    @Query("SELECT documentId, pageId, source, content FROM search_index "
            + "WHERE search_index MATCH :query ORDER BY documentId, pageId LIMIT :limit")
    public abstract List<SearchMatch> search(String query, int limit);
}
