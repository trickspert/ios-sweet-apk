package com.iossweet.sweet16

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider

/**
 * The card artwork: an original abstract night-to-dusk scene (layered
 * gradients, soft light orbs, flowing ribbons) with a frosted "16" and the
 * OS name. Drawn in code so it scales to any card size.
 */
class Ios16ArtView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val corner = 38f * density
    private val sky = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numeral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val numeralEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = numeral.typeface
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val tagline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = Color.argb(225, 255, 255, 255)
    }
    private val path = Path()

    /** Dim the art when the OS is not installed yet. */
    var dimmed: Boolean = false
        set(value) { field = value; invalidate() }

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        clipToOutline = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val fw = w.toFloat()
        val fh = h.toFloat()
        sky.shader = LinearGradient(0f, 0f, fw * 0.35f, fh,
            intArrayOf(0xFF0A1238.toInt(), 0xFF2A2477.toInt(), 0xFF6A3AA8.toInt(), 0xFFC4588F.toInt(), 0xFFF29A5C.toInt()),
            floatArrayOf(0f, 0.3f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
        numeral.textSize = fw * 0.62f
        numeral.shader = LinearGradient(0f, fh * 0.12f, 0f, fh * 0.42f,
            intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(120, 226, 220, 255)),
            null, Shader.TileMode.CLAMP)
        numeralEdge.textSize = numeral.textSize
        numeralEdge.strokeWidth = 1.6f * density
        numeralEdge.color = Color.argb(70, 255, 255, 255)
        label.textSize = fw * 0.17f
        tagline.textSize = fw * 0.052f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, sky)

        // Soft light orbs.
        drawOrb(canvas, w * 0.85f, h * 0.18f, w * 0.55f, 0x806C8BFF.toInt())
        drawOrb(canvas, w * 0.1f, h * 0.55f, w * 0.6f, 0x70A05BFF.toInt())
        drawOrb(canvas, w * 0.75f, h * 0.92f, w * 0.75f, 0x90FFB36B.toInt())

        // Flowing ribbons across the lower half.
        drawRibbon(canvas, w, h, 0.58f, 0.10f, 0x55FFFFFF, 0x00FFFFFF)
        drawRibbon(canvas, w, h, 0.66f, -0.08f, 0x66FF7FB0, 0x10FFC27A)
        drawRibbon(canvas, w, h, 0.76f, 0.12f, 0x80FF9B5E.toInt(), 0x30FF6FA0)
        drawRibbon(canvas, w, h, 0.86f, -0.06f, 0x70362A86, 0x50201A5C)

        // Frosted "16".
        val baseline = h * 0.40f
        glow.color = Color.argb(90, 190, 180, 255)
        glow.maskFilter = BlurMaskFilter(18f * density, BlurMaskFilter.Blur.NORMAL)
        glow.textSize = numeral.textSize
        glow.typeface = numeral.typeface
        glow.textAlign = Paint.Align.CENTER
        canvas.drawText("16", w / 2f, baseline, glow)
        canvas.drawText("16", w / 2f, baseline, numeral)
        canvas.drawText("16", w / 2f, baseline, numeralEdge)

        canvas.drawText("iOS", w / 2f, baseline + label.textSize * 1.25f, label)
        canvas.drawText("Runs on this phone, in a virtual machine", w / 2f,
            baseline + label.textSize * 1.25f + tagline.textSize * 2.0f, tagline)

        if (dimmed) canvas.drawColor(Color.argb(110, 0, 0, 0))
    }

    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        orb.shader = RadialGradient(cx, cy, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, orb)
    }

    private fun drawRibbon(canvas: Canvas, w: Float, h: Float, y: Float, tilt: Float, top: Int, bottom: Int) {
        val y0 = h * y
        path.reset()
        path.moveTo(-w * 0.1f, y0 + h * tilt)
        path.cubicTo(w * 0.25f, y0 - h * 0.12f, w * 0.6f, y0 + h * 0.10f, w * 1.1f, y0 - h * tilt)
        path.lineTo(w * 1.1f, h)
        path.lineTo(-w * 0.1f, h)
        path.close()
        ribbon.shader = LinearGradient(0f, y0 - h * 0.1f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawPath(path, ribbon)
    }
}
