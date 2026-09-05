package dev.swart.inklab.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.swart.inklab.AppContainer
import dev.swart.inklab.core.model.*
import dev.swart.inklab.core.export.*
import dev.swart.inklab.core.recognition.ProviderState
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.*
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun WorkspaceDialogs(vm: EditorViewModel) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDeletion by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exportIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var exportingBoard by remember { mutableStateOf<InkBoard?>(null) }
    fun job(block: suspend () -> String) {
        if(busy) return
        busy=true
        scope.launch { message=runCatching { block() }.getOrElse { it.message ?: "Не удалось выполнить действие" }; busy=false }
    }
    val pdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val board=exportingBoard
        if(uri!=null && board!=null) job { withContext(Dispatchers.IO) { DocumentTransfer(context).pdf(board,exportIndices,uri) }; "PDF сохранён" }
    }
    val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null) {
            val boards=vm.allDocuments(); val folders=vm.folders.toList()
            job { withContext(Dispatchers.IO) { DocumentTransfer(context).backup(boards,folders,uri) }; "Резервная копия сохранена" }
        }
    }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) job {
            val imported=withContext(Dispatchers.IO) { DocumentTransfer(context).restore(uri) }
            vm.folders.addAll(imported.folders); vm.importDocuments(imported.boards); vm.restorePreferences(imported.preferences)
            "Восстановлено документов: ${imported.boards.size}. Они добавлены как копии."
        }
    }
    if(vm.libraryTools) Sheet("Документ и библиотека",{ vm.libraryTools=false }) {
        val board=vm.currentBoard
        if(board!=null) {
            Action("Языки распознавания · ${Locale.forLanguageTag(board.languageTag).getDisplayName(Locale.forLanguageTag("ru"))}") { vm.libraryTools=false; vm.languagePanel=true }
            Action("Диктофон и записи лекций") { vm.libraryTools=false; vm.audioPanel=true }
            Action(if(board.favorite) "Убрать из избранного" else "Добавить в избранное") { vm.toggleFavorite(board.id) }
            Text("Переместить в папку",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick={vm.moveDocument(board.id,null)}) { Text("Все файлы") }
                vm.folders.forEach { folder -> TextButton(onClick={vm.moveDocument(board.id,folder.id)}) { Text(folder.title) } }
            }
            Action("Экспорт всей тетради в PDF") { exportingBoard=vm.allDocuments().first { it.id==board.id }; exportIndices=board.pages.indices.toList(); pdf.launch("${board.title}.pdf") }
            Action("Экспорт текущей страницы в PDF") { exportingBoard=vm.allDocuments().first { it.id==board.id }; exportIndices=listOf(vm.currentPageIndex); pdf.launch("${board.title}-${vm.currentPageIndex+1}.pdf") }
        }
        HorizontalDivider()
        Action("Сохранить резервную копию с аудио") { backup.launch("InkLab-backup.zip") }
        Action("Восстановить из резервной копии") { restore.launch(arrayOf("application/zip","application/octet-stream")) }
        val deleted=vm.boards.filter { it.deletedAt!=null }
        Text("Корзина · ${deleted.size}",style=MaterialTheme.typography.titleMedium)
        deleted.forEach { item ->
            Text(item.title)
            Row {
                TextButton(onClick={vm.restoreDocument(item.id)}) { Text("Восстановить") }
                TextButton(onClick={ message="Удалить навсегда: ${item.title}"; pendingDeletion=item.id }) { Text("Удалить навсегда") }
            }
        }
        board?.trashedPages?.forEachIndexed { index,page ->
            Row { TextButton(onClick={vm.restorePage(page.id)}) {Text("Восстановить лист ${index+1}")}; TextButton(onClick={message="Удалить лист навсегда?";pendingDeletion="page:${page.id}"}) {Text("Удалить")} }
        }
        if(deleted.isNotEmpty() || vm.boards.any {it.trashedPages.isNotEmpty()}) Action("Очистить корзину") {message="Удалить все документы и страницы из корзины навсегда?";pendingDeletion="all"}
        if(deleted.isEmpty() && board?.trashedPages?.isEmpty()!=false) Text("Корзина пуста",color=InkColors.Muted)
    }
    if(vm.pageManager || (vm.pagePanel && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 900)) PagePanel(vm)
    if(vm.languagePanel) LanguagesPanel(vm)
    if(vm.audioPanel) AudioPanel(vm)
    if(busy) AlertDialog(onDismissRequest={},title={ Text("Выполняется…") },text={ CircularProgressIndicator() },confirmButton={})
    val error=vm.storageError
    if(error!=null) AlertDialog(onDismissRequest={}, title={Text("Данные требуют внимания")},text={Text(error)},confirmButton={TextButton(onClick={vm.libraryTools=true; vm.storageError=null}) {Text("Резервные копии")}})
    message?.let { text -> AlertDialog(onDismissRequest={message=null; pendingDeletion=null},text={Text(text)},confirmButton={TextButton(onClick={
        pendingDeletion?.let { id ->
            val store=dev.swart.inklab.audio.AudioStore(context)
            val deletedIds=if(id=="all") vm.boards.filter {it.deletedAt!=null}.map {it.id}.toSet() else setOf(id)
            if(store.list().any { it.boardId in deletedIds && it.id==dev.swart.inklab.audio.AudioHub.activeId }) { message="Сначала остановите запись"; pendingDeletion=null; return@TextButton }
            store.list().filter { it.boardId in deletedIds }.forEach(store::delete)
            when {id=="all" -> vm.emptyTrash();id.startsWith("page:")->vm.permanentlyDeletePage(id.removePrefix("page:"));else->vm.permanentlyDelete(id)}
        }
        pendingDeletion=null; message=null
    }) {Text(if(pendingDeletion==null) "Понятно" else "Удалить навсегда")}},dismissButton={ if(pendingDeletion!=null) TextButton(onClick={pendingDeletion=null;message=null}) {Text("Отмена")} }) }
}

