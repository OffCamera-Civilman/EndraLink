package com.endralink.app

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/** Renders a proportional section of approved artwork; controls remain native accessible views. */
class ArtworkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var artwork: Drawable? = null
    private var top = 0f
    private var bottom = 1f

    /** Select a region without modifying the source illustration. */
    fun configure(resource: Int, from: Float, to: Float) {
        artwork = ContextCompat.getDrawable(context, resource)
        top = from
        bottom = to
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val d = artwork
        val h = if (d == null) 0 else (w * d.intrinsicHeight.toFloat() / d.intrinsicWidth * (bottom - top)).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = artwork ?: return
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        val scale = width.toFloat() / d.intrinsicWidth
        canvas.scale(scale, scale)
        canvas.translate(0f, -d.intrinsicHeight * top)
        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
        d.draw(canvas)
        canvas.restore()
    }
}
