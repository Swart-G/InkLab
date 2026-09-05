package dev.swart.inklab

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.swart.inklab.core.model.*
import dev.swart.inklab.core.storage.*
import dev.swart.inklab.core.export.*
import dev.swart.inklab.core.recognition.*
import dev.swart.inklab.ui.*
import dev.swart.inklab.audio.*
import dev.swart.inklab.core.input.CanvasInputController
import android.view.MotionEvent
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class RepositoryAndEditorTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestApplication
    private val stores=mutableListOf<ViewModelStore>()
    @Before fun setup() {app=TestApplication(instrumentation.targetContext)}
    @After fun cleanup() {instrumentation.runOnMainSync {stores.forEach {it.clear()}};app.filesDir.deleteRecursively()}
    private fun main(block: () -> Unit) { instrumentation.runOnMainSync(block) }
    private fun vm():EditorViewModel=EditorViewModel(app).also { val store=ViewModelStore();store.put("vm",it);stores+=store }
    private fun stroke()=InkStroke(points=listOf(InkPoint(100f,100f,1),InkPoint(160f,100f,2)))

    @Test fun libraryNavigationAndLockedPrimaryColor()=main {
        val vm=vm()
        assertEquals(AppScreen.BOARDS, vm.screen)
        vm.navigate(AppScreen.SETTINGS)
        assertEquals(AppScreen.BOARDS, vm.settingsOrigin)
        vm.chooseQuickPenColor(0)
        val primary=vm.penColor
        vm.updateQuickPenColor(0, androidx.compose.ui.graphics.Color.Red)
        assertEquals(primary,vm.penColor)
        vm.updateInputPreferences(vm.inputPreferences.copy(darkTheme=true))
        assertEquals(primary,vm.penColor)
        vm.createBoard()
        val boardId=vm.currentBoardId
        vm.addPage()
        assertEquals(1,vm.currentBoard!!.pages.size)
        vm.navigate(AppScreen.BOARDS)
        vm.deleteBoard(boardId)
        assertEquals(AppScreen.BOARDS,vm.screen)
    }

    @Test fun pageTrashCanRestoreAnotherNotebookWithoutOpeningIt()=main {
        val vm=vm()
        val notebook=vm.currentBoardId
        val page=vm.currentBoard!!.pages.first()
        vm.deleteCurrentPage()
        vm.createBoard()
        val board=vm.currentBoardId
        vm.navigate(AppScreen.BOARDS)
        vm.restorePage(page.id)
        assertEquals(board,vm.currentBoardId)
        assertEquals(AppScreen.BOARDS,vm.screen)
        assertTrue(vm.boards.first {it.id==notebook}.pages.any {it.id==page.id})
        assertTrue(vm.boards.first {it.id==notebook}.trashedPages.isEmpty())
    }

    @Test fun legacyMigrationPreservesCoordinatesAndBackup() {
        val source="""[{"id":"legacy","title":"old","format":"NOTEBOOK","pages":[{"id":"page","strokes":[{"points":[[-50,20,1],[2500,2800,2]]}]}]}]"""
        File(app.filesDir,"boards.json").writeText(source)
        val repo=BoardRepository(app);val board=repo.load().single();val page=board.pages.single()
        assertEquals(-50f,page.strokes.single().points.first().x)
        assertTrue(page.originX < -50f);assertTrue(page.originX+page.width > 2500f);assertTrue(page.originY+page.height > 2800f)
        assertEquals(source,File(app.filesDir,"migration-v1/boards.json").readText())
        repo.save(listOf(board));assertEquals(board,BoardRepository(app).load().single())
    }
    @Test fun damagedStorageCannotBeOverwritten() {
        val file=File(app.filesDir,"boards.json");file.writeText("broken")
        val repo=BoardRepository(app);assertTrue(repo.load().isEmpty());assertNotNull(repo.loadError)
        assertTrue(runCatching {repo.save(emptyList())}.isFailure);assertEquals("broken",file.readText())
    }
    @Test fun separateFilesRoundTripAndDelete() {
        val a=InkBoard(title="A",format=DocumentFormat.NOTEBOOK,languageTag="en-US",favorite=true,trashedPages=listOf(InkPage(strokes=listOf(stroke()))))
        val b=InkBoard(title="B",deletedAt=100)
        val repo=BoardRepository(app);repo.save(listOf(a,b));assertEquals(listOf(a,b),repo.load())
        repo.save(listOf(a));assertFalse(File(app.filesDir,"documents/${b.id}.json").exists());assertEquals(listOf(a),repo.load())
    }
    @Test fun viewportMappingSurvivesZoomRotationAndPageChange()=main {
        val vm=vm();vm.resizeViewport(1200f,800f);vm.addPage()
        val point=Offset(230f,410f)
        vm.zoomBy(1.8f,Offset(600f,400f));val screen=vm.canvasToScreen(point);val mapped=vm.screenToCanvas(screen)
        assertEquals(point.x,mapped.x,0.001f);assertEquals(point.y,mapped.y,0.001f)
        vm.resizeViewport(800f,1200f);val next=vm.screenToCanvas(vm.canvasToScreen(point));assertEquals(point.x,next.x,0.001f)
        assertEquals(1,vm.pageAt(vm.canvasToScreen(Offset(300f,300f))))
        vm.openPage(0);assertEquals(0,vm.currentPageIndex)
    }
    @Test fun documentUndoIncludesPageOperationsAndCancellation()=main {
        val vm=vm();vm.startStroke(InkPoint(100f,100f,1));vm.addPoint(InkPoint(160f,100f,2));vm.finishStroke()
        val first=vm.strokes.toList();vm.addPage();assertEquals(2,vm.currentBoard!!.pages.size)
        vm.undo();assertEquals(1,vm.currentBoard!!.pages.size);assertEquals(first,vm.strokes.toList())
        vm.beginInput();vm.beginErase();vm.eraseAt(Offset(130f,100f));vm.cancelInput();assertEquals(first,vm.strokes.toList())
        vm.undo();assertTrue(vm.strokes.isEmpty());vm.redo();assertEquals(first,vm.strokes.toList())
    }
    @Test fun staleRecognitionNeverReplacesEditedInk()=main {
        val vm=vm();vm.startStroke(InkPoint(100f,100f,1));vm.addPoint(InkPoint(160f,100f,2));vm.finishStroke()
        val original=vm.strokes.toList();vm.recognition=UiRecognition(RecognitionMode.TEXT,result=RecognitionResult("text",latencyMs=1,providerId="test"),sourceIds=original.map{it.id}.toSet(),documentId=vm.currentBoardId,pageId=vm.currentBoard!!.pages[0].id,originalStrokes=original)
        vm.strokes[0]=original[0].copy(width=15f);vm.applyRecognition()
        assertNotNull(vm.recognition?.error);assertTrue(vm.convertedObjects.isEmpty());assertEquals(15f,vm.strokes[0].width)
    }
    @Test fun archiveRoundTripWithAudioAndPdf() {
        val page=InkPage(strokes=listOf(stroke()));val board=InkBoard(format=DocumentFormat.NOTEBOOK,pages=listOf(page),languageTag="en-US")
        val audio=AudioStore(app);audio.file("sample.m4a").writeBytes(byteArrayOf(1,2,3))
        audio.save(AudioNote(boardId=board.id,segments=listOf(AudioSegment("sample.m4a",1000)),markers=listOf(AudioMarker(page.id,500,"mark")),status="complete"))
        val archive=File(app.cacheDir,"test-${UUID.randomUUID()}.zip")
        val transfer=DocumentTransfer(app);transfer.backup(listOf(board),emptyList(),Uri.fromFile(archive))
        val restored=transfer.restore(Uri.fromFile(archive)).boards.single()
        assertNotEquals(board.id,restored.id);assertNotEquals(page.id,restored.pages[0].id);assertEquals(page.strokes[0].points,restored.pages[0].strokes[0].points)
        val note=audio.list().single {it.boardId==restored.id};assertEquals(restored.pages[0].id,note.markers[0].pageId);assertArrayEquals(byteArrayOf(1,2,3),audio.file(note.segments[0].file).readBytes())
        val pdf=File(app.cacheDir,"test-${UUID.randomUUID()}.pdf");transfer.pdf(restored,listOf(0),Uri.fromFile(pdf))
        assertTrue(pdf.readBytes().take(4).toByteArray().decodeToString()=="%PDF")
        archive.delete();pdf.delete()
    }
    @Test fun archiveRejectsTraversalBeforeInstalling() {
        val archive=File(app.cacheDir,"bad-${UUID.randomUUID()}.zip")
        ZipOutputStream(archive.outputStream()).use { it.putNextEntry(ZipEntry("../outside.json"));it.write(byteArrayOf(1));it.closeEntry() }
        assertTrue(runCatching {DocumentTransfer(app).restore(Uri.fromFile(archive))}.isFailure)
        assertTrue(AudioStore(app).list().isEmpty());archive.delete()
    }
    @Test fun nativeStylusCanStartAfterPalmAndCanceledStrokeRollsBack()=main {
        val vm=vm();vm.resizeViewport(1200f,800f)
        val input=CanvasInputController(vm,12f)
        val now=android.os.SystemClock.uptimeMillis()
        fun event(action:Int,time:Long,types:List<Int>,positions:List<Offset>,flags:Int=0) {
            val props=types.mapIndexed {i,type -> MotionEvent.PointerProperties().apply {id=i;toolType=type}}.toTypedArray()
            val coords=positions.map {p -> MotionEvent.PointerCoords().apply {x=p.x;y=p.y;pressure=0.5f;size=0.1f}}.toTypedArray()
            val e=MotionEvent.obtain(now,time,action,types.size,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,flags)
            input.event(e);e.recycle()
        }
        val touch=MotionEvent.TOOL_TYPE_FINGER;val pen=MotionEvent.TOOL_TYPE_STYLUS
        event(MotionEvent.ACTION_DOWN,now,listOf(touch),listOf(Offset(100f,300f)))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),now+20,listOf(touch,pen),listOf(Offset(100f,300f),Offset(400f,200f)))
        event(MotionEvent.ACTION_MOVE,now+30,listOf(touch,pen),listOf(Offset(100f,300f),Offset(460f,200f)))
        assertTrue(vm.currentPoints.size>1)
        event(MotionEvent.ACTION_CANCEL,now+40,listOf(touch,pen),listOf(Offset(100f,300f),Offset(460f,200f)))
        assertTrue(vm.currentPoints.isEmpty());assertTrue(vm.strokes.isEmpty());assertFalse(vm.stylusInContact)
    }
}

private class TestApplication(private val source:Context):Application() {
    private val key=UUID.randomUUID().toString()
    private val directory=File(source.cacheDir,"fixture-$key").apply {mkdirs()}
    init {attachBaseContext(source)}
    override fun getFilesDir()=directory
    override fun getApplicationContext():Context=this
    override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=source.getSharedPreferences("$key-$name",mode)
}
