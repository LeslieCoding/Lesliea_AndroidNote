package com.leslie.colorosnote;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.leslie.colorosnote.core.common.AppExecutors;
import com.leslie.colorosnote.core.model.NoteDocument;
import com.leslie.colorosnote.core.storage.db.InkNoteDatabase;
import com.leslie.colorosnote.core.storage.repository.DocumentRepository;
import com.leslie.colorosnote.feature.editor.EditorActivity;
import com.leslie.colorosnote.feature.export.ExportOptions;
import com.leslie.colorosnote.feature.export.ExportRouter;
import com.leslie.colorosnote.feature.importer.ImportJob;
import com.leslie.colorosnote.feature.importer.ImportJobManager;
import com.leslie.colorosnote.feature.library.LibraryFragment;
import com.leslie.colorosnote.feature.library.LibraryHost;
import com.leslie.colorosnote.feature.library.NewItemBottomSheet;
import com.leslie.colorosnote.feature.search.SearchFragment;
import com.leslie.colorosnote.feature.search.SearchHost;

import java.io.File;
import java.util.List;

/**
 * Launcher activity: hosts the document library (feature-library) and wires
 * the host callbacks to editor / search / import / export.
 */
public class MainActivity extends AppCompatActivity implements LibraryHost, SearchHost {

    private final ActivityResultLauncher<String[]> importPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImportPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_container, LibraryFragment.newInstance())
                    .commit();
        }
    }

    // ------------------------------------------------------------------
    // LibraryHost
    // ------------------------------------------------------------------

    @Override
    public DocumentRepository documentRepository() {
        return AppServices.repository(this);
    }

    @Override
    public InkNoteDatabase database() {
        return AppServices.database(this);
    }

    @Override
    public void openDocument(String documentId) {
        startActivity(EditorActivity.createIntent(this, documentId));
    }

    @Override
    public void startImport(String importType) {
        importPicker.launch(mimeTypesFor(importType));
    }

    @Override
    public void openSearch() {
        SearchFragment fragment = SearchFragment.newInstance();
        fragment.setDatabase(AppServices.database(this));
        fragment.setSearchHost(this);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_container, fragment)
                .addToBackStack("search")
                .commit();
    }

    @Override
    public void exportDocuments(List<String> documentIds) {
        String[] labels = {"PDF", "PNG 图片", "TXT 文本", "Markdown", ".inknote 原件"};
        ExportOptions.Format[] formats = {
                ExportOptions.Format.PDF, ExportOptions.Format.PNG,
                ExportOptions.Format.TXT, ExportOptions.Format.MARKDOWN,
                ExportOptions.Format.INKNOTE};
        new AlertDialog.Builder(this)
                .setTitle("导出格式")
                .setItems(labels, (d, which) -> exportAll(documentIds, formats[which]))
                .show();
    }

    // ------------------------------------------------------------------
    // SearchHost
    // ------------------------------------------------------------------

    @Override
    public void openResult(String documentId, String pageId, float[] boundingBox) {
        // Opens the document and jumps to the hit page with a highlight flash
        // (spec 22.3).
        startActivity(EditorActivity.createIntent(this, documentId, pageId, boundingBox));
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    private void onImportPicked(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers don't grant persistable permissions; the import
            // copies bytes into the sandbox immediately, so this is best-effort.
        }
        ImportJobManager manager = AppImports.manager(this);
        manager.setListener(new ImportJobManager.Listener() {
            @Override
            public void onJobProgress(String jobId, int percent, com.leslie.colorosnote.feature.importer.ImportStage stage) {
                // Progress is surfaced in the library list; no toast spam.
            }

            @Override
            public void onJobFinished(String jobId, ImportJob.Status status, String errorMessage) {
                runOnUiThread(() -> {
                    if (status == ImportJob.Status.SUCCEEDED) {
                        Toast.makeText(MainActivity.this, "导入完成", Toast.LENGTH_SHORT).show();
                    } else if (status == ImportJob.Status.FAILED) {
                        Toast.makeText(MainActivity.this,
                                "导入失败：" + errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onQueueDrained() {
                // Nothing to do; the library list refreshes itself on resume.
            }
        });
        manager.enqueue(uri.toString());
        Toast.makeText(this, "已加入导入队列", Toast.LENGTH_SHORT).show();
    }

    private static String[] mimeTypesFor(String importType) {
        switch (importType) {
            case NewItemBottomSheet.IMPORT_PDF:
                return new String[]{"application/pdf"};
            case NewItemBottomSheet.IMPORT_WORD:
                return new String[]{"application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"};
            case NewItemBottomSheet.IMPORT_PPT:
                return new String[]{"application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"};
            case NewItemBottomSheet.IMPORT_EXCEL:
                return new String[]{"application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"};
            case NewItemBottomSheet.IMPORT_IMAGE:
            case NewItemBottomSheet.IMPORT_SCAN:
                return new String[]{"image/*"};
            case NewItemBottomSheet.IMPORT_CAJ:
            case NewItemBottomSheet.IMPORT_FILE:
            default:
                return new String[]{"*/*"};
        }
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private void exportAll(List<String> documentIds, ExportOptions.Format format) {
        File outDir = getExternalFilesDir("exports");
        Toast.makeText(this, "开始导出…", Toast.LENGTH_SHORT).show();
        AppExecutors.get().io().execute(() -> {
            DocumentRepository repo = AppServices.repository(this);
            int done = 0;
            String firstError = null;
            for (String id : documentIds) {
                try {
                    NoteDocument doc = repo.openDocument(id);
                    ExportOptions options = new ExportOptions();
                    options.format = format;
                    ExportRouter.export(doc, repo.fileFor(id), options, outDir, null);
                    done++;
                } catch (Exception e) {
                    firstError = e.getMessage();
                }
            }
            int finalDone = done;
            String finalError = firstError;
            runOnUiThread(() -> {
                if (finalError == null) {
                    Toast.makeText(this, "已导出 " + finalDone + " 个文件到 " + outDir,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "导出完成 " + finalDone + "/" + documentIds.size()
                            + "，失败：" + finalError, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.content_container);
        if (current instanceof SearchFragment) {
            getSupportFragmentManager().popBackStack();
            return;
        }
        super.onBackPressed();
    }
}
