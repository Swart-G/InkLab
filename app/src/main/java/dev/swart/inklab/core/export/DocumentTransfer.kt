package dev.swart.inklab.core.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import dev.swart.inklab.audio.*
import dev.swart.inklab.core.model.*
import dev.swart.inklab.core.storage.BoardRepository
import org.json.*
import java.io.File
import java.util.UUID
import java.util.zip.*
import ru.noties.jlatexmath.JLatexMathDrawable

object PageRenderer {
    fun draw(canvas: Canvas, page: InkPage, settings: BoardSettings) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(settings.paperColor.toInt())
        canvas.save(); canvas.translate(-page.originX,-page.originY)
        val left=page.originX; val top=page.originY; val right=left+page.width; val bottom=top+page.height
        paint.color=if(android.graphics.Color.luminance(settings.paperColor.toInt()) < 0.4f) 0xFF565860.toInt() else 0xFFC9C5BC.toInt()
        paint.strokeWidth=0.8f
        val spacing=settings.spacing.coerceAtLeast(12f)
        if(settings.pattern!=PaperPattern.BLANK) {
            var y=kotlin.math.floor(top/spacing)*spacing
            while(y<bottom) {
                if(settings.pattern==PaperPattern.RULED || settings.pattern==PaperPattern.GRID) canvas.drawLine(left,y,right,y,paint)
                if(settings.pattern==PaperPattern.DOTS) { var x=kotlin.math.floor(left/spacing)*spacing; while(x<right) { canvas.drawCircle(x,y,1.2f,paint); x+=spacing } }
                y+=spacing
            }
            if(settings.pattern==PaperPattern.GRID) { var x=kotlin.math.floor(left/spacing)*spacing; while(x<right) { canvas.drawLine(x,top,x,bottom,paint); x+=spacing } }
        }
        if(settings.showMargin) { paint.color=0xFFDE9B9F.toInt(); canvas.drawLine(74f,top,74f,bottom,paint) }
        paint.strokeCap=Paint.Cap.ROUND
        page.strokes.forEach { stroke ->
            paint.color=stroke.color.toArgb()
            if(stroke.points.size==1) canvas.drawCircle(stroke.points[0].x,stroke.points[0].y,stroke.width/2,paint)
            stroke.points.zipWithNext().forEach { (a,b) -> paint.strokeWidth=stroke.width*(0.55f+((a.pressure+b.pressure)/2).coerceIn(0.15f,1f)*0.75f); canvas.drawLine(a.x,a.y,b.x,b.y,paint) }
        }
        page.convertedObjects.forEach { item ->
            canvas.save(); canvas.translate(item.x,item.y)
            val math=if(item.kind==ConvertedInkKind.MATH) runCatching { JLatexMathDrawable.builder(item.content).textSize(item.textSize).color(item.color.toArgb()).build() }.getOrNull() else null
            if(math!=null) {
                val fit=minOf(1f,item.width/math.intrinsicWidth.coerceAtLeast(1),item.height/math.intrinsicHeight.coerceAtLeast(1))
                canvas.scale(fit,fit); math.setBounds(0,0,math.intrinsicWidth,math.intrinsicHeight); math.draw(canvas)
            } else {
                val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=item.color.toArgb(); textSize=item.textSize; typeface=android.graphics.Typeface.create("cursive",android.graphics.Typeface.NORMAL) }
                StaticLayout.Builder.obtain(item.content,0,item.content.length,text,item.width.toInt().coerceAtLeast(1)).setIncludePad(false).setLineSpacing(0f,1.16f).build().draw(canvas)
            }
            canvas.restore()
        }
        canvas.restore()
    }
}

data class ImportedLibrary(val boards: List<InkBoard>,val folders: List<InkFolder>,val preferences: JSONObject?)

