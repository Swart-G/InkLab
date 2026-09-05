package dev.swart.inklab

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun editorPagesAndSettingsAreReachable() {
        compose.onNodeWithContentDescription("Страницы").performClick()
        compose.onNodeWithText("+ Лист").performClick()
        compose.onNodeWithText("Лист 2").assertExists()
        compose.onNodeWithContentDescription("Страницы").performClick()
        compose.onNodeWithText("Фокус").performClick()
        compose.onNodeWithText("Панели").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Действия с документом").performClick()
        compose.onNodeWithText("Диктофон и записи лекций").assertExists()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Отмена двумя пальцами"))
        compose.onNodeWithText("Отмена двумя пальцами").assertExists()
    }
}
