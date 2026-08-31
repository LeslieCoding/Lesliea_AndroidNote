package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** An audio recording attached to a document (requirement 20/34). */
@Entity(
        tableName = "audio_records",
        foreignKeys = {
                @ForeignKey(
                        entity = DocumentEntity.class,
                        parentColumns = "id",
                        childColumns = "documentId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(
                        entity = PageEntity.class,
                        parentColumns = "id",
                        childColumns = "pageId",
                        onDelete = ForeignKey.SET_NULL)
        },
        indices = {@Index("documentId"), @Index("pageId")})
public final class AudioRecordEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    @ColumnInfo(name = "documentId")
    @NonNull
    public String documentId;

    /** Page the recording was started on; null when page was deleted. */
    @ColumnInfo(name = "pageId")
    public String pageId;

    /** Path inside the container, e.g. "audio/<id>". */
    @ColumnInfo(name = "containerPath")
    public String containerPath = "";

    @ColumnInfo(name = "durationMs")
    public long durationMs;

    @ColumnInfo(name = "sampleRateHz")
    public int sampleRateHz;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    public AudioRecordEntity(String id, String documentId) {
        this.id = id;
        this.documentId = documentId;
    }
}
