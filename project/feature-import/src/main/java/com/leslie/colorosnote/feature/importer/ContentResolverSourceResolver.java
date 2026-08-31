package com.leslie.colorosnote.feature.importer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.io.InputStream;

/** {@link ImportSourceResolver} backed by the platform {@link ContentResolver}. */
public final class ContentResolverSourceResolver implements ImportSourceResolver {

    private final ContentResolver resolver;

    public ContentResolverSourceResolver(@NonNull ContentResolver resolver) {
        this.resolver = resolver;
    }

    @WorkerThread
    @Override
    @Nullable
    public String displayName(@NonNull String sourceRef) {
        String[] value = queryMetadata(sourceRef);
        return value == null ? null : value[0];
    }

    @WorkerThread
    @Override
    public long sizeBytes(@NonNull String sourceRef) {
        String[] value = queryMetadata(sourceRef);
        if (value == null || value[1] == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value[1]);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    @WorkerThread
    @Override
    @NonNull
    public InputStream open(@NonNull String sourceRef) throws IOException {
        InputStream in = resolver.openInputStream(Uri.parse(sourceRef));
        if (in == null) {
            throw new IOException("ContentResolver returned null stream for " + sourceRef);
        }
        return in;
    }

    /** One metadata query serving both displayName and sizeBytes. */
    @Nullable
    private String[] queryMetadata(String sourceRef) {
        Uri uri = Uri.parse(sourceRef);
        try (Cursor c = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            String name = c.isNull(0) ? null : c.getString(0);
            String size = c.isNull(1) ? null : c.getString(1);
            return new String[]{name, size};
        } catch (RuntimeException e) {
            // Some providers throw on projection columns they do not know.
            return null;
        }
    }
}
