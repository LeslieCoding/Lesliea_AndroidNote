package com.leslie.colorosnote.feature.editor;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.leslie.colorosnote.core.ink.InkCommands;
import com.leslie.colorosnote.core.model.ImageObject;
import com.leslie.colorosnote.core.model.Page;
import com.leslie.colorosnote.core.model.PageObject;
import com.leslie.colorosnote.core.model.ShapeObject;
import com.leslie.colorosnote.core.model.StickerObject;
import com.leslie.colorosnote.core.model.TapeObject;
import com.leslie.colorosnote.core.model.TextBoxObject;
import com.leslie.colorosnote.core.render.ViewportTransform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Object tools (Phase 5 / spec 17–19): insertion and editing of images, text
 * boxes, shapes, stickers and memory tapes. Receives aux-toolbar picks via
 * {@link AuxTool.AuxToolListener} and page touches via
 * {@link EditorGestureController.TouchInterceptor} while a tool is armed.
 *
 * <p>All mutations go through the fragment's undo stack
 * ({@code InkCommands.AddObject/RemoveObject}, {@link ObjectCommands}); move /
 * scale / rotate of existing objects reuse the lasso selection
 * ({@link SelectionController} + {@code EditorCommands.TransformObjects}).
 *
 * <p>Insertion gestures: shapes and tapes are dragged out; text boxes and
 * stickers are placed with a tap; images pick a location with a tap and then
 * delegate the gallery intent to the host activity ({@link ImageInsertHost}).
 * Tapping an existing tape toggles its reveal state (TAP_TOGGLE, spec 19).
 * In armed text-box mode a placed box keeps its dashed placeholder; tapping a
 * non-empty box selects it (drag to move, bottom-right handle to resize, tap
 * for the action menu), double-tapping reopens its editor.
 */
