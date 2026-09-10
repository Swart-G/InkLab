package dev.swart.inklab.core.input

import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.compose.ui.geometry.Offset
import dev.swart.inklab.core.model.InkPoint
import dev.swart.inklab.ui.CanvasInputSource
import dev.swart.inklab.ui.EditorTool
import dev.swart.inklab.ui.EditorViewModel

class CanvasInputController(private val vm: EditorViewModel, private val slop: Float) {
    private var penId = -1
    private var tool: EditorTool? = null
    private var penStarted = 0L
    private var lastMove = 0L
    private var tap = TwoFingerTap(slop)
    private val stylusButton = StylusButtonState()
    private var touchBlocked = false
    private var touchStart = Offset.Zero
    private var touchStarted = 0L
    private var touchCount = 0
    private var dragged = false
    private var touchSelectionDragCandidate = false
    private var touchSelectionDragging = false
    private var suppressPenUntilUp = false
    private var previous = emptyMap<Int, Offset>()
    private var velocityTracker: VelocityTracker? = null

    fun hover(event: MotionEvent) {
        stylusButton.observePressedFlag(hasStylusButton(event.buttonState), event.eventTime)
        if ((0 until event.pointerCount).any { isPen(event.getToolType(it)) }) {
            vm.stylusHover = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            vm.lastStylusTime = event.eventTime
            if (vm.stylusHover) {
                tap.cancel()
                if (vm.inputPreferences.palmRejection) touchBlocked = true
            }
        }
    }

    private fun isPen(type: Int) = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER

    private fun hasStylusButton(buttons: Int): Boolean =
        buttons and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0

    fun cancel() {
        if (penId != -1 || touchSelectionDragging) vm.cancelInput()
        penId = -1
        tool = null
        tap.cancel()
        previous = emptyMap()
        touchBlocked = true
        touchSelectionDragCandidate = false
        touchSelectionDragging = false
        suppressPenUntilUp = false
        stylusButton.reset()
        velocityTracker?.recycle()
        velocityTracker = null
    }

    fun event(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            cancel()
            return true
        }

        // Samsung S Pen can report these while hovering, before ACTION_DOWN. Keep the button state
        // latched so the following contact starts with the temporary eraser/lasso override.
        if (action == MotionEvent.ACTION_BUTTON_PRESS && hasStylusButton(e.actionButton)) {
            stylusButton.press(e.eventTime)
            return true
        }
        if (action == MotionEvent.ACTION_BUTTON_RELEASE && hasStylusButton(e.actionButton)) {
            stylusButton.release()
            // A temporary tool must end when the button is released, even while the nib still
            // touches the display. Do not reinterpret the same physical contact as a pen stroke;
            // resume the selected tool on the next ACTION_DOWN.
            if (penId != -1 && tool != vm.tool) {
                when (tool) {
                    EditorTool.ERASER -> vm.finishErase()
                    EditorTool.LASSO -> vm.finishLasso()
                    EditorTool.PEN -> vm.finishStroke(false)
                    null -> Unit
                }
                vm.setStylusContact(false)
                vm.endInput()
                penId = -1
                tool = null
                suppressPenUntilUp = true
            }
            return true
        }
        stylusButton.observePressedFlag(hasStylusButton(e.buttonState), e.eventTime)