@Composable
internal fun Sheet(title: String, close: () -> Unit, scrollable: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=640.dp).fillMaxWidth(0.95f).fillMaxHeight(0.9f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) { Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge); TextButton(onClick=close) { Text("Готово") } }
                Column(Modifier.weight(1f).then(if(scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
            }
        }
    }
}
@Composable internal fun Action(title: String, action: () -> Unit) { TextButton(onClick=action,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(title,Modifier.fillMaxWidth()) } }

@Composable
private fun PagePanel(vm: EditorViewModel) {
    val board=vm.currentBoard ?: return
    val rowHeight=with(LocalDensity.current) { 144.dp.toPx() }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    var exportBoard by remember { mutableStateOf<InkBoard?>(null) }; var indices by remember { mutableStateOf<List<Int>>(emptyList()) }; var error by remember { mutableStateOf<String?>(null) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source=exportBoard
        if(uri!=null && source!=null) scope.launch { error=runCatching { withContext(Dispatchers.IO) { DocumentTransfer(context).pdf(source,indices,uri) }; "PDF сохранён" }.getOrElse { it.message } }
    }
    Dialog(onDismissRequest={vm.pagePanel=false;vm.pageManager=false}) {
        Surface(shape=MaterialTheme.shapes.large) { Column(Modifier.fillMaxWidth().heightIn(max=700.dp).padding(16.dp)) {
            Row { Text("Страницы · ${board.pages.size}",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge); TextButton(onClick={vm.pagePanel=false;vm.pageManager=false}) {Text("Готово")} }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick=vm::addPage) {Text("+ Лист")}; TextButton(onClick=vm::duplicatePage) {Text("Копия")}; TextButton(onClick=vm::deleteCurrentPage) {Text("В корзину")}
            }
            Text("Нажмите лист для перехода. Удерживайте ≡ для перестановки.",style=MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f,false)) {
                itemsIndexed(board.pages,key={_,p->p.id}) { index,page ->
                    val currentIndex by rememberUpdatedState(index)
                    Row(Modifier.fillMaxWidth().height(144.dp).background(if(index==vm.currentPageIndex) InkColors.AccentSoft else Color.Transparent),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(index in selected,{ selected=if(it) selected+index else selected-index })
                        PageThumbnail(page,board.settings,Modifier.width(72.dp).height(110.dp).clickable { vm.openPage(index) })
                        Column(Modifier.weight(1f).padding(8.dp)) { Text("Лист ${index+1}"); TextButton(onClick={vm.openPage(index);vm.pagePanel=false;vm.pageManager=false}) {Text("Открыть")} }
                        Text("≡",Modifier.padding(12.dp).pointerInput(page.id) {
                            var distance=0f
                            detectDragGesturesAfterLongPress(onDragStart={vm.activatePage(currentIndex);distance=0f},onDrag={change,delta ->
                                change.consume(); distance+=delta.y
                                if(kotlin.math.abs(distance)>rowHeight) { vm.movePage(if(distance>0) 1 else -1);distance=0f; selected=emptySet() }
                            })
                        })
                    }
                }
            }
            if(selected.isNotEmpty()) TextButton(onClick={exportBoard=vm.allDocuments().first {it.id==board.id}; indices=selected.filter {it in board.pages.indices}.sorted();export.launch("${board.title}-страницы.pdf")}) {Text("Экспортировать выбранные (${selected.size})")}
            error?.let {Text(it)}
        } }
    }
}

