package dev.swart.inklab.core.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.util.UUID

data class InkPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long,
    val pressure: Float = 1f,
    val tilt: Float = 0f
) {
    fun offset() = Offset(x, y)
}

data class InkStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<InkPoint>,
    val width: Float = 5f,
    val color: Color = Color(0xFF25272C)
)

data class SelectionState(
    val selectedIds: Set<String> = emptySet(),
    val lasso: List<Offset> = emptyList(),
    val active: Boolean = false
)