        val canceled = Build.VERSION.SDK_INT >= 33 && e.flags and MotionEvent.FLAG_CANCELED != 0
        if (canceled) {
            tap.cancel()
            if (touchSelectionDragging) {
                vm.cancelInput()
                touchSelectionDragging = false
                touchSelectionDragCandidate = false
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            vm.stopViewportMotion()
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain().also { it.addMovement(e) }
            tap = TwoFingerTap(slop)
            previous = emptyMap()
            touchCount = 0
            dragged = false
            touchSelectionDragging = false
            touchBlocked = vm.inputPreferences.palmRejection && (vm.stylusHover || e.eventTime - vm.lastStylusTime < 700L)
            touchStart = Offset(e.x, e.y)
            touchStarted = e.eventTime
            touchSelectionDragCandidate = !touchBlocked && vm.selectionBounds
                ?.inflate(18f / vm.viewportScale)
                ?.contains(vm.screenToCanvas(touchStart)) == true
        }

        val penIndex = (0 until e.pointerCount).firstOrNull {
            isPen(e.getToolType(it)) || e.getToolType(it) == MotionEvent.TOOL_TYPE_MOUSE
        }
        if (penIndex != null) {
            tap.cancel()
            if (touchSelectionDragging) {
                vm.cancelInput()
                touchSelectionDragging = false
                touchSelectionDragCandidate = false
            }
            touchBlocked = true
            val index = if (penId >= 0) e.findPointerIndex(penId) else penIndex
            if (index < 0) {
                cancel()
                return true
            }
            val up = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && e.actionIndex == index
            if (suppressPenUntilUp) {
                if (up) suppressPenUntilUp = false
                return true
            }
            val isStylus = isPen(e.getToolType(index))
            if (isStylus) vm.lastStylusTime = e.eventTime
            if (canceled && up) {
                cancel()
                return true
            }

            if (penId == -1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && e.actionIndex == index) {
                val screen = Offset(e.getX(index), e.getY(index))
                val page = vm.pageAt(screen) ?: return true
                vm.activatePage(page)
                vm.beginInput()
                penId = e.getPointerId(index)
                penStarted = e.eventTime
                lastMove = e.eventTime
                val source = when (e.getToolType(index)) {
                    MotionEvent.TOOL_TYPE_ERASER -> CanvasInputSource.STYLUS_ERASER
                    MotionEvent.TOOL_TYPE_MOUSE -> CanvasInputSource.MOUSE
                    else -> CanvasInputSource.STYLUS
                }
                val temporaryOverride = isStylus && (stylusButton.pressed || hasStylusButton(e.buttonState))
                tool = vm.effectiveTool(source, temporaryOverride)
                if (isStylus) vm.setStylusContact(true)
                val point = vm.screenToCanvas(screen)
                when (tool) {
                    EditorTool.PEN -> vm.startStroke(ink(point, e.eventTime, e.getPressure(index), e.getAxisValue(MotionEvent.AXIS_TILT, index)))
                    EditorTool.ERASER -> {
                        vm.beginErase()
                        vm.eraserCursor = point
                        vm.eraseAt(point)
                    }
                    EditorTool.LASSO -> vm.startLasso(point)
                    null -> Unit
                }
            } else if (penId != -1) {
                if (action == MotionEvent.ACTION_MOVE || up) {
                    for (h in 0 until e.historySize) {
                        add(
                            Offset(e.getHistoricalX(index, h), e.getHistoricalY(index, h)),
                            e.getHistoricalEventTime(h),
                            e.getHistoricalPressure(index, h),
                            e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h)
                        )
                    }
                    add(
                        Offset(e.getX(index), e.getY(index)),
                        e.eventTime,
                        e.getPressure(index),
                        e.getAxisValue(MotionEvent.AXIS_TILT, index)
                    )
                }
                if (up) {
                    when (tool) {
                        EditorTool.PEN -> vm.finishStroke(
                            isStylus && e.eventTime - lastMove >= 520L && e.eventTime - penStarted >= 520L
                        )
                        EditorTool.ERASER -> vm.finishErase()
                        EditorTool.LASSO -> vm.finishLasso()
                        null -> Unit
                    }
                    if (isStylus) vm.setStylusContact(false)
                    vm.endInput()
                    penId = -1
                    tool = null
                    // If a dedicated RELEASE event was missed, buttonState on UP prevents a sticky
                    // temporary eraser on the next contact.
                    if (!hasStylusButton(e.buttonState)) stylusButton.release()
                }
            }
            return true
        }

        if (penId != -1) {
            cancel()
            return true
        }

