package com.leslie.colorosnote.core.storage.db.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Speech-to-text transcript of an audio record (requirement 21/34). */
@Entity(
        tableName = "transcripts",
        foreignKeys = @ForeignKey(
                entity = AudioRecordEntity.class,
                parentColumns = "id",
                childColumns = "audioRecordId",
                onDelete = ForeignKey.CASCADE),
        indices = @Index("audioRecordId"))
public final class TranscriptEntity {

    @PrimaryKey
    @ColumnInfo(name = "id")
    @NonNull
    public String id;

    @ColumnInfo(name = "audioRecordId")
    public String audioRecordId;

    @ColumnInfo(name = "text")
    public String text = "";

    /** BCP-47 language tag, e.g. "zh-CN". */
    @ColumnInfo(name = "language")
    public String language;

    /** Engine name, e.g. "local-whisper" / "online". */
    @ColumnInfo(name = "engine")
    public String engine;

    @ColumnInfo(name = "createdAt")
    public long createdAt;

    public TranscriptEntity(String id, String audioRecordId) {
        this.id = id;
        this.audioRecordId = audioRecordId;
    }
}
