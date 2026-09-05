package dev.swart.inklab.audio

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.compose.runtime.*
import dev.swart.inklab.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

 data class AudioMarker(val pageId: String, val timeMs: Long, val title: String)
 data class AudioSegment(val file: String, val durationMs: Long)
 data class AudioNote(
    val id: String = UUID.randomUUID().toString(), val boardId: String, val title: String = "Запись лекции",
    val createdAt: Long = System.currentTimeMillis(), val segments: List<AudioSegment> = emptyList(),
    val markers: List<AudioMarker> = emptyList(), val status: String = "recording", val error: String? = null
) { val durationMs get() = segments.sumOf { it.durationMs } }

object AudioHub {
    var activeId by mutableStateOf<String?>(null)
    var paused by mutableStateOf(false)
    var elapsed by mutableLongStateOf(0L)
    var revision by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
}

class AudioStore(context: Context) {
    val directory = File(context.filesDir, "audio").apply { mkdirs() }
    fun file(name: String): File { require(name.matches(Regex("[a-zA-Z0-9.-]+"))); return File(directory,name) }
    fun list(): List<AudioNote> = directory.listFiles()?.filter { it.extension == "json" }?.mapNotNull {
        runCatching { decode(JSONObject(it.readText())) }.getOrNull()
    }?.sortedByDescending { it.createdAt } ?: emptyList()
    fun save(note: AudioNote) {
        val atomic = android.util.AtomicFile(file("${note.id}.json"))
        val out = atomic.startWrite()
        try { out.write(encode(note).toString().toByteArray()); atomic.finishWrite(out) }
        catch (e: Throwable) { atomic.failWrite(out); throw e }
        AudioHub.revision++
    }
    fun delete(note: AudioNote) { check(note.id != AudioHub.activeId); note.segments.forEach { file(it.file).delete() }; file("${note.id}.json").delete(); AudioHub.revision++ }
    fun recover() {
        if (AudioHub.activeId != null) return
        list().filter { it.status == "recording" }.forEach { note ->
            val known = note.segments.map { it.file }.toSet()
            val recovered = directory.listFiles()?.filter { it.name.startsWith("${note.id}-") && it.extension == "m4a" && it.name !in known }?.sortedBy { it.lastModified() }?.mapNotNull { file ->
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.path)
                    val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (duration > 0) AudioSegment(file.name,duration) else { file.delete(); null }
                } catch (_: Exception) { file.delete(); null } finally { reader.release() }
            } ?: emptyList()
            save(note.copy(segments = note.segments + recovered, status="interrupted",error="Запись прервана. Доступные фрагменты сохранены."))
        }
    }
    fun encode(note: AudioNote) = JSONObject().apply {
        put("id",note.id); put("boardId",note.boardId); put("title",note.title); put("createdAt",note.createdAt); put("status",note.status); put("error",note.error ?: JSONObject.NULL)
        put("segments",JSONArray().apply { note.segments.forEach { put(JSONObject().put("file",it.file).put("durationMs",it.durationMs)) } })
        put("markers",JSONArray().apply { note.markers.forEach { put(JSONObject().put("pageId",it.pageId).put("timeMs",it.timeMs).put("title",it.title)) } })
    }
    fun decode(o: JSONObject): AudioNote {
        val segments=o.getJSONArray("segments"); val markers=o.getJSONArray("markers")
        return AudioNote(o.getString("id"),o.getString("boardId"),o.getString("title"),o.getLong("createdAt"),
            List(segments.length()) { segments.getJSONObject(it).let { a -> AudioSegment(a.getString("file"),a.getLong("durationMs")) } },
            List(markers.length()) { markers.getJSONObject(it).let { a -> AudioMarker(a.getString("pageId"),a.getLong("timeMs"),a.getString("title")) } },
            o.getString("status"), if(o.isNull("error")) null else o.getString("error"))
    }
    fun export(note: AudioNote, destination: File) {
        require(note.segments.isNotEmpty()) { "Нет завершённых фрагментов записи" }
        val muxer=MediaMuxer(destination.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track=-1; var offset=0L; var started=false
        try {
            note.segments.forEach { segment ->
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(file(segment.file).path)
                    val source=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    if(track<0) { track=muxer.addTrack(extractor.getTrackFormat(source)); muxer.start(); started=true }
                    extractor.selectTrack(source)
                    val buffer=java.nio.ByteBuffer.allocate(256*1024); val info=MediaCodec.BufferInfo(); var last=0L
                    while(true) {
                        buffer.clear(); val size=extractor.readSampleData(buffer,0); if(size<0) break
                        last=extractor.sampleTime
                        info.set(0,size,offset+last,if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0); muxer.writeSampleData(track,buffer,info); extractor.advance()
                    }
                    offset += maxOf(segment.durationMs*1000,last+24000)
                } finally { extractor.release() }
            }
        } finally { if(started) muxer.stop(); muxer.release() }
    }
}