        val points = (0 until e.pointerCount).associate { e.getPointerId(it) to Offset(e.getX(it), e.getY(it)) }
        if (action != MotionEvent.ACTION_DOWN) velocityTracker?.addMovement(e)
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            tap.down(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            touchCount++
            if (touchCount > 1) {
                velocityTracker?.recycle()
                velocityTracker = null
                touchSelectionDragCandidate = false
                if (touchSelectionDragging) {
                    vm.cancelInput()
                    touchSelectionDragging = false
                    dragged = false
                }
            }
        }
        points.forEach { (id, p) -> tap.move(id, p.x, p.y) }
        if (vm.stylusHover || e.eventTime - vm.lastStylusTime < 700L || canceled) {
            if (vm.inputPreferences.palmRejection || canceled) touchBlocked = true
            tap.cancel()
            touchSelectionDragCandidate = false
        }
        if (!touchBlocked && action == MotionEvent.ACTION_MOVE && points.keys == previous.keys) {
            val center = points.values.reduce { a, b -> a + b } / points.size.toFloat()
            val oldCenter = previous.values.reduce { a, b -> a + b } / previous.size.toFloat()
            if (points.size == 1 && (center - touchStart).getDistance() >= slop && !dragged) {
                dragged = true
                if (touchSelectionDragCandidate) {
                    vm.beginInput()
                    vm.startLasso(vm.screenToCanvas(touchStart))
                    touchSelectionDragging = true
                    tap.cancel()
                }
            }
            if (points.size == 2) {
                val p = points.values.toList()
                val old = previous.values.toList()
                val distance = (p[0] - p[1]).getDistance()
                val oldDistance = (old[0] - old[1]).getDistance()
                if (oldDistance > 1f && kotlin.math.abs(distance - oldDistance) > 0.5f) {
                    tap.cancel()
                    vm.zoomBy(distance / oldDistance, center)
                }
                if ((center - oldCenter).getDistance() > 0.5f) {
                    tap.cancel()
                    vm.panBy(center - oldCenter)
                }
            } else if (dragged && points.size == 1) {
                if (touchSelectionDragging) {
                    vm.addLasso(vm.screenToCanvas(center))
                } else {
                    vm.panBy(center - oldCenter)
                }
            }
        }
        if (action == MotionEvent.ACTION_UP) {
            val selectionWasDragging = touchSelectionDragging
            if (touchSelectionDragging) {
                vm.finishLasso()
                vm.endInput()
                touchSelectionDragging = false
                touchSelectionDragCandidate = false
            } else if (!touchBlocked && !canceled) {
                if (vm.inputPreferences.twoFingerUndo && tap.finish(e.eventTime)) {
                    vm.undo()
                } else if (touchCount == 1 && !dragged && e.eventTime - touchStarted < 250L) {
                    vm.pageAt(touchStart)?.let {
                        vm.activatePage(it)
                        vm.selectObjectAt(vm.screenToCanvas(touchStart))
                    }
                }
            }
            if (!touchBlocked && !canceled && dragged && touchCount == 1 && !selectionWasDragging) {
                velocityTracker?.apply {
                    computeCurrentVelocity(1000, 8_000f)
                    vm.flingViewport(Offset(xVelocity, yVelocity))
                }
            }
            velocityTracker?.recycle()
            velocityTracker = null
            vm.flush()
            previous = emptyMap()
            touchSelectionDragCandidate = false
        } else {
            previous = points
        }
        return true
    }

    private fun ink(p: Offset, t: Long, pressure: Float, tilt: Float) = InkPoint(
        p.x,
        p.y,
        t,
        if (vm.inputPreferences.pressureEnabled) pressure.coerceIn(0.15f, 1f) else 0.6f,
        tilt.coerceIn(0f, (Math.PI / 2.0).toFloat())
    )

    private fun add(screen: Offset, time: Long, pressure: Float, tilt: Float) {
        val p = vm.screenToCanvas(screen)
        when (tool) {
            EditorTool.PEN -> {
                if (vm.currentPoints.lastOrNull()?.let { (it.offset() - p).getDistance() > 0.7f } == true) lastMove = time
                vm.addPoint(ink(p, time, pressure, tilt))
            }
            EditorTool.ERASER -> {
                vm.eraserCursor = p
                vm.eraseAt(p)
            }
            EditorTool.LASSO -> vm.addLasso(p)
            null -> Unit
        }
    }
}
