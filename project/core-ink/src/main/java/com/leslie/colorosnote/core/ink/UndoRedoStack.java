package com.leslie.colorosnote.core.ink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Undo/redo history with a hard capacity and drag-transaction merging
 * (requirement 30). Pure JVM; the stack holds commands, never bitmaps, so a
 * 100-entry history stays small.
 *
 * <p>Usage: call {@link #execute(Command)} for every edit. During a drag,
 * wrap the incremental moves in {@link #beginTransaction()} /
 * {@link #endTransaction()} so they collapse into a single undo step.
 */
public final class UndoRedoStack {

    public static final int DEFAULT_CAPACITY = 100;

    /**
     * Observes history transitions. The editor mirrors stroke edits into the
     * crash journal through this hook: {@link #onApplied(Command)} fires right
     * after a command is executed (initial execute or redo),
     * {@link #onUndone(Command)} right after it is undone. During a
     * transaction each member fires {@code onApplied} as it is executed, and
     * the merged entry (single command or {@code Composite}) fires on
     * undo/redo — so listeners must recurse into composites.
     */
    public interface Listener {
        void onApplied(Command command);

        void onUndone(Command command);
    }

    private final int capacity;
    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();
    private List<Command> transaction;
    private Listener listener;

    public UndoRedoStack() {
        this(DEFAULT_CAPACITY);
    }

    public UndoRedoStack(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    // ------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------

    /**
     * Starts a transaction. Commands executed until {@link #endTransaction()}
     * are applied immediately and merged into one history entry.
     */
    public void beginTransaction() {
        if (transaction != null) {
            throw new IllegalStateException("Transaction already open");
        }
        transaction = new ArrayList<>();
    }

    /** Closes the open transaction; a single command is pushed unwrapped. */
    public void endTransaction() {
        if (transaction == null) {
            throw new IllegalStateException("No open transaction");
        }
        List<Command> commands = transaction;
        transaction = null;
        if (commands.isEmpty()) {
            return;
        }
        push(commands.size() == 1
                ? commands.get(0)
                : new InkCommands.Composite(commands));
    }

    public boolean isInTransaction() {
        return transaction != null;
    }

    /** Registers the history observer (journal mirroring); null clears it. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // Execute / undo / redo
    // ------------------------------------------------------------------

    /** Executes the command and records it (or merges it into a transaction). */
    public void execute(Command command) {
        command.execute();
        notifyApplied(command);
        if (transaction != null) {
            transaction.add(command);
            redoStack.clear();
            return;
        }
        // Coalesce with the previous entry when compatible (e.g. drag moves).
        Command top = undoStack.peekLast();
        if (top != null && top.mergeWith(command)) {
            redoStack.clear();
            return;
        }
        push(command);
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public int undoSize() {
        return undoStack.size();
    }

    public int redoSize() {
        return redoStack.size();
    }

    /** Undoes the newest command; no-op when the history is empty. */
    public void undo() {
        Command c = undoStack.pollLast();
        if (c == null) {
            return;
        }
        c.undo();
        notifyUndone(c);
        redoStack.addLast(c);
    }

    /** Redoes the most recently undone command; no-op when nothing to redo. */
    public void redo() {
        Command c = redoStack.pollLast();
        if (c == null) {
            return;
        }
        c.execute();
        notifyApplied(c);
        undoStack.addLast(c);
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    private void push(Command command) {
        undoStack.addLast(command);
        while (undoStack.size() > capacity) {
            undoStack.pollFirst(); // drop the oldest entry, never OOM
        }
        redoStack.clear(); // a new edit invalidates the redo branch
    }

    private void notifyApplied(Command command) {
        Listener l = listener;
        if (l != null) {
            l.onApplied(command);
        }
    }

    private void notifyUndone(Command command) {
        Listener l = listener;
        if (l != null) {
            l.onUndone(command);
        }
    }
}