public final class ObjectToolController
        implements AuxTool.AuxToolListener, EditorGestureController.TouchInterceptor {

    /**
     * Implemented by the host activity to serve the gallery intent (spec
     * 18.1): the editor defines the flow, the host owns the platform picker.
     */
    public interface ImageInsertHost {
        void requestImagePick(ImagePickCallback callback);
    }

    /** Result of a host-driven image pick; invoked on the main thread. */
    public interface ImagePickCallback {
        /** @param resourceId id under which the bitmap was registered */
        void onImagePicked(String resourceId, int widthPx, int heightPx);

        void onImagePickCancelled();
    }

    private enum Mode {NONE, SHAPE, TAPE, TEXT_BOX, PLACE_STICKER, PLACE_IMAGE}

    private static final float DRAG_SLOP_PAGE = 6f;
    private static final float TAP_HIT_SLOP_PX = 20f;
    private static final long DOUBLE_TAP_MS = 400L;
    private static final float TEXTBOX_WIDTH = 260f;
    private static final float TEXTBOX_MIN_WIDTH = 80f;
    private static final float SELECTION_PAD_PX = 6f;
    private static final float RESIZE_HANDLE_PX = 34f;
    private static final float STICKER_SIZE = 96f;
    private static final float IMAGE_MAX_DIM = 360f;
    private static final float SHAPE_MIN_SIZE = 8f;
    private static final String PREFS = "object_tools_prefs";
    private static final String KEY_RECENT_STICKERS = "recent_stickers";

    private final EditorFragment fragment;
    private final RecentStickers recentStickers = new RecentStickers();
    private final TapeConfigPopup.Config tapeConfig = new TapeConfigPopup.Config();

    private Mode mode = Mode.NONE;
    private ShapeObject.ShapeType shapeType = ShapeObject.ShapeType.LINE;
    private String pendingStickerId;

    // In-progress gesture state (page coordinates).
    private float downX;
    private float downY;
    private boolean dragging;
    private final List<Float> dragPath = new ArrayList<>();
    @Nullable
    private TapeObject downTape;

    // Text box double-tap detection.
    @Nullable
    private String lastTappedBoxId;
    private long lastTappedBoxAtMs;

    // Text box selection (armed TEXT_BOX mode): drag to move, bottom-right
    // handle to resize; committed as one undoable command on UP.
    @Nullable
    private TextBoxObject selectedBox;
    @Nullable
    private TextBoxObject downTextBox;
    @Nullable
    private float[] gestureStartBounds;
    private boolean resizeDrag;

    private PopupWindow toolPopup;
    /** Lazily created speech-to-text card (AuxTool.SPEECH_TO_TEXT). */
    private SpeechToTextController speechToTextController;

    public ObjectToolController(EditorFragment fragment) {
        this.fragment = fragment;
        restoreRecentStickers();
    }

    /** Active insertion mode, for tests / host queries. */
    public boolean isArmed() {
        return mode != Mode.NONE;
    }

    /** Disarms the tool (fragment teardown, tool switch). */
    public void disarm() {
        mode = Mode.NONE;
        pendingStickerId = null;
        downTape = null;
        dragging = false;
        dragPath.clear();
        clearTextBoxSelection();
        downTextBox = null;
        gestureStartBounds = null;
        resizeDrag = false;
        clearPreview();
    }

    public void dispose() {
        disarm();
        dismissPopup();
        if (speechToTextController != null) {
            speechToTextController.dispose();
            speechToTextController = null;
        }
    }

    // ------------------------------------------------------------------
    // AuxTool.AuxToolListener
    // ------------------------------------------------------------------

    @Override
    public void onAuxToolSelected(AuxTool tool) {
        switch (tool) {
            case IMAGE:
                if (imageHost() == null) {
                    toast(R.string.obj_image_no_host);
                    return;
                }
                arm(Mode.PLACE_IMAGE);
                break;
            case TEXT_BOX:
                arm(Mode.TEXT_BOX);
                CreamToast.show(fragment.getView(), R.string.obj_textbox_arm_hint);
                break;
            case SHAPE:
                showShapePicker();
                break;
            case TAPE:
                showTapeConfig();
                break;
            case STICKER:
                showStickerPicker();
                break;
            case OCR:
                new OcrToTextController(fragment).start();
                break;
            case HANDWRITING_TO_TEXT:
                new HandwritingToTextController(fragment).start();
                break;
            case SPEECH_TO_TEXT:
                showSpeechToText();
                break;
            default:
                toast(R.string.aux_tool_coming_soon);
                break;
        }
    }

    private void arm(Mode newMode) {
        mode = newMode;
        downTape = null;
        dragging = false;
        dragPath.clear();
        clearTextBoxSelection();
        downTextBox = null;
        gestureStartBounds = null;
        resizeDrag = false;
    }

    // ------------------------------------------------------------------
    // EditorGestureController.TouchInterceptor
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mode == Mode.NONE) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleDown(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                handleMove(event);
                return true;
            case MotionEvent.ACTION_UP:
                handleUp(event);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                dragPath.clear();
                downTape = null;
                cancelTextBoxGesture();
                clearPreview();
                return true;
            default:
                return true;
        }
    }

    private void handleDown(MotionEvent event) {
        float[] page = toPage(event.getX(), event.getY());
        downX = page[0];
        downY = page[1];
        dragging = false;
        dragPath.clear();
        dragPath.add(downX);
        dragPath.add(downY);
        downTape = null;
        if (mode == Mode.TAPE) {
            Page p = fragment.getCurrentPage();
            if (p != null) {
                PageObject hit = ObjectHitTester.topmostAt(
                        p.objects, downX, downY, tapSlopPage());
                if (hit instanceof TapeObject) {
                    downTape = (TapeObject) hit;
                }
            }
        }
        if (mode == Mode.TEXT_BOX) {
            handleTextBoxDown(event);
        }
    }

    /**
     * DOWN in text-box mode: a non-empty unlocked box becomes selected (drag
     * = move, bottom-right handle = resize); empty/locked boxes and blank
     * space are handled on UP (editor / menu / placement).
     */
    private void handleTextBoxDown(MotionEvent event) {
        downTextBox = null;
        gestureStartBounds = null;
        resizeDrag = false;
        Page p = fragment.getCurrentPage();
        PageObject hit = p == null ? null
                : ObjectHitTester.topmostAt(p.objects, downX, downY, tapSlopPage(), true);
        if (!(hit instanceof TextBoxObject)) {
            clearTextBoxSelection();
            return;
        }
        TextBoxObject box = (TextBoxObject) hit;
        downTextBox = box;
        if (box.locked || box.text == null || box.text.isEmpty()) {
            return;
        }
        selectTextBox(box);
        gestureStartBounds = box.bounds.clone();
        resizeDrag = onResizeHandle(event.getX(), event.getY());
    }

    private void handleMove(MotionEvent event) {
        float[] page = toPage(event.getX(), event.getY());
        float dx = page[0] - downX;
        float dy = page[1] - downY;
        if (!dragging && Math.hypot(dx, dy) > DRAG_SLOP_PAGE / viewportScale()) {
            dragging = true;
        }
        if (!dragging) {
            return;
        }
        switch (mode) {
            case TAPE:
                if (!tapeConfig.straight) {
                    dragPath.add(page[0]);
                    dragPath.add(page[1]);
                } else {
                    // Straight tape preview: keep just the endpoints.
                    dragPath.clear();
                    dragPath.add(downX);
                    dragPath.add(downY);
                    dragPath.add(page[0]);
                    dragPath.add(page[1]);
                }
                showPathPreview(dragPath);
                break;
            case SHAPE: {
                List<Float> preview = Arrays.asList(downX, downY, page[0], page[1]);
                showShapePreview(preview);
                break;
            }
            case TEXT_BOX:
                handleTextBoxDrag(dx, dy);
                break;
            default:
                break;
        }
    }

    /** Live move / corner-resize of the selected text box (commit on UP). */
    private void handleTextBoxDrag(float dx, float dy) {
        if (selectedBox == null || gestureStartBounds == null
                || downTextBox != selectedBox || selectedBox.locked) {
            return;
        }
        if (resizeDrag) {
            float minHeight = selectedBox.fontSize * selectedBox.lineSpacing;
            selectedBox.bounds = clampResizedBounds(gestureStartBounds,
                    gestureStartBounds[2] + dx, gestureStartBounds[3] + dy,
                    TEXTBOX_MIN_WIDTH, minHeight);
        } else {
            selectedBox.bounds = new float[]{
                    gestureStartBounds[0] + dx, gestureStartBounds[1] + dy,
                    gestureStartBounds[2] + dx, gestureStartBounds[3] + dy};
        }
        showTextBoxSelection(selectedBox);
        fragment.scheduleObjectToolRefresh();
    }

    /**
     * Bottom-right resize with the top-left corner anchored to {@code base};
     * clamps to minimum extents (page units). Pure, for tests.
     */
    static float[] clampResizedBounds(float[] base, float right, float bottom,
                                      float minWidth, float minHeight) {
        return new float[]{base[0], base[1],
                Math.max(right, base[0] + minWidth),
                Math.max(bottom, base[1] + minHeight)};
    }

    private void handleUp(MotionEvent event) {
        float[] page = toPage(event.getX(), event.getY());
        boolean wasDragging = dragging;
        dragging = false;
        clearPreview();
        switch (mode) {
            case SHAPE:
                if (wasDragging) {
                    insertShape(downX, downY, page[0], page[1]);
                } else {
                    offerObjectActions(page[0], page[1], ShapeObject.class);
                }
                break;
            case TAPE:
                if (wasDragging) {
                    insertTape(page[0], page[1]);
                } else if (downTape != null) {
                    toggleTape(downTape);
                }
                downTape = null;
                break;
            case TEXT_BOX:
                if (wasDragging && selectedBox != null && downTextBox == selectedBox
                        && gestureStartBounds != null) {
                    commitSelectedBoxBounds();
                } else {
                    handleTextBoxTap(page[0], page[1]);
                }
                downTextBox = null;
                gestureStartBounds = null;
                resizeDrag = false;
                break;
            case PLACE_STICKER:
                insertSticker(page[0], page[1]);
                break;
            case PLACE_IMAGE:
                requestImage(page[0], page[1]);
                break;
            default:
                break;
        }
        dragPath.clear();
    }

    // ------------------------------------------------------------------
    // Insertion
    // ------------------------------------------------------------------

    private void insertShape(float x0, float y0, float x1, float y1) {
        Page p = fragment.getCurrentPage();
        if (p == null) {
            return;
        }
        ShapeObject shape = new ShapeObject();
        shape.shapeType = shapeType;
        shape.points = new ArrayList<>(Arrays.asList(x0, y0, x1, y1));
        float l = Math.min(x0, x1);
        float r = Math.max(x0, x1);
        float t = Math.min(y0, y1);
        float b = Math.max(y0, y1);
        // Zero-area drags get a minimum extent so the shape stays grabbable.
        if (r - l < SHAPE_MIN_SIZE) {
            r = l + SHAPE_MIN_SIZE;
        }
        if (b - t < SHAPE_MIN_SIZE) {
            b = t + SHAPE_MIN_SIZE;
        }
        float padOut = Math.max(4f, shape.strokeWidth * 2f);
        shape.bounds = new float[]{l - padOut, t - padOut, r + padOut, b + padOut};
        addObject(p, shape);
    }

    private void insertTape(float upX, float upY) {
        Page p = fragment.getCurrentPage();
        if (p == null) {
            return;
        }
        TapeObject tape = new TapeObject();
        tape.width = tapeConfig.width;
        tape.color = tapeConfig.color;
        tape.pattern = tapeConfig.pattern;
        tape.revealMode = TapeObject.RevealMode.TAP_TOGGLE;
        if (tapeConfig.straight) {
            tape.rect = TapeGeometry.straightRect(downX, downY, upX, upY, tapeConfig.width);
            tape.bounds = tape.rect.clone();
        } else {
            List<Float> simplified = TapeGeometry.simplify(dragPath, 2f);
            if (simplified.size() < 4) {
                return;
            }
            tape.path = simplified;
            tape.bounds = TapeGeometry.pathBounds(simplified, tapeConfig.width);
        }
        addObject(p, tape);
    }

    private void insertSticker(float x, float y) {
        Page p = fragment.getCurrentPage();
        if (p == null || pendingStickerId == null) {
            return;
        }
        StickerObject sticker = new StickerObject();
        sticker.stickerId = pendingStickerId;
        float half = STICKER_SIZE / 2f;
        sticker.bounds = new float[]{x - half, y - half, x + half, y + half};
        recentStickers.record(pendingStickerId);
        persistRecentStickers();
        addObject(p, sticker);
        disarm(); // one-shot placement
    }

    private void requestImage(float x, float y) {
        ImageInsertHost host = imageHost();
        if (host == null) {
            toast(R.string.obj_image_no_host);
            disarm();
            return;
        }
        final float insertX = x;
        final float insertY = y;
        host.requestImagePick(new ImagePickCallback() {
            @Override
            public void onImagePicked(String resourceId, int widthPx, int heightPx) {
                insertImage(insertX, insertY, resourceId, widthPx, heightPx);
            }

            @Override
            public void onImagePickCancelled() {
                // Stay armed: the user can pick another spot or switch tools.
            }
        });
    }

    private void insertImage(float x, float y, String resourceId, int widthPx, int heightPx) {
        Page p = fragment.getCurrentPage();
        if (p == null || widthPx <= 0 || heightPx <= 0) {
            return;
        }
        float scale = Math.min(1f, IMAGE_MAX_DIM / Math.max(widthPx, heightPx));
        float w = widthPx * scale;
        float h = heightPx * scale;
        ImageObject image = new ImageObject();
        image.resourceId = resourceId;
        image.bounds = new float[]{x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f};
        addObject(p, image);
        disarm();
    }

    private void addObject(Page page, PageObject object) {
        object.zIndex = ObjectZOrder.frontZIndex(page.objects);
        fragment.getUndoStack().execute(new InkCommands.AddObject(page, object));
        fragment.notifyObjectToolEdited();
    }

    // ------------------------------------------------------------------
    // Text boxes (spec 18.2)
    // ------------------------------------------------------------------

    private void handleTextBoxTap(float x, float y) {
        Page p = fragment.getCurrentPage();
        if (p == null) {
            return;
        }
        PageObject hit = ObjectHitTester.topmostAt(p.objects, x, y, tapSlopPage(), true);
        if (!(hit instanceof TextBoxObject)) {
            // Blank space: drop the selection and place a new box.
            clearTextBoxSelection();
            insertTextBox(p, x, y);
            return;
        }
        TextBoxObject box = (TextBoxObject) hit;
        if (box.locked) {
            showObjectMenu(box);
            return;
        }
        if (box.text == null || box.text.isEmpty()) {
            // Placeholder box: a tap goes straight into the editor.
            openTextBoxEditor(box);
            return;
        }
        // Non-empty box: already selected on DOWN; a tap (no drag) opens the
        // action menu, a double-tap reopens the editor.
        long now = android.os.SystemClock.uptimeMillis();
        boolean doubleTap = box.id.equals(lastTappedBoxId)
                && now - lastTappedBoxAtMs < DOUBLE_TAP_MS;
        lastTappedBoxId = box.id;
        lastTappedBoxAtMs = now;
        if (doubleTap) {
            openTextBoxEditor(box);
        } else {
            showObjectMenu(box);
        }
    }

    private void insertTextBox(Page page, float x, float y) {
        TextBoxObject box = new TextBoxObject();
        box.fontSize = 18f;
        float line = box.fontSize * box.lineSpacing;
        box.bounds = new float[]{x, y, x + TEXTBOX_WIDTH, y + line * 2f};
        box.zIndex = ObjectZOrder.frontZIndex(page.objects);
        fragment.getUndoStack().execute(new InkCommands.AddObject(page, box));
        fragment.notifyObjectToolEdited();
        // No auto-editor: the dashed "输入文本" placeholder stays on the page
        // and the tool stays armed, so the user can place several boxes and
        // tap any of them to start typing.
    }

    private void openTextBoxEditor(TextBoxObject box) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        TextBoxEditDialog.show(context, box, new TextBoxEditDialog.Listener() {
            @Override
            public void onConfirmed(TextBoxEditDialog.Draft draft) {
                ObjectCommands.EditTextBox.Snapshot after =
                        ObjectCommands.EditTextBox.Snapshot.capture(box);
                after.text = draft.text;
                after.bold = draft.bold;
                after.italic = draft.italic;
                after.underline = draft.underline;
                after.fontSize = draft.fontSize;
                after.color = draft.color;
                // Auto height: bounds follow the line count (spec 18.2).
                int lines = draft.text.isEmpty() ? 1 : draft.text.split("\n", -1).length;
                float height = Math.max((lines + 0.6f) * draft.fontSize * box.lineSpacing,
                        draft.fontSize * box.lineSpacing * 1.6f);
                after.bounds = new float[]{box.bounds[0], box.bounds[1],
                        box.bounds[2], box.bounds[1] + height};
                fragment.getUndoStack().execute(new ObjectCommands.EditTextBox(box, after));
                fragment.notifyObjectToolEdited();
                // Keep the incremental search index in sync (spec 22).
                Page p = fragment.getCurrentPage();
                if (p != null) {
                    EditorServices.get(context).searchIndexer()
                            .indexTextBox(fragment.getDocumentId(), p.id, draft.text);
                }
            }

            @Override
            public void onCancelled() {
                // The box stays where the user placed it; cancelling the
                // editor never rolls the placement back.
            }
        });
    }

    // ------------------------------------------------------------------
    // Text box selection (move / resize / overlay)
    // ------------------------------------------------------------------

    private void selectTextBox(TextBoxObject box) {
        selectedBox = box;
        showTextBoxSelection(box);
    }

    /** Draws the overlay selection frame (padded screen rect) for the box. */
    private void showTextBoxSelection(TextBoxObject box) {
        EditorOverlayView overlay = fragment.overlayView();
        if (overlay == null) {
            return;
        }
        ViewportTransform viewport = fragment.viewport();
        float[] a = new float[2];
        float[] b = new float[2];
        viewport.pageToScreen(box.bounds[0], box.bounds[1], a);
        viewport.pageToScreen(box.bounds[2], box.bounds[3], b);
        overlay.setSelectionRect(new RectF(
                Math.min(a[0], b[0]) - SELECTION_PAD_PX,
                Math.min(a[1], b[1]) - SELECTION_PAD_PX,
                Math.max(a[0], b[0]) + SELECTION_PAD_PX,
                Math.max(a[1], b[1]) + SELECTION_PAD_PX));
    }

    private void clearTextBoxSelection() {
        selectedBox = null;
        EditorOverlayView overlay = fragment.overlayView();
        if (overlay != null) {
            overlay.clearSelection();
        }
    }

    /** Hit test for the bottom-right resize handle (screen pixels). */
    private boolean onResizeHandle(float screenX, float screenY) {
        if (selectedBox == null) {
            return false;
        }
        float[] corner = new float[2];
        fragment.viewport().pageToScreen(
                selectedBox.bounds[2], selectedBox.bounds[3], corner);
        return Math.abs(screenX - corner[0]) <= RESIZE_HANDLE_PX
                && Math.abs(screenY - corner[1]) <= RESIZE_HANDLE_PX;
    }

    /** Commits the live-mutated bounds as one undoable EditTextBox command. */
    private void commitSelectedBoxBounds() {
        TextBoxObject box = selectedBox;
        float[] finalBounds = box.bounds.clone();
        // Restore the gesture-start bounds so the command's before-snapshot
        // is exact, then let the command apply the final bounds.
        box.bounds = gestureStartBounds.clone();
        ObjectCommands.EditTextBox.Snapshot after =
                ObjectCommands.EditTextBox.Snapshot.capture(box);
        after.bounds = finalBounds;
        fragment.getUndoStack().execute(new ObjectCommands.EditTextBox(box, after));
        fragment.notifyObjectToolEdited();
        showTextBoxSelection(box);
    }

    /** Restores gesture-start bounds when the gesture is cancelled. */
    private void cancelTextBoxGesture() {
        if (selectedBox != null && gestureStartBounds != null) {
            selectedBox.bounds = gestureStartBounds.clone();
            showTextBoxSelection(selectedBox);
            fragment.scheduleObjectToolRefresh();
        }
        downTextBox = null;
        gestureStartBounds = null;
        resizeDrag = false;
    }

    // ------------------------------------------------------------------
    // Tape interactions (spec 19)
    // ------------------------------------------------------------------

    private void toggleTape(TapeObject tape) {
        if (tape.revealMode != TapeObject.RevealMode.TAP_TOGGLE) {
            return;
        }
        fragment.getUndoStack().execute(
                new ObjectCommands.SetTapeRevealed(tape, !tape.isRevealed));
        fragment.notifyObjectToolEdited();
    }

    private void setAllTapesRevealed(boolean revealed) {
        Page p = fragment.getCurrentPage();
        if (p == null) {
            return;
        }
        List<TapeObject> tapes = new ArrayList<>();
        for (PageObject o : p.objects) {
            if (o instanceof TapeObject) {
                tapes.add((TapeObject) o);
            }
        }
        if (tapes.isEmpty()) {
            toast(R.string.obj_tape_none);
            return;
        }
        fragment.getUndoStack().execute(new ObjectCommands.SetTapeRevealed(tapes, revealed));
        fragment.notifyObjectToolEdited();
    }

    // ------------------------------------------------------------------
    // Object action menu (置顶/置底/锁定/删除/透明度/编辑)
    // ------------------------------------------------------------------

    /** Tap on an existing object of the armed tool's type opens its actions. */
    private void offerObjectActions(float x, float y, Class<? extends PageObject> type) {
        Page p = fragment.getCurrentPage();
        if (p == null || fragment.getView() == null) {
            return;
        }
        PageObject hit = ObjectHitTester.topmostAt(p.objects, x, y, tapSlopPage(), true);
        if (hit != null && type.isInstance(hit)) {
            showObjectMenu(hit);
        }
    }

    private void showObjectMenu(PageObject object) {
        if (fragment.getView() == null || fragment.getContext() == null) {
            return;
        }
        Page p = fragment.getCurrentPage();
        if (p == null) {
            return;
        }
        PopupMenu menu = new PopupMenu(fragment.requireContext(),
                fragment.requireView(), Gravity.CENTER);
        Menu m = menu.getMenu();
        if (object.locked) {
            m.add(0, 10, 0, R.string.obj_unlock);
        } else {
            if (object instanceof TextBoxObject) {
                m.add(0, 5, 0, R.string.obj_edit_text);
            }
            if (object instanceof ImageObject) {
                SubMenu opacity = m.addSubMenu(0, 6, 1, R.string.obj_opacity);
                opacity.add(0, 60, 0, "25%");
                opacity.add(0, 61, 1, "50%");
                opacity.add(0, 62, 2, "75%");
                opacity.add(0, 63, 3, "100%");
            }
            m.add(0, 1, 2, R.string.obj_bring_front);
            m.add(0, 2, 3, R.string.obj_send_back);
            m.add(0, 3, 4, R.string.obj_lock);
            m.add(0, 4, 5, R.string.obj_delete);
        }
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    executeObjectCommand(new ObjectCommands.SetZIndex(object,
                            ObjectZOrder.frontZIndex(p.objects)));
                    return true;
                case 2:
                    executeObjectCommand(new ObjectCommands.SetZIndex(object,
                            ObjectZOrder.backZIndex(p.objects)));
                    return true;
                case 3:
                    executeObjectCommand(new ObjectCommands.SetLocked(
                            Collections.singletonList(object), true));
                    toast(R.string.obj_locked_hint);
                    return true;
                case 10:
                    executeObjectCommand(new ObjectCommands.SetLocked(
                            Collections.singletonList(object), false));
                    return true;
                case 4:
                    fragment.getUndoStack().execute(new InkCommands.RemoveObject(p, object));
                    fragment.notifyObjectToolEdited();
                    return true;
                case 5:
                    openTextBoxEditor((TextBoxObject) object);
                    return true;
                case 60:
                case 61:
                case 62:
                case 63:
                    float alpha = (item.getItemId() - 59) * 0.25f; // 60→0.25 … 63→1.0
                    executeObjectCommand(new ObjectCommands.SetImageOpacity(
                            (ImageObject) object, alpha));
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void executeObjectCommand(com.leslie.colorosnote.core.ink.Command command) {
        fragment.getUndoStack().execute(command);
        fragment.notifyObjectToolEdited();
    }

    // ------------------------------------------------------------------
    // Tool pickers
    // ------------------------------------------------------------------

    private void showShapePicker() {
        if (fragment.getView() == null) {
            return;
        }
        dismissPopup();
        toolPopup = ShapePickerPopup.show(fragment.requireView(), shapeType, type -> {
            dismissPopup();
            shapeType = type;
            arm(Mode.SHAPE);
            CreamToast.show(fragment.getView(), R.string.obj_shape_arm_hint);
        });
    }

    private void showTapeConfig() {
        if (fragment.getView() == null) {
            return;
        }
        dismissPopup();
        toolPopup = TapeConfigPopup.show(fragment.requireView(), tapeConfig,
                new TapeConfigPopup.Listener() {
                    @Override
                    public void onCoverAll() {
                        setAllTapesRevealed(false);
                    }

                    @Override
                    public void onRevealAll() {
                        setAllTapesRevealed(true);
                    }
                });
        arm(Mode.TAPE);
    }

    private void showStickerPicker() {
        if (fragment.getView() == null) {
            return;
        }
        dismissPopup();
        toolPopup = StickerPickerPopup.show(fragment.requireView(), recentStickers,
                stickerId -> {
                    dismissPopup();
                    pendingStickerId = stickerId;
                    arm(Mode.PLACE_STICKER);
                    CreamToast.show(fragment.getView(), R.string.obj_sticker_arm_hint);
                });
    }

    // ------------------------------------------------------------------
    // Speech to text (spec 20.4)
    // ------------------------------------------------------------------

    private void showSpeechToText() {
        if (fragment.getView() == null || fragment.getContext() == null) {
            return;
        }
        if (speechToTextController == null) {
            speechToTextController =
                    new SpeechToTextController(fragment, this::insertTranscriptTextBox);
        }
        speechToTextController.show();
    }

    /**
     * SpeechToTextController.Host: inserts the recognized text as a text box
     * near the viewport's upper-middle (undoable AddObject path).
     */
    private void insertTranscriptTextBox(String text) {
        Page p = fragment.getCurrentPage();
        if (p == null || text == null || text.isEmpty() || fragment.getView() == null) {
            return;
        }
        TextBoxObject box = new TextBoxObject();
        box.text = text;
        box.fontSize = 18f;
        float line = box.fontSize * box.lineSpacing;
        int lines = text.split("\n", -1).length;
        float height = Math.max((lines + 0.6f) * line, line * 1.6f);
        float[] at = toPage(fragment.getView().getWidth() / 2f,
                fragment.getView().getHeight() * 0.35f);
        box.bounds = new float[]{at[0] - TEXTBOX_WIDTH / 2f, at[1],
                at[0] + TEXTBOX_WIDTH / 2f, at[1] + height};
        addObject(p, box);
    }

    // ------------------------------------------------------------------
    // Preview + helpers
    // ------------------------------------------------------------------

    private void showPathPreview(List<Float> pagePoints) {
        EditorOverlayView overlay = fragment.overlayView();
        if (overlay == null) {
            return;
        }
        List<Float> screen = new ArrayList<>(pagePoints.size());
        ViewportTransform viewport = fragment.viewport();
        float[] out = new float[2];
        for (int i = 0; i + 1 < pagePoints.size(); i += 2) {
            viewport.pageToScreen(pagePoints.get(i), pagePoints.get(i + 1), out);
            screen.add(out[0]);
            screen.add(out[1]);
        }
        overlay.setLassoPath(screen, false);
    }

    private void showShapePreview(List<Float> pageEndpoints) {
        EditorOverlayView overlay = fragment.overlayView();
        if (overlay == null) {
            return;
        }
        ViewportTransform viewport = fragment.viewport();
        float[] a = new float[2];
        float[] b = new float[2];
        viewport.pageToScreen(pageEndpoints.get(0), pageEndpoints.get(1), a);
        viewport.pageToScreen(pageEndpoints.get(2), pageEndpoints.get(3), b);
        boolean rectLike = shapeType == ShapeObject.ShapeType.RECT
                || shapeType == ShapeObject.ShapeType.ELLIPSE
                || shapeType == ShapeObject.ShapeType.TRIANGLE
                || shapeType == ShapeObject.ShapeType.DIAMOND;
        overlay.setLassoPath(Arrays.asList(a[0], a[1], b[0], b[1]), rectLike);
    }

    private void clearPreview() {
        EditorOverlayView overlay = fragment.overlayView();
        if (overlay != null) {
            overlay.clearLasso();
        }
    }

    private float[] toPage(float viewX, float viewY) {
        float[] out = new float[2];
        fragment.viewport().screenToPage(viewX, viewY, out);
        return out;
    }

    private float viewportScale() {
        return Math.max(0.01f, fragment.viewport().getScale());
    }

    private float tapSlopPage() {
        return TAP_HIT_SLOP_PX / viewportScale();
    }

    @Nullable
    private ImageInsertHost imageHost() {
        Activity activity = fragment.getActivity();
        return activity instanceof ImageInsertHost ? (ImageInsertHost) activity : null;
    }

    private void dismissPopup() {
        if (toolPopup != null) {
            toolPopup.dismiss();
            toolPopup = null;
        }
    }

    private void toast(int textRes) {
        Context context = fragment.getContext();
        if (context != null) {
            Toast.makeText(context, textRes, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Recent sticker persistence (spec 18.3 最近使用)
    // ------------------------------------------------------------------

    private SharedPreferences prefs() {
        Context context = fragment.getContext();
        return context == null ? null
                : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void persistRecentStickers() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (String id : recentStickers.mostRecent()) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(id);
        }
        prefs.edit().putString(KEY_RECENT_STICKERS, joined.toString()).apply();
    }

    private void restoreRecentStickers() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        String saved = prefs.getString(KEY_RECENT_STICKERS, null);
        if (saved != null && !saved.isEmpty()) {
            recentStickers.restore(Arrays.asList(saved.split(",")));
        }
    }
}
