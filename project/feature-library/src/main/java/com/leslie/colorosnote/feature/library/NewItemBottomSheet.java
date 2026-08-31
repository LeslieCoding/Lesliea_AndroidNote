package com.leslie.colorosnote.feature.library;

import android.app.Dialog;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.leslie.colorosnote.feature.library.databinding.BottomsheetNewItemBinding;

/**
 * "+" grouped bottom sheet (spec 24): 4 create actions handled locally via
 * callback, 8 import actions forwarded to the host (feature-import wiring).
 */
public final class NewItemBottomSheet extends BottomSheetDialogFragment {

    // Import type constants passed to LibraryHost.startImport().
    public static final String IMPORT_PDF = "pdf";
    public static final String IMPORT_WORD = "word";
    public static final String IMPORT_PPT = "ppt";
    public static final String IMPORT_EXCEL = "excel";
    public static final String IMPORT_IMAGE = "image";
    public static final String IMPORT_CAJ = "caj";
    public static final String IMPORT_SCAN = "scan";
    public static final String IMPORT_FILE = "file";

    public interface Callback {
        void onCreateBlankNote();

        void onCreateTemplateNote();

        void onCreateFolder();

        void onCreateInfiniteCanvas();

        void onImport(String importType);
    }

    private Callback callback;

    public static NewItemBottomSheet newInstance() {
        return new NewItemBottomSheet();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        BottomsheetNewItemBinding binding =
                BottomsheetNewItemBinding.inflate(inflater, container, false);

        binding.rowNewBlank.setOnClickListener(v -> fire(c -> c.onCreateBlankNote()));
        binding.rowNewTemplate.setOnClickListener(v -> fire(c -> c.onCreateTemplateNote()));
        binding.rowNewFolder.setOnClickListener(v -> fire(c -> c.onCreateFolder()));
        binding.rowNewInfinite.setOnClickListener(v -> fire(c -> c.onCreateInfiniteCanvas()));

        binding.rowImportPdf.setOnClickListener(v -> fire(c -> c.onImport(IMPORT_PDF)));
        binding.rowImportWord.setOnClickListener(v -> fire(c -> c.onImport(IMPORT_WORD)));
        binding.rowImportPpt.setOnClickListener(v -> fire(c -> c.onImport(IMPORT_PPT)));
        binding.rowImportExcel.setOnClickListener(v -> fire(c -> c.onImport(IMPORT_EXCEL)));
        binding.rowImportImage.setOnClickListener(v -> fire(c -> c.onImport(IMPORT_IMAGE)));
        binding.rowImportCaj.setOnClickListener(v -> fire(c -> c.onImport(IMPORT_CAJ)));
        binding.rowImportScan.setOnClickListener(v -> fire(c -> c.onImport(IMPORT_SCAN)));
        binding.rowImportFile.setOnClickListener(v -> fire(c -> c.onImport(IMPORT_FILE)));

        return binding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        applySheetHeight();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The host activity handles orientation changes itself
        // (configChanges in the manifest), so the dialog survives rotation
        // with the height captured for the previous orientation. Re-apply it
        // (landscape -> portrait left the sheet short at the top with a big
        // empty gap below).
        applySheetHeight();
    }

    /**
     * The 12 menu rows are taller than a landscape screen, which makes the
     * default bottom-sheet state collapse into a bottom sliver (landscape bug
     * report). Give the sheet a near-fullscreen height — re-computed for the
     * current orientation — and expand it; the inner NestedScrollView scrolls
     * the overflow instead.
     */
    private void applySheetHeight() {
        Dialog dialog = getDialog();
        if (dialog == null) {
            return;
        }
        View sheet = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) {
            return;
        }
        ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        lp.height = (int) (getResources().getDisplayMetrics().heightPixels
                * 0.9f);
        sheet.setLayoutParams(lp);
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
        behavior.setFitToContents(false);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private interface Action {
        void run(Callback callback);
    }

    private void fire(Action action) {
        if (callback != null) {
            action.run(callback);
        }
        dismiss();
    }
}
