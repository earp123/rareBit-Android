package com.example.rarebit.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * FrameLayout that injects a software-layer glow surface behind its first XML child.
 * The glow view is added at index 0 in init; XML-inflated children sit on top.
 */
class GlowCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val glowSurface = GlowSurface(context)

    init {
        clipChildren = false
        clipToPadding = false
        glowSurface.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        glowSurface.setLayerType(LAYER_TYPE_SOFTWARE, null)
        glowSurface.alpha = 0f
        addView(glowSurface)
    }

    fun setGlowColor(color: Int) {
        glowSurface.setGlowColor(color)
    }

    fun showGlow(animate: Boolean = false) {
        if (animate) {
            glowSurface.animate().alpha(1f).setDuration(400).start()
        } else {
            glowSurface.alpha = 1f
        }
    }

    fun hideGlow() {
        glowSurface.animate().cancel()
        glowSurface.alpha = 0f
    }
}

private class GlowSurface(context: Context) : View(context) {

    private val blurRadius = 24f * context.resources.displayMetrics.density
    // Must track app:cardCornerRadius in item_device_card.xml
    private val cornerRadius = 26f * context.resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    fun setGlowColor(color: Int) {
        paint.color = color
        paint.maskFilter = if (color != Color.TRANSPARENT)
            BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.OUTER)
        else null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (paint.color == Color.TRANSPARENT || paint.maskFilter == null) return
        // Draw inset so OUTER blur has room within view bounds
        val inset = blurRadius * 0.5f
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
    }
}
