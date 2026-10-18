package com.openrad.mobile.remote

/** Input gate independent of UI state. A view-only session never sends input, even on close. */
class RemoteInput(private val pointerWriter: (Int, Int, Int) -> Unit, private val keyWriter: (Int, Boolean) -> Unit) {
    var enabled = false
        private set
    private val pressed = LinkedHashSet<Int>()
    private var x = 0
    private var y = 0
    private var buttons = 0
    @Synchronized fun setEnabled(value: Boolean) { release(); enabled = value }
    @Synchronized fun pointer(px: Int, py: Int, mask: Int) {
        if (!enabled) throw RemoteError("CONTROL_REQUIRED")
        if (mask !in 0..31) throw RemoteError("INVALID_INPUT")
        x = px; y = py; buttons = mask
        pointerWriter(px, py, mask)
    }
    @Synchronized fun key(key: Int, down: Boolean) {
        if (!enabled) throw RemoteError("CONTROL_REQUIRED")
        if (down && pressed.size >= 32 && key !in pressed) throw RemoteError("INPUT_LIMIT")
        if (down) pressed.add(key) else pressed.remove(key)
        keyWriter(key, down)
    }
    @Synchronized fun release() {
        enabled = false
        for (key in pressed.toList()) runCatching { keyWriter(key, false) }
        pressed.clear()
        if (buttons != 0) runCatching { pointerWriter(x, y, 0) }
        buttons = 0
    }
}
