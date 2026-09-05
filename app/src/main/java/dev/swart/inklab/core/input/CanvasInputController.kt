package dev.swart.inklab.core.input

import android.os.Build
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.ui.CanvasInputSource
import dev.swart.inklab.ui.EditorTool
import dev.swart.inklab.ui.EditorViewModel
import kotlin.math.hypot

class CanvasInputController(private val vm: EditorViewModel, private val slop: Float) {
    private var penId = -1
    private var tool: EditorTool? = null
    private var penStarted = 0L
    private var lastMove = 0L
    private var tap = TwoFingerTap(slop)
    private var touchBlocked = false
    private var touchStart = Offset.Zero
    private var touchStarted = 0L
    private var touchCount = 0
    private var dragged = false
    private var previous = emptyMap<Int, Offset>()

    fun hover(event: MotionEvent) {
        if ((0 until event.pointerCount).any { isPen(event.getToolType(it)) }) {
            vm.stylusHover = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            vm.lastStylusTime = event.eventTime
            if (vm.stylusHover) { tap.cancel(); if(vm.inputPreferences.palmRejection) touchBlocked = true }
        }
    }
    private fun isPen(type: Int) = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    fun cancel() { if (penId != -1) vm.cancelInput(); penId = -1; tool = null; tap.cancel(); previous = emptyMap(); touchBlocked = true }

    fun event(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true }
        val canceled = Build.VERSION.SDK_INT >= 33 && e.flags and MotionEvent.FLAG_CANCELED != 0
        if (canceled) tap.cancel()
        if (action == MotionEvent.ACTION_DOWN) {
            tap = TwoFingerTap(slop); previous = emptyMap(); touchCount = 0; dragged = false
            touchBlocked = vm.inputPreferences.palmRejection && (vm.stylusHover || e.eventTime-vm.lastStylusTime < 700L)
            touchStart = Offset(e.x,e.y); touchStarted = e.eventTime
        }
        val penIndex = (0 until e.pointerCount).firstOrNull { isPen(e.getToolType(it)) || e.getToolType(it) == MotionEvent.TOOL_TYPE_MOUSE }
        if (penIndex != null) {
            tap.cancel(); touchBlocked = true
            val index = if (penId >= 0) e.findPointerIndex(penId) else penIndex
            if (index < 0) { cancel(); return true }
            val up = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && e.actionIndex == index
            val isStylus = isPen(e.getToolType(index))
            if (isStylus) vm.lastStylusTime = e.eventTime
            if (canceled && up) { cancel(); return true }
            if (penId == -1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && e.actionIndex == index) {
                val screen = Offset(e.getX(index),e.getY(index))
                val page = vm.pageAt(screen) ?: return true
                vm.activatePage(page)
                vm.beginInput()
                penId = e.getPointerId(index); penStarted = e.eventTime; lastMove = e.eventTime
                val source = when (e.getToolType(index)) {
                    MotionEvent.TOOL_TYPE_ERASER -> CanvasInputSource.STYLUS_ERASER
                    MotionEvent.TOOL_TYPE_MOUSE -> CanvasInputSource.MOUSE
                    else -> CanvasInputSource.STYLUS
                }
                tool = vm.effectiveTool(source, e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0)
                if (isStylus) vm.setStylusContact(true)
                val point = vm.screenToCanvas(screen)
                when (tool) {
                    EditorTool.PEN -> vm.startStroke(ink(point,e.eventTime,e.getPressure(index)))
                    EditorTool.ERASER -> { vm.beginErase(); vm.eraserCursor=point; vm.eraseAt(point) }
                    EditorTool.LASSO -> vm.startLasso(point)
                    null -> Unit
                }
            } else if (penId != -1) {
                if (action == MotionEvent.ACTION_MOVE || up) {
                    for (h in 0 until e.historySize) add(Offset(e.getHistoricalX(index,h),e.getHistoricalY(index,h)),e.getHistoricalEventTime(h), e.getHistoricalPressure(index,h))
                    add(Offset(e.getX(index),e.getY(index)),e.eventTime,e.getPressure(index))
                }
                if (up) {
                    when (tool) {
                        EditorTool.PEN -> vm.finishStroke(isStylus && e.eventTime-lastMove >= 520L && e.eventTime-penStarted >= 520L)
                        EditorTool.ERASER -> vm.finishErase()
                        EditorTool.LASSO -> vm.finishLasso()
                        null -> Unit
                    }
                    if (isStylus) vm.setStylusContact(false); vm.endInput(); penId = -1; tool = null
                }
            }
            return true
        }
        if (penId != -1) { cancel(); return true }
        val points = (0 until e.pointerCount).associate { e.getPointerId(it) to Offset(e.getX(it),e.getY(it)) }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            tap.down(e.getPointerId(i),e.getX(i),e.getY(i),e.eventTime)
            touchCount++
        }
        points.forEach { (id, p) -> tap.move(id,p.x,p.y) }
        if (vm.stylusHover || e.eventTime-vm.lastStylusTime < 700L || canceled) { if(vm.inputPreferences.palmRejection || canceled) touchBlocked = true; tap.cancel() }
        if (!touchBlocked && action == MotionEvent.ACTION_MOVE && points.keys == previous.keys) {
            val center = points.values.reduce { a,b -> a+b } / points.size.toFloat()
            val oldCenter = previous.values.reduce { a,b -> a+b } / previous.size.toFloat()
            if (points.size == 1 && (center-touchStart).getDistance() >= slop) dragged = true
            if (points.size == 2) {
                val p = points.values.toList(); val old = previous.values.toList()
                val distance = (p[0]-p[1]).getDistance(); val oldDistance = (old[0]-old[1]).getDistance()
                if (oldDistance > 1f && kotlin.math.abs(distance-oldDistance) > 0.5f) { tap.cancel(); vm.zoomBy(distance/oldDistance,center) }
                if ((center-oldCenter).getDistance() > 0.5f) { tap.cancel(); vm.panBy(center-oldCenter) }
            } else if (dragged && points.size == 1) vm.panBy(center-oldCenter)
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!touchBlocked && !canceled) {
                if (vm.inputPreferences.twoFingerUndo && tap.finish(e.eventTime)) vm.undo()
                else if (touchCount == 1 && !dragged && e.eventTime-touchStarted < 250L) {
                    vm.pageAt(touchStart)?.let { vm.activatePage(it); vm.selectObjectAt(vm.screenToCanvas(touchStart)) }
                }
            }
            vm.flush()
            previous = emptyMap()
        } else previous = points
        return true
    }
    private fun ink(p: Offset,t: Long,pressure: Float) = InkPoint(p.x,p.y,t,if (vm.inputPreferences.pressureEnabled) pressure.coerceIn(0.15f,1f) else 0.6f)
    private fun add(screen: Offset, time: Long, pressure: Float) {
        val p = vm.screenToCanvas(screen)
        when (tool) {
            EditorTool.PEN -> {
                if (vm.currentPoints.lastOrNull()?.let { (it.offset()-p).getDistance() > 0.7f } == true) lastMove=time
                vm.addPoint(ink(p,time,pressure))
            }
            EditorTool.ERASER -> { vm.eraserCursor=p; vm.eraseAt(p) }
            EditorTool.LASSO -> vm.addLasso(p)
            null -> Unit
        }
    }
}