class DocumentTransfer(private val context: Context) {
    private val repository=BoardRepository(context)
    fun pdf(board: InkBoard, indices: List<Int>, uri: Uri) {
        val pdf=PdfDocument()
        try {
            indices.forEachIndexed { n,index ->
                var source=board.pages[index]
                if(board.format==DocumentFormat.BOARD) {
                    val points=source.strokes.flatMap { it.points }; val objects=source.convertedObjects
                    val left=minOf(points.minOfOrNull { it.x } ?: 0f,objects.minOfOrNull { it.x } ?: 0f)-24
                    val top=minOf(points.minOfOrNull { it.y } ?: 0f,objects.minOfOrNull { it.y } ?: 0f)-24
                    val right=maxOf(points.maxOfOrNull { it.x } ?: 100f,objects.maxOfOrNull { it.x+it.width } ?: 100f)+24
                    val bottom=maxOf(points.maxOfOrNull { it.y } ?: 100f,objects.maxOfOrNull { it.y+it.height } ?: 100f)+24
                    source=source.copy(originX=left,originY=top,width=right-left,height=bottom-top)
                }
                val width=if(source.width>source.height) 842 else 595
                val height=if(board.format==DocumentFormat.BOARD) (width*source.height/source.width).toInt().coerceIn(72,14400) else if(width==842) 595 else 842
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(width,height,n+1).create())
                page.canvas.save(); val scale=minOf(width/source.width,height/source.height); page.canvas.scale(scale,scale)
                page.canvas.clipRect(0f,0f,source.width,source.height)
                PageRenderer.draw(page.canvas,source,board.settings); page.canvas.restore(); pdf.finishPage(page)
            }
            context.contentResolver.openOutputStream(uri,"wt")!!.use(pdf::writeTo)
        } finally { pdf.close() }
    }
    fun backup(boards: List<InkBoard>, folders: List<InkFolder>, uri: Uri) {
        check(AudioHub.activeId==null) { "Остановите запись перед созданием резервной копии" }
        val store=AudioStore(context)
        val folderJson=JSONArray().apply { folders.forEach { put(JSONObject().put("id",it.id).put("title",it.title).put("parentId",it.parentId ?: JSONObject.NULL)) } }
        val prefs=JSONObject(context.getSharedPreferences("input_preferences",Context.MODE_PRIVATE).all)
        val manifest=JSONObject().put("version",2).put("boards",JSONArray(repository.encode(boards))).put("folders",folderJson).put("preferences",prefs)
        context.contentResolver.openOutputStream(uri,"wt")!!.use { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("library.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            store.list().filter { note -> boards.any { it.id==note.boardId } }.forEach { note ->
                zip.putNextEntry(ZipEntry("audio/${note.id}.json")); zip.write(store.encode(note).toString().toByteArray()); zip.closeEntry()
                note.segments.forEach { segment -> zip.putNextEntry(ZipEntry("audio/${segment.file}")); store.file(segment.file).inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        } }
    }
    fun restore(uri: Uri): ImportedLibrary {
        val stage=File(context.cacheDir,"restore-${UUID.randomUUID()}").apply { mkdirs() }
        val installed=mutableListOf<File>()
        try {
            var bytes=0L; var count=0
            context.contentResolver.openInputStream(uri)!!.use { input -> ZipInputStream(input).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(++count<=10000) { "Слишком много файлов в архиве" }
                    require(entry.name=="library.json" || entry.name.matches(Regex("audio/[a-zA-Z0-9.-]+\\.(json|m4a)"))) { "Недопустимый путь в архиве" }
                    val file=File(stage,entry.name); require(!file.exists()); file.parentFile!!.mkdirs()
                    file.outputStream().use { out ->
                        val buffer=ByteArray(32768)
                        while(true) { val n=zip.read(buffer); if(n<0) break; bytes+=n; require(bytes<2L*1024*1024*1024 && stage.usableSpace>10*1024*1024) { "Архив слишком большой или недостаточно места" }; out.write(buffer,0,n) }
                    }
                }
            } }
            val manifest=JSONObject(File(stage,"library.json").readText()); require(manifest.getInt("version")==2) { "Неподдерживаемая версия архива" }
            val old=repository.decode(manifest.getJSONArray("boards").toString())
            val ids=mutableMapOf<String,String>(); fun newId(id:String)=ids.getOrPut(id) { UUID.randomUUID().toString() }
            val foldersJson=manifest.optJSONArray("folders") ?: JSONArray()
            val folders=List(foldersJson.length()) { foldersJson.getJSONObject(it).let { f -> InkFolder(id=newId(f.getString("id")),title=f.getString("title"),parentId=if(f.isNull("parentId")) null else newId(f.getString("parentId"))) } }
            require(folders.all { it.parentId==null || folders.any { parent -> parent.id==it.parentId } })
            folders.forEach { start -> var item:InkFolder?=start; val seen=mutableSetOf<String>(); while(item!=null) { require(seen.add(item.id)) { "Цикл папок в архиве" }; val parent=item.parentId; item=folders.firstOrNull { it.id==parent } } }
            val boards=old.map { board ->
                fun copyPage(page: InkPage)=page.copy(id=newId(page.id),strokes=page.strokes.map { it.copy(id=newId(it.id)) },convertedObjects=page.convertedObjects.map { it.copy(id=newId(it.id),sourceStrokes=it.sourceStrokes.map { stroke -> stroke.copy(id=newId(stroke.id)) }) })
                board.copy(id=newId(board.id),title=board.title+" (копия)",folderId=board.folderId?.let(::newId),pages=board.pages.map(::copyPage),trashedPages=board.trashedPages.map(::copyPage))
            }
            val store=AudioStore(context)
            val audio=File(stage,"audio").listFiles()?.filter { it.extension=="json" }?.map { file ->
                val note=store.decode(JSONObject(file.readText())); require(old.any { it.id==note.boardId })
                note.segments.forEach { require(it.durationMs>=0); require(it.file.matches(Regex("[a-zA-Z0-9.-]+\\.m4a"))); require(File(stage,"audio/${it.file}").isFile) }
                note
            } ?: emptyList()
            audio.forEach { note ->
                val segments=note.segments.map { segment -> val name="${UUID.randomUUID()}.m4a"; val target=store.file(name); installed+=target; File(stage,"audio/${segment.file}").copyTo(target); segment.copy(file=name) }
                val id=newId(note.id); installed+=store.file("$id.json")
                store.save(note.copy(id=id,boardId=newId(note.boardId),segments=segments,markers=note.markers.map { it.copy(pageId=newId(it.pageId)) }))
            }
            return ImportedLibrary(boards,folders,manifest.optJSONObject("preferences"))
        } catch(e: Throwable) { installed.forEach { it.delete() }; throw e }
        finally { stage.deleteRecursively() }
    }
}
