package dev.swart.inklab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.PageOrientation
import dev.swart.inklab.ui.EditorViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openViewMenu() {
        compose.onNodeWithContentDescription("Масштаб и вид")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
    }

    @Test
    fun editorPagesCompactChromeAndSettingsAreReachable() {
        compose.onNodeWithText("Библиотека").assertIsDisplayed()
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[EditorViewModel::class.java].createDocument(
                title = "Проверка интерфейса",
                format = DocumentFormat.NOTEBOOK,
                orientation = PageOrientation.PORTRAIT
            )
        }

        compose.onNodeWithText("+ Лист").performClick()
        compose.onNodeWithText("Лист 2").assertExists()

        // Zoom/fit no longer consumes a permanent third row. On very narrow windows secondary
        // controls remain reachable by scrolling the toolbar instead of shrinking touch targets.
        compose.onNodeWithText("По ширине").assertDoesNotExist()
        compose.onNodeWithText("Лист целиком").assertDoesNotExist()
        openViewMenu()
        compose.onNodeWithText("По ширине").assertIsDisplayed().performClick()
        openViewMenu()
        compose.onNodeWithText("Лист целиком").assertIsDisplayed().performClick()

        compose.waitUntil(5000) { !ViewModelProvider(compose.activity)[EditorViewModel::class.java].saving }
        val toolbar = compose.onNodeWithTag("documentToolbar").captureToImage().asAndroidBitmap()
        compose.runOnUiThread {
            val vm = ViewModelProvider(compose.activity)[EditorViewModel::class.java]
            vm.zoomBy(3f, Offset.Zero)
            vm.panBy(Offset(-200f, -500f))
        }
        assertTrue(
            "Pages must not paint over the toolbar",
            toolbar.sameAs(compose.onNodeWithTag("documentToolbar").captureToImage().asAndroidBitmap())
        )

        openViewMenu()
        compose.onNodeWithText("Режим фокуса").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Меню").assertDoesNotExist()
        compose.onNodeWithContentDescription("Выйти из режима фокуса").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Меню").assertIsDisplayed()

        compose.onNodeWithContentDescription("Действия с документом").performClick()
        compose.onNodeWithText("Документ").assertExists()
        compose.onNodeWithText("Диктофон и записи лекций").assertDoesNotExist()
        compose.onNodeWithText("Корзина").assertDoesNotExist()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Отмена двумя пальцами"))
        compose.onNodeWithText("Отмена двумя пальцами").assertExists()
    }

    @Test
    fun boardHasNoNotebookControlsAndLibraryHasSeparateActions() {
        compose.onNodeWithText("Библиотека").assertIsDisplayed()
        compose.onNodeWithContentDescription("Действия библиотеки").performClick()
        compose.onNodeWithText("Корзина").performClick()
        compose.onNodeWithText("Диктофон и записи лекций").assertDoesNotExist()
        compose.onNodeWithText("Готово").performClick()
        compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java].createBoard() }

        compose.onNodeWithText("Исходный вид").assertDoesNotExist()
        openViewMenu()
        compose.onNodeWithText("Исходный вид").assertIsDisplayed().performClick()
        compose.onNodeWithText("Лист целиком").assertDoesNotExist()
        compose.onNodeWithText("+ Лист").assertDoesNotExist()
        compose.onNodeWithText("Страницы").assertDoesNotExist()

        compose.onNodeWithContentDescription("Действия с документом").performClick()
        compose.onNodeWithText("Экспорт текущей страницы в PDF").assertDoesNotExist()
        compose.onNodeWithText("Сохранить резервную копию с аудио").assertDoesNotExist()
    }
}
