package app.bekocat.core.timeline

/**
 * Transactional undo/redo over immutable project snapshots. Because the model
 * is persistent (structural sharing via data-class copies), snapshots are
 * cheap. A multi-step AI command is committed as ONE transaction so a single
 * Undo reverts it without touching unrelated edits made before it.
 */
class History(initial: Project, private val maxDepth: Int = 200) {

    data class Entry(val label: String, val before: Project, val after: Project)

    var current: Project = initial
        private set
    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label
    val entries: List<Entry> get() = undoStack.toList()

    /** Applies [op]; on exception the project is left untouched and the error rethrown. */
    fun commit(label: String, nowMs: Long, op: (Project) -> Project): Project {
        val before = current
        val after = op(before).copy(modifiedAtMs = nowMs)
        if (after == before.copy(modifiedAtMs = nowMs)) return current // no-op
        undoStack.addLast(Entry(label, before, after))
        while (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
        current = after
        return current
    }

    fun undo(): Project? {
        val e = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(e)
        current = e.before
        return current
    }

    fun redo(): Project? {
        val e = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(e)
        current = e.after
        return current
    }

    /** Replaces state without history (e.g. after loading or relinking). */
    fun reset(p: Project) {
        current = p; undoStack.clear(); redoStack.clear()
    }
}
