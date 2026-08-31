package com.leslie.colorosnote.core.ink;

/**
 * One undoable edit (requirement 30, Command pattern). Implementations must
 * be fully self-contained: everything needed to undo is captured at
 * construction or execute time.
 */
public interface Command {

    void execute();

    void undo();

    /**
     * Optional coalescing: when the next command on the stack is a compatible
     * continuation (e.g. another move of the same objects during a drag),
     * absorb it and return true so no extra history entry is created.
     */
    default boolean mergeWith(Command next) {
        return false;
    }
}