@Composable
internal fun PageThumbnail(page: InkPage, settings: BoardSettings, modifier: Modifier=Modifier) {
    val palette=LocalInkPalette.current
    val night=palette.dark && dev.swart.inklab.ui.theme.LocalNightPaper.current
    val displayPaper=paperDisplayColor(Color(settings.paperColor),night)
    val displayPage=remember(page,night,displayPaper) { page.copy(strokes=page.strokes.map {it.copy(color=inkDisplayColor(it.color,displayPaper,night))},convertedObjects=page.convertedObjects.map {it.copy(color=inkDisplayColor(it.color,displayPaper,night))}) }
    val bitmap = remember(displayPage, settings) {
        android.graphics.Bitmap.createBitmap(160, 226, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = android.graphics.Canvas(bitmap)
            val scale = minOf(160f / page.width, 226f / page.height)
            canvas.scale(scale, scale); canvas.clipRect(0f,0f,page.width,page.height)
            PageRenderer.draw(canvas, displayPage, settings.copy(paperColor=displayPaper.toArgb().toLong() and 0xffffffffL))
        }
    }
    Canvas(modifier) {
        drawContext.canvas.nativeCanvas.drawBitmap(bitmap, null, android.graphics.RectF(0f,0f,size.width,size.height),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    }
}

private fun languageTags(): List<String> = DigitalInkRecognitionModelIdentifier::class.java.fields
    .filter { it.type==DigitalInkRecognitionModelIdentifier::class.java }
    .mapNotNull { runCatching { (it.get(null) as DigitalInkRecognitionModelIdentifier).languageTag }.getOrNull() }
    .filter { !it.contains("gesture") && !it.startsWith("zxx") && !it.startsWith("und") }
    .distinct().sortedWith(compareBy<String> { when(it) { "ru-RU","ru"->0; "en-US"->1; else->2 } }.thenBy { Locale.forLanguageTag(it).getDisplayName(Locale.forLanguageTag("ru")) })

@Composable
internal fun LanguagesPanel(vm: EditorViewModel) {
    val context=LocalContext.current
    var query by remember { mutableStateOf("") }
    val tags=remember { (listOf("ru-RU","en-US")+languageTags()).distinctBy { DigitalInkRecognitionModelIdentifier.fromLanguageTag(it)?.languageTag ?: it } }
    Sheet("Языки распознавания",{vm.languagePanel=false},scrollable=false) {
        OutlinedTextField(query,{query=it},label={Text("Найти язык")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Row(verticalAlignment=Alignment.CenterVertically) { Text("Загружать только по Wi-Fi",Modifier.weight(1f)); Switch(vm.inputPreferences.wifiOnlyModels,{vm.updateInputPreferences(vm.inputPreferences.copy(wifiOnlyModels=it))}) }
        Text("После загрузки распознавание работает без интернета. Размер одного языка — примерно 20 МБ.",style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) { items(tags.filter { it.contains(query,true) || Locale.forLanguageTag(it).getDisplayName(Locale.forLanguageTag("ru")).contains(query,true) },key={it}) { tag ->
            val provider=remember(tag) { AppContainer.recognitionRegistry.language(tag) }
            LaunchedEffect(provider) { runCatching { provider.refresh() }.onFailure {vm.modelErrors[provider.id]=it.message ?: "Не удалось проверить модель"} }
            val loading=provider.id in vm.modelProgress
            val ready=provider.state(context)==ProviderState.READY
            Column(Modifier.fillMaxWidth().padding(vertical=6.dp)) {
                Text(provider.subtitle,style=MaterialTheme.typography.titleSmall)
                Text(if(ready) "Установлен · $tag" else tag,style=MaterialTheme.typography.bodySmall,color=InkColors.Muted)
                if(loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Выполняется… При загрузке только по Wi-Fi ожидается подходящая сеть.",style=MaterialTheme.typography.bodySmall); if(vm.inputPreferences.wifiOnlyModels) TextButton(onClick={vm.prepareProvider(context,provider.id,false)}) {Text("Разрешить текущую сеть")} }
                else Row {
                    if(ready) {
                        TextButton(onClick={vm.setLanguage(tag)}) {Text(if(vm.currentBoard?.languageTag?.let {AppContainer.recognitionRegistry.language(it).id}==provider.id) "✓ Выбран" else "Выбрать")}
                        TextButton(onClick={vm.removeProvider(context,provider.id)}) {Text("Удалить")}
                    } else TextButton(onClick={vm.prepareProvider(context,provider.id)}) {Text("Загрузить")}
                }
                vm.modelErrors[provider.id]?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
            }
        }
    }
}
}

@Composable
internal fun InlinePageRail(vm: EditorViewModel) {
    val board = vm.currentBoard ?: return
    Surface(Modifier.width(184.dp).fillMaxHeight(), color = InkColors.PaperRaised) {
        Column(Modifier.padding(10.dp)) {
            Row { Text("Страницы", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onClick = {vm.pagePanel=false}) {Text("×")} }
            TextButton(onClick={vm.pageManager=true}) {Text("Управление")}
            Row { TextButton(onClick=vm::addPage) {Text("+ Лист")}; TextButton(onClick=vm::duplicatePage) {Text("Копия")} }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(board.pages, key={_,p->p.id}) { index,page ->
                    Column(Modifier.fillMaxWidth().background(if(index==vm.currentPageIndex) InkColors.AccentSoft else Color.Transparent).clickable {vm.openPage(index)}.padding(10.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                        PageThumbnail(page,board.settings,Modifier.width(112.dp).height(158.dp))
                        Text("Лист ${index+1}",style=MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.Center) {
                IconButton(onClick={vm.movePage(-1)},enabled=vm.currentPageIndex>0) {Icon(Icons.Outlined.ArrowUpward,"Переместить лист выше")}
                IconButton(onClick={vm.movePage(1)},enabled=vm.currentPageIndex<board.pages.lastIndex) {Icon(Icons.Outlined.ArrowDownward,"Переместить лист ниже")}
                IconButton(onClick=vm::deleteCurrentPage) {Icon(Icons.Outlined.DeleteOutline,"Удалить лист")}
            }
        }
    }
}
