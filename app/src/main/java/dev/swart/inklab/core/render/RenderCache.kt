package dev.swart.inklab.core.render

/**
 * Byte-budgeted LRU used by page/background rendering. Generation is part of the key contract so a
 * late decoder result can never replace a newer document revision.
 */
class RenderCache<K, V>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
    private val onEvict: (V) -> Unit = {}
) {
    init { require(maxBytes > 0) }

    private data class Entry<V>(val generation: Long, val value: V, val bytes: Long)
    private val values = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {}
    private var usedBytes = 0L

    @Synchronized
    fun get(key: K, generation: Long): V? {
        val entry = values[key] ?: return null
        if (entry.generation != generation) {
            removeInternal(key, entry)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, generation: Long, value: V): Boolean {
        val bytes = sizeOf(value).coerceAtLeast(0L)
        if (bytes > maxBytes) return false
        values.remove(key)?.let { old -> usedBytes -= old.bytes; onEvict(old.value) }
        values[key] = Entry(generation, value, bytes)
        usedBytes += bytes
        trim()
        return values[key]?.value === value
    }

    @Synchronized
    fun invalidate(key: K) {
        values[key]?.let { removeInternal(key, it) }
    }

    @Synchronized
    fun invalidateGeneration(generation: Long) {
        val stale = values.filterValues { it.generation != generation }.keys.toList()
        stale.forEach { key -> values[key]?.let { removeInternal(key, it) } }
    }

    @Synchronized
    fun clear() {
        values.values.forEach { onEvict(it.value) }
        values.clear()
        usedBytes = 0L
    }

    @Synchronized fun bytes(): Long = usedBytes
    @Synchronized fun size(): Int = values.size

    private fun trim() {
        val iterator = values.entries.iterator()
        while (usedBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next().value
            iterator.remove()
            usedBytes -= entry.bytes
            onEvict(entry.value)
        }
    }

    private fun removeInternal(key: K, entry: Entry<V>) {
        values.remove(key)
        usedBytes -= entry.bytes
        onEvict(entry.value)
    }
}
