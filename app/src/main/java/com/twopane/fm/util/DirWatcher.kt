package com.twopane.fm.util

import android.os.FileObserver
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches a directory (non-recursive) and fires [onChange] debounced
 * whenever its contents change. Used to keep file panes live.
 */
class DirWatcher(
    private val path: String,
    private val onChange: () -> Unit
) : AutoCloseable {

    private var observer: FileObserver? = null
    private var closed = AtomicBoolean(false)

    init {
        observer = object : FileObserver(path, CREATE or DELETE or MOVED_FROM or MOVED_TO or MODIFY or ATTRIB or DELETE_SELF or MOVE_SELF) {
            override fun onEvent(event: Int, p: String?) {
                if (closed.get()) return
                debounce()
            }
        }.also { it.startWatching() }
    }

    @Volatile
    private var pending = false

    private fun debounce() {
        synchronized(this) {
            if (pending) return
            pending = true
        }
        Thread {
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
            synchronized(this) { pending = false }
            if (!closed.get()) onChange()
        }.start()
    }

    override fun close() {
        closed.set(true)
        try { observer?.stopWatching() } catch (_: Exception) {}
        observer = null
    }
}
