package dev.swart.inklab.audio

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.*

class AudioPlayer(context: Context) {
    private val store=AudioStore(context)
    private var player: MediaPlayer?=null
    private var segmentIndex=0
    var note by mutableStateOf<AudioNote?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val position: Long get() = (note?.segments?.take(segmentIndex)?.sumOf {it.durationMs} ?: 0)+runCatching {player?.currentPosition?.toLong() ?: 0}.getOrDefault(0)
    fun open(value: AudioNote, time: Long=0) { release(); note=value; seek(time,true) }
    fun toggle() { val media=player ?: return; if(playing) {media.pause();playing=false} else {media.start();playing=true} }
    fun changeSpeed(value: Float) { speed=value; player?.let { val wasPlaying=playing; it.playbackParams=it.playbackParams.setSpeed(value);if(!wasPlaying) it.pause() } }
    fun seek(time: Long, autoplay: Boolean=playing) {
        val current=note ?: return
        if(current.segments.isEmpty()) return
        var target=time.coerceIn(0,(current.durationMs-1).coerceAtLeast(0));var index=0
        while(index<current.segments.lastIndex && target>=current.segments[index].durationMs) {target-=current.segments[index].durationMs; index++}
        player?.release(); player=null; playing=false; segmentIndex=index
        try {
            val media=MediaPlayer();player=media
            media.setDataSource(store.file(current.segments[index].file).path)
            media.setOnErrorListener { _,_,_-> error="Не удалось воспроизвести запись";playing=false;true }
            media.setOnCompletionListener {
                if(segmentIndex<current.segments.lastIndex) seek(current.segments.take(segmentIndex+1).sumOf {it.durationMs},true)
                else {playing=false; seek(0,false)}
            }
            media.prepare();media.seekTo(target.toInt());media.playbackParams=media.playbackParams.setSpeed(speed)
            if(autoplay) {media.start();playing=true} else media.pause()
        } catch(e: Exception) {error=e.message; player?.release();player=null;playing=false}
    }
    fun release() { player?.release();player=null;playing=false }
}
