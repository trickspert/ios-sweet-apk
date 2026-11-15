package com.iossweet.sweet16

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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

    /*
     * Direct display (DirectFrame): full-resolution frames read from the guest's RAM
     * replace the VNC picture once the first one arrives. The pixels are BGRA: the
     * paint swaps red and blue while the GPU scales the bitmap.
     */
    private var directBitmap: Bitmap? = null
    private var directPixels: java.nio.ByteBuffer? = null
    private var directNew = false
    private val directMatrix = Matrix()
    private val directInverse = Matrix()
    private val bgraPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 1f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 255f)))
    }
    @Volatile var direct = false
        private set

    /**
     * From the direct-display thread: a new frame (packed BGRA, w*h*4 bytes). Returns
     * the previous buffer, which the view no longer uses (null at first).
     */
    fun directFrame(pixels: java.nio.ByteBuffer, w: Int, h: Int): java.nio.ByteBuffer? {
        val old: java.nio.ByteBuffer?
        synchronized(lock) {
            old = directPixels
            directPixels = pixels
            if (directBitmap?.width != w || directBitmap?.height != h) {
                directBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
                post { updateMatrix(); invalidate() }
            }
            directNew = true
            direct = true
        }
        postInvalidateOnAnimation()
        return old
    }

    /** Back to the VNC picture (direct display stopped). */
    fun directOff() {
        synchronized(lock) {
            direct = false
            directBitmap = null
            directPixels = null
        }
        postInvalidate()
    }

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
        if (!direct) postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateMatrix()

    private fun updateMatrix() {
        if (width == 0 || height == 0) return
        fun fit(bw: Int, bh: Int, m: Matrix, inv: Matrix) {
            val s = minOf(width.toFloat() / bw, height.toFloat() / bh)
            m.reset()
            m.postScale(s, s)
            m.postTranslate((width - bw * s) / 2f, (height - bh * s) / 2f)
            m.invert(inv)
        }
        bitmap?.let { fit(it.width, it.height, matrix, inverse) }
        directBitmap?.let { fit(it.width, it.height, directMatrix, directInverse) }
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(lock) {
            val db = directBitmap
            val dp = directPixels
            if (direct && db != null) {
                if (directNew && dp != null && dp.remaining() >= db.width * db.height * 4) {
                    dp.mark()
                    db.copyPixelsFromBuffer(dp)
                    dp.reset()
                    directNew = false
                }
                canvas.drawBitmap(db, directMatrix, bgraPaint)
                return
            }
        }
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
        val db = directBitmap
        if (direct && db != null && c.width > 0) {
            // Frame pixels -> VNC pixels: both span the whole display, linearly.
            directInverse.mapPoints(point)
            point[0] = point[0] * c.width / db.width
            point[1] = point[1] * c.height / db.height
        } else {
            inverse.mapPoints(point)
        }
        val px = point[0].toInt().coerceIn(0, maxOf(0, c.width - 1))
        val py = point[1].toInt().coerceIn(0, maxOf(0, c.height - 1))
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
