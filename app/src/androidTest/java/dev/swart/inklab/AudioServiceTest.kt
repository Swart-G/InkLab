package dev.swart.inklab

import android.media.MediaMetadataRetriever
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.swart.inklab.audio.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioServiceTest {
    @Test fun recordPauseResumeBackgroundAndExport() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val store=AudioStore(context)
        val board="audio-test-${UUID.randomUUID()}"
        fun waitFor(condition: () -> Boolean) {val deadline=System.currentTimeMillis()+8000;while(!condition() && System.currentTimeMillis()<deadline) Thread.sleep(100);assertTrue(condition())}
        try {
            scenario.onActivity {recordingCommand(it,"start",board,"page-test","Test lecture")}
            waitFor {AudioHub.activeId!=null};Thread.sleep(1200)
            scenario.onActivity {recordingCommand(it,"mark",pageId="page-test",title="Bookmark")}
            scenario.onActivity {recordingCommand(it,"pause")};waitFor {AudioHub.paused}
            val paused=store.list().single {it.boardId==board};assertTrue(paused.durationMs>0);assertTrue(paused.markers.any {it.title=="Bookmark"})
            scenario.onActivity {recordingCommand(it,"resume")};waitFor {!AudioHub.paused};Thread.sleep(1200)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close();Thread.sleep(1100)
            assertNotNull(AudioHub.activeId)
            recordingCommand(context,"stop");waitFor {AudioHub.activeId==null}
            val complete=store.list().single {it.boardId==board};assertEquals("complete",complete.status);assertEquals(2,complete.segments.size)
            val output=File(context.cacheDir,"audio-test.m4a");store.export(complete,output)
            val reader=MediaMetadataRetriever()
            try {reader.setDataSource(output.path);assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>1000)} finally {reader.release();output.delete()}
        } finally {
            if(AudioHub.activeId!=null) {recordingCommand(context,"stop");Thread.sleep(300)}
            store.list().filter {it.boardId==board}.forEach(store::delete);scenario.close()
        }
    }
}
