package com.iossweet.sweet16

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Shows the guest framebuffer (from [RfbClient]) scaled to fit, and turns
 * the first finger into absolute pointer events (the guest's touch screen).
 */
class VmView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var client: RfbClient? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val dirty = Rect()
    private var hasDirty = false
    private val lock = Any()
    private val point = FloatArray(2)
    private var activePointer = -1

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** From the RFB thread. */
    fun resize(w: Int, h: Int) {
        post {
            synchronized(lock) {
                bitmap = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) } else null
                hasDirty = false
            }
            updateMatrix()
            invalidate()
        }
    }

    /** From the RFB thread: mark a rectangle for the next frame. */
    fun damage(x: Int, y: Int, w: Int, h: Int) {
        synchronized(lock) {
            if (hasDirty) dirty.union(x, y, x + w, y + h) else dirty.set(x, y, x + w, y + h)
            hasDirty = true
        }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateMatrix()

    private fun updateMatrix() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        matrix.invert(inverse)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val c = client
        synchronized(lock) {
            if (hasDirty && c != null) {
                val px = c.pixels
                if (px.size == b.width * b.height) {
                    dirty.intersect(0, 0, b.width, b.height)
                    synchronized(px) {
                        b.setPixels(px, dirty.top * b.width + dirty.left, b.width,
                            dirty.left, dirty.top, dirty.width(), dirty.height())
                    }
                }
                hasDirty = false
            }
        }
        canvas.drawBitmap(b, matrix, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = client ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = e.getPointerId(0)
                send(c, e, 0, true)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activePointer)
                if (i >= 0) {
                    for (h in 0 until e.historySize) send(c, e.getHistoricalX(i, h), e.getHistoricalY(i, h), true)
                    send(c, e, i, true)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == activePointer) {
                    send(c, e, e.actionIndex, false)
                    activePointer = -1
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val i = e.findPointerIndex(activePointer)
                if (i >= 0) send(c, e, i, false)
                activePointer = -1
            }
        }
        return true
    }

    private fun send(c: RfbClient, e: MotionEvent, i: Int, down: Boolean) = send(c, e.getX(i), e.getY(i), down)

    private fun send(c: RfbClient, x: Float, y: Float, down: Boolean) {
        point[0] = x
        point[1] = y
        inverse.mapPoints(point)
        val px = point[0].toInt()
        val py = point[1].toInt()
        input.execute {
            try {
                c.pointer(px, py, down)
            } catch (_: Exception) {
            }
        }
    }

    /** Socket writes off the UI thread, in order. */
    val input: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
}