class RecordingService : Service() {
    private lateinit var store: AudioStore
    private var note: AudioNote? = null
    private var recorder: MediaRecorder? = null
    private var segmentFile: String? = null
    private var segmentStart=0L
    private val handler=Handler(Looper.getMainLooper())
    private val tick=object : Runnable { override fun run() {
        if(note==null) return
        AudioHub.elapsed=(note?.durationMs ?: 0)+(if(recorder!=null) SystemClock.elapsedRealtime()-segmentStart else 0)
        if(store.directory.usableSpace < 5*1024*1024) finish("Недостаточно места для записи")
        else handler.postDelayed(this,500)
    } }
    override fun onCreate() { super.onCreate(); store=AudioStore(this) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when(intent?.action) {
                "start" -> if(note==null) {
                    val board=intent.getStringExtra("boardId") ?: return START_NOT_STICKY
                    note=AudioNote(boardId=board,title=intent.getStringExtra("title") ?: "Запись лекции")
                    AudioHub.activeId=note!!.id; AudioHub.error=null; AudioHub.paused=false
                    foreground(); store.save(note!!); startSegment(); tick.run()
                    marker(intent.getStringExtra("pageId") ?: "", "Начало записи")
                }
                "pause" -> if(note!=null) { closeSegment(); AudioHub.paused=true; foreground() }
                "resume" -> if(note!=null && recorder==null) { startSegment(); AudioHub.paused=false; foreground() }
                "stop" -> finish(null)
                "mark" -> if(note!=null) marker(intent.getStringExtra("pageId") ?: "",intent.getStringExtra("title") ?: "Закладка")
            }
        } catch(e: Exception) { finish(e.message ?: "Ошибка записи") }
        return START_NOT_STICKY
    }
    private fun marker(page: String,title: String) {
        val current=note ?: return
        val time=current.durationMs+(if(recorder!=null) SystemClock.elapsedRealtime()-segmentStart else 0)
        note=current.copy(markers=current.markers+AudioMarker(page,time,title)); store.save(note!!)
    }
    @Suppress("DEPRECATION") private fun startSegment() {
        val current=note ?: return
        val name="${current.id}-${UUID.randomUUID()}.m4a"
        val media=if(Build.VERSION.SDK_INT>=31) MediaRecorder(this) else MediaRecorder()
        try {
            media.setAudioSource(MediaRecorder.AudioSource.MIC); media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); media.setAudioSamplingRate(44100); media.setAudioEncodingBitRate(96000); media.setAudioChannels(1)
            media.setOutputFile(store.file(name).path); media.setMaxDuration(60000)
            media.setOnInfoListener { _, what, _ -> if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                try { closeSegment(); startSegment() } catch(e: Exception) { finish(e.message) }
            } }
            media.setOnErrorListener { _,_,_ -> finish("Микрофон недоступен") }
            if (Build.VERSION.SDK_INT >= 29) media.registerAudioRecordingCallback(mainExecutor, object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (configs.any { it.isClientSilenced }) finish("Запись остановлена: микрофон отключён или занят другим приложением")
                }
            })
            media.prepare(); media.start(); recorder=media; segmentFile=name; segmentStart=SystemClock.elapsedRealtime()
        } catch(e: Exception) { media.release(); store.file(name).delete(); throw e }
    }
    private fun closeSegment() {
        val media=recorder ?: return
        recorder=null
        val name=segmentFile ?: return
        segmentFile=null
        val duration=SystemClock.elapsedRealtime()-segmentStart
        try {
            media.stop()
            note=note?.copy(segments=note!!.segments+AudioSegment(name,duration))
            note?.let(store::save)
        } catch(e: RuntimeException) { store.file(name).delete(); if(duration>1000) throw e }
        finally { media.release() }
    }
    private fun finish(error: String?) {
        handler.removeCallbacks(tick)
        val closeError=runCatching { closeSegment() }.exceptionOrNull()?.message
        val message=error ?: closeError
        note?.let { runCatching { store.save(it.copy(status=if(message==null) "complete" else "interrupted",error=message)) } }
        note=null; AudioHub.activeId=null; AudioHub.paused=false; AudioHub.error=message
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun foreground() {
        val manager=getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel("recording","Запись лекции",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(value:String)=PendingIntent.getService(this,value.hashCode(),Intent(this,RecordingService::class.java).setAction(value),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION") val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"recording") else Notification.Builder(this)
        val notification=builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(if(AudioHub.paused) "Запись на паузе" else "InkLab записывает лекцию")
            .setContentText(note?.title).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,if(AudioHub.paused) "Продолжить" else "Пауза",action(if(AudioHub.paused) "resume" else "pause")).build())
            .addAction(Notification.Action.Builder(null,"Остановить",action("stop")).build()).build()
        if(Build.VERSION.SDK_INT>=30) startForeground(41,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(41,notification)
    }
    override fun onDestroy() { if(note!=null) finish("Запись остановлена системой"); super.onDestroy() }
}

fun recordingCommand(context: Context, action: String, boardId: String? = null, pageId: String? = null, title: String? = null) {
    val intent=Intent(context,RecordingService::class.java).setAction(action).putExtra("boardId",boardId).putExtra("pageId",pageId).putExtra("title",title)
    if(action=="start" && Build.VERSION.SDK_INT>=26) context.startForegroundService(intent) else context.startService(intent)
}
