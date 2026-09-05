package dev.swart.inklab

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.ViewModelProvider
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.core.model.DocumentFormat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset

@RunWith(AndroidJUnit4::class)
class EditorUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun editorPagesAndSettingsAreReachable() {
        compose.onNodeWithText("Библиотека").assertIsDisplayed()
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[EditorViewModel::class.java].createDocument(title="Проверка интерфейса", format=DocumentFormat.NOTEBOOK, orientation=dev.swart.inklab.core.model.PageOrientation.PORTRAIT)
        }
        compose.onNodeWithText("+ Лист").performClick()
        compose.onNodeWithText("Лист 2").assertExists()
        compose.onNodeWithText("По ширине").assertIsDisplayed().performClick()
        compose.onNodeWithText("Лист целиком").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !ViewModelProvider(compose.activity)[EditorViewModel::class.java].saving }
        val toolbar = compose.onNodeWithTag("documentToolbar").captureToImage().asAndroidBitmap()
        compose.runOnUiThread {
            val vm = ViewModelProvider(compose.activity)[EditorViewModel::class.java]
            vm.zoomBy(3f, Offset.Zero)
            vm.panBy(Offset(-200f, -500f))
        }
        org.junit.Assert.assertTrue("Pages must not paint over the toolbar", toolbar.sameAs(compose.onNodeWithTag("documentToolbar").captureToImage().asAndroidBitmap()))
        compose.onNodeWithText("Фокус").assertDoesNotExist()
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

    @Test fun boardHasNoNotebookControlsAndLibraryHasSeparateActions() {
        compose.onNodeWithText("Библиотека").assertIsDisplayed()
        compose.onNodeWithContentDescription("Действия библиотеки").performClick()
        compose.onNodeWithText("Корзина").performClick()
        compose.onNodeWithText("Диктофон и записи лекций").assertDoesNotExist()
        compose.onNodeWithText("Готово").performClick()
        compose.runOnUiThread { ViewModelProvider(compose.activity)[EditorViewModel::class.java].createBoard() }
        compose.onNodeWithText("Исходный вид").assertIsDisplayed()
        compose.onNodeWithText("+ Лист").assertDoesNotExist()
        compose.onNodeWithText("Лист целиком").assertDoesNotExist()
        compose.onNodeWithText("Страницы").assertDoesNotExist()
        compose.onNodeWithContentDescription("Действия с документом").performClick()
        compose.onNodeWithText("Экспорт текущей страницы в PDF").assertDoesNotExist()
        compose.onNodeWithText("Сохранить резервную копию с аудио").assertDoesNotExist()
    }
}
