package dev.swart.inklab.core.export

/** Parses user-facing one-based page selections such as `1-8,12` into zero-based indices. */
object PageRange {
    fun parse(value: String, pageCount: Int): List<Int> {
        require(pageCount > 0) { "Документ не содержит страниц" }
        val source = value.trim()
        if (source.isEmpty()) return (0 until pageCount).toList()

        val result = linkedSetOf<Int>()
        source.split(',').forEach { rawToken ->
            val token = rawToken.trim()
            require(token.isNotEmpty()) { "Пустой элемент диапазона страниц" }
            val parts = token.split('-').map(String::trim)
            when (parts.size) {
                1 -> result += checkedPage(parts[0], pageCount) - 1
                2 -> {
                    val start = checkedPage(parts[0], pageCount)
                    val end = checkedPage(parts[1], pageCount)
                    require(start <= end) { "Диапазон страниц задан в обратном порядке: $token" }
                    for (page in start..end) result += page - 1
                }
                else -> throw IllegalArgumentException("Некорректный диапазон страниц: $token")
            }
        }
        require(result.isNotEmpty()) { "Не выбрано ни одной страницы" }
        return result.toList()
    }

    private fun checkedPage(value: String, pageCount: Int): Int {
        val page = value.toIntOrNull() ?: throw IllegalArgumentException("Некорректный номер страницы: $value")
        require(page in 1..pageCount) { "Страница $page вне диапазона 1..$pageCount" }
        return page
    }
}
