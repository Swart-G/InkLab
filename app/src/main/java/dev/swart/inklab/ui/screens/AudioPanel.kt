package dev.swart.inklab.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.swart.inklab.audio.*
import dev.swart.inklab.ui.EditorViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

@Composable
internal fun AudioPanel(vm: EditorViewModel) {
    val context=LocalContext.current
    val store=remember { AudioStore(context) }
    val scope=rememberCoroutineScope()
    val player=remember {AudioPlayer(context)}
    DisposableEffect(player) { onDispose {player.release()} }
    LaunchedEffect(Unit) {store.recover()}
    var notice by remember {mutableStateOf<String?>(null)}
    var markerTitle by remember {mutableStateOf("")}
    var position by remember {mutableLongStateOf(0)}
    var renameId by remember {mutableStateOf<String?>(null)}
    var renameTitle by remember {mutableStateOf("")}
    var deleteId by remember {mutableStateOf<String?>(null)}
    var sharing by remember {mutableStateOf(false)}
    val revision=AudioHub.revision
    val notes=remember(revision,vm.currentBoardId) {store.list().filter {it.boardId==vm.currentBoardId}}
    val active=notes.firstOrNull {it.id==AudioHub.activeId}
    fun start() {
        player.release()
        val board=vm.currentBoard ?: return
        runCatching {recordingCommand(context,"start",board.id,board.pages[vm.currentPageIndex].id,board.title)}.onFailure {notice=it.message}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(grants[Manifest.permission.RECORD_AUDIO]==true || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) start()
        else notice="Для записи нужен доступ к микрофону. Его можно разрешить в настройках приложения."
    }
    LaunchedEffect(player) {while(true) {position=player.position;delay(250)}}
    Sheet("Диктофон",{vm.audioPanel=false}) {
        if(AudioHub.activeId==null) {
            Button(onClick={
                if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(if(android.os.Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
            }) {Text("● Записать лекцию")}
            Text("Запись продолжается при выключенном экране. Закладки связывают звук со страницами конспекта.")
        } else {
            Text("${if(AudioHub.paused) "Пауза" else "● Идёт запись"} · ${audioTime(AudioHub.elapsed)}",style=MaterialTheme.typography.titleLarge)
            if(active==null) Text("Запись принадлежит другому блокноту.")
            Row {
                TextButton(onClick={recordingCommand(context,if(AudioHub.paused) "resume" else "pause")}) {Text(if(AudioHub.paused) "Продолжить" else "Пауза")}
                TextButton(onClick={recordingCommand(context,"stop")}) {Text("Остановить")}
            }
            if(active!=null) {
                OutlinedTextField(markerTitle,{markerTitle=it},label={Text("Название закладки")},singleLine=true,modifier=Modifier.fillMaxWidth())
                TextButton(onClick={recordingCommand(context,"mark",pageId=vm.currentBoard?.pages?.get(vm.currentPageIndex)?.id,title=markerTitle.ifBlank {"Лист ${vm.currentPageIndex+1}"});markerTitle=""}) {Text("+ Закладка на текущем листе")}
            }
        }
        AudioHub.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        notice?.let {Text(it)}
        player.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        player.note?.let {note ->
            HorizontalDivider(); Text(note.title,style=MaterialTheme.typography.titleMedium)
            Text("${audioTime(position)} / ${audioTime(note.durationMs)}")
            Slider(position.toFloat().coerceIn(0f,note.durationMs.toFloat().coerceAtLeast(1f)),{player.seek(it.toLong())},valueRange=0f..note.durationMs.toFloat().coerceAtLeast(1f))
            Row {TextButton(onClick={player.seek(position-10000)}) {Text("−10 с")};TextButton(onClick=player::toggle) {Text(if(player.playing) "Пауза" else "Слушать")};TextButton(onClick={player.seek(position+10000)}) {Text("+10 с")}}
            Row {listOf(0.5f,1f,1.5f,2f).forEach {speed -> FilterChip(player.speed==speed,{player.changeSpeed(speed)},label={Text("${speed}×")})}}
        }
        if(notes.isEmpty()) Text("Здесь появятся записи этого блокнота.")
        notes.forEach {note ->
            HorizontalDivider()
            Text(note.title,style=MaterialTheme.typography.titleMedium)
            Text("${audioTime(note.durationMs)} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(note.createdAt))}",style=MaterialTheme.typography.bodySmall)
            note.error?.let {Text(it)}
            if(note.id!=AudioHub.activeId) Row {
                TextButton(enabled=note.segments.isNotEmpty() && AudioHub.activeId==null,onClick={player.open(note)}) {Text("Слушать")}
                TextButton(onClick={renameId=note.id;renameTitle=note.title}) {Text("Имя")}
                TextButton(enabled=!sharing && note.segments.isNotEmpty(),onClick={
                    sharing=true
                    scope.launch {
                        runCatching {
                            val file=withContext(Dispatchers.IO) {File(context.cacheDir,"exports").mkdirs();File(context.cacheDir,"exports/${note.id}.m4a").also {store.export(note,it)}}
                            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Поделиться записью"))
                        }.onFailure {notice=it.message};sharing=false
                    }
                }) {Text("Поделиться")}
                TextButton(onClick={deleteId=note.id}) {Text("Удалить")}
            }
            note.markers.forEach {marker ->
                TextButton(onClick={
                    val page=vm.currentBoard?.pages?.indexOfFirst {it.id==marker.pageId} ?: -1
                    if(page>=0) vm.openPage(page) else notice="Страница находится в корзине или удалена. Аудиозакладка сохранена."
                    if(note.id!=AudioHub.activeId && AudioHub.activeId==null) player.open(note,marker.timeMs)
                }) {Text("${audioTime(marker.timeMs)} · ${marker.title}")}
            }
        }
    }
    if(renameId!=null) AlertDialog(onDismissRequest={renameId=null},title={Text("Название записи")},text={OutlinedTextField(renameTitle,{renameTitle=it})},confirmButton={TextButton(enabled=renameTitle.isNotBlank(),onClick={notes.firstOrNull {it.id==renameId}?.let {store.save(it.copy(title=renameTitle.trim()))};renameId=null}) {Text("Сохранить")}})
    if(deleteId!=null) AlertDialog(onDismissRequest={deleteId=null},title={Text("Удалить аудиозапись навсегда?")},text={Text("Страницы конспекта сохранятся.")},confirmButton={TextButton(onClick={notes.firstOrNull {it.id==deleteId}?.let {if(player.note?.id==it.id) player.release();store.delete(it)};deleteId=null}) {Text("Удалить")}},dismissButton={TextButton(onClick={deleteId=null}) {Text("Отмена")}})
}
private fun audioTime(ms: Long) = "%d:%02d".format(ms/60000,(ms/1000)%60)
