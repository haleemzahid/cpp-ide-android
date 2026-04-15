package dev.cppide.ide.screens.editor.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * [CodeEditor] subclass that paints debugger decorations inside Sora's
 * own drawing pass — not as a Compose overlay.
 *
 * Why: Compose overlays have to track Sora's scroll position by listening
 * to `ScrollEvent` and then doing their own pixel math. That works most of
 * the time but desyncs on programmatic scrolls, zoom changes, initial
 * layout, and any Compose recomposition that races the scroll event.
 * Painting inside the editor's own `onDraw` eliminates the whole class of
 * sync bugs because we use Sora's real, current coordinate state every
 * single frame.
 *
 * Draw order: we call `super.onDraw(canvas)` first so Sora renders text,
 * syntax highlighting, line numbers, and its own current-line marker.
 * Then we paint our debugger decorations on top, so they layer above
 * the text but below anything Compose draws in the editor pane.
 *
 * Decorations drawn:
 *   - **Current-execution-line highlight**: a full-width amber fill across
 *     the line that the debugger is currently stopped on, plus a filled
 *     arrow in the gutter pointing right.
 *   - **Breakpoint markers**: a filled red circle (verified) or hollow
 *     grey circle (unverified) in the gutter at each breakpoint line.
 *     The filled circle is drawn on top of the gutter arrow so both can
 *     coexist on the same line (VSCode does this too).
 *
 * Coordinate system: inside `onDraw`, the canvas origin is the top-left
 * of the `View`. Sora's text is drawn at `rowBaselineY = textTopMargin +
 * (lineIndex) * rowHeight - offsetY` where `offsetY` is Sora's current
 * vertical scroll (not the `View.scrollY` field — Sora manages its own
 * scroll state). We match that convention exactly.
 */
class DebugCodeEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CodeEditor(context, attrs, defStyleAttr) {

    // ---- public state (call setters from the host; each invalidates) ----

    /** 1-indexed source line the debugger is stopped on, or null when not stopped. */
    var currentExecutionLine: Int? = null
        set(value) {
            if (field != value) {
                Log.i(TAG, "currentExecutionLine: $field -> $value")
                field = value
                invalidate()
            }
        }

    /**
     * Breakpoint lines: map of 1-indexed line → verified flag. A verified
     * breakpoint is drawn as a filled red circle; unverified as a hollow
     * grey ring. Same lines as [breakpointLines] from the old overlay.
     */
    var breakpointLines: Map<Int, Boolean> = emptyMap()
        set(value) {
            if (field != value) {
                Log.i(TAG, "breakpointLines: ${field.size} -> ${value.size}")
                field = value
                invalidate()
            }
        }

    // ---- paints (lazy, to avoid allocs in onDraw) ----

    private val currentLineFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFB000.toInt()  // soft amber, ~25% alpha
        style = Paint.Style.FILL
    }

    private val gutterArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB000.toInt()  // solid amber
        style = Paint.Style.FILL
    }

    private val bpFilled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF14C4C.toInt()  // VSCode-style filled red
        style = Paint.Style.FILL
    }

    private val arrowPath = Path()

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val row = rowHeight
        if (row <= 0) return
        val viewH = height
        val scroll = offsetY
        val layout = layout
        val dp = dpUnit
        // Sora's line-number gutter sits in the left column. We don't have
        // a precise public getter for its width, so we estimate from
        // dpUnit — close enough for decoration drawing.
        val gutterWidth = (row * 2.5f).coerceAtLeast(dp * 40f)
        val textRight = width.toFloat()

        // Resolve a 1-indexed source line to a screen-Y for the top of
        // its row. Uses sora's actual layout API (which accounts for
        // text size changes from pinch-zoom, line spacing, soft-wrap,
        // etc.) so the decoration tracks the real text position even
        // during fast scrolls. Falls back to plain row-math if the
        // layout isn't ready yet (rare — only on the very first frame).
        fun screenTopOfLine(line: Int): Float {
            val layout0 = layout
            if (layout0 == null) return ((line - 1) * row - scroll).toFloat()
            val xy = layout0.getCharLayoutOffset(line - 1, 0)
            // getCharLayoutOffset returns [bottom-y, x] in editor layout
            // coordinates (post-zoom, pre-scroll). The y returned is the
            // BASELINE bottom of the row, so subtract row to get the top.
            // We then subtract the current scroll offset to get screen-y.
            val rowBottomLayoutY = xy[0]
            return rowBottomLayoutY - row - scroll
        }

        // --- current-execution line (full-width amber fill behind text) ---
        currentExecutionLine?.let { line ->
            if (line < 1) return@let
            val top = screenTopOfLine(line)
            if (top + row >= 0 && top <= viewH) {
                canvas.drawRect(
                    0f, top,
                    textRight, top + row,
                    currentLineFill,
                )
                drawGutterArrow(canvas, top, row.toFloat(), gutterWidth)
            }
        }

        // --- breakpoint markers ---
        // Always painted as a filled red circle in the gutter, regardless
        // of verification state. Verification is a backend concept the
        // user shouldn't have to care about visually; if a breakpoint
        // truly doesn't bind, the debugger says so in the panel.
        if (breakpointLines.isNotEmpty()) {
            val radius = (row * 0.30f).coerceAtMost(dp * 6f)
            val cx = radius * 1.6f
            for ((line, _) in breakpointLines) {
                if (line < 1) continue
                val top = screenTopOfLine(line)
                if (top + row < 0 || top > viewH) continue
                val cy = top + row / 2f
                canvas.drawCircle(cx, cy, radius, bpFilled)
            }
        }
    }

    /** Draws a filled right-pointing triangle inside the gutter at [top, top+row]. */
    private fun drawGutterArrow(
        canvas: Canvas,
        top: Float,
        row: Float,
        gutterWidth: Float,
    ) {
        val pad = row * 0.22f
        val left = gutterWidth - row * 0.72f
        val right = gutterWidth - pad
        val midY = top + row / 2f
        arrowPath.rewind()
        arrowPath.moveTo(left, top + pad)
        arrowPath.lineTo(right, midY)
        arrowPath.lineTo(left, top + row - pad)
        arrowPath.close()
        canvas.drawPath(arrowPath, gutterArrowPaint)
    }

    /**
     * Scrolls the editor so [line] sits roughly in the vertical middle of
     * the viewport, instead of at the very top. VSCode does this when a
     * breakpoint hits — keeps enough context visible above the stopped
     * line that the user can see where they came from.
     *
     * Call this from the host after setting [currentExecutionLine].
     */
    companion object {
        private const val TAG = "DebugCodeEditor"
    }

    fun scrollToLineCentered(line: Int) {
        if (line < 1) return
        val row = rowHeight
        if (row <= 0 || height <= 0) {
            // Layout isn't ready yet — fall back to the default jump,
            // which will at least make the line visible once layout runs.
            try { jumpToLine(line - 1) } catch (_: Throwable) {}
            return
        }
        // Target scroll: place (line - 1) * row at (viewport height / 2)
        // from the top, but never scroll past the content boundaries.
        val desired = ((line - 1) * row - height / 2 + row / 2).coerceIn(0, scrollMaxY)
        try {
            // EditorScroller takes (distanceX, distanceY) deltas, not
            // absolute positions. Compute the delta from where we are now.
            val dx = -offsetX  // snap to left edge (no horizontal scroll
                               //                   when jumping to a line)
            val dy = desired - offsetY
            getScroller().forceFinished(true)
            getScroller().startScroll(offsetX, offsetY, dx, dy, 0)
            // Force an invalidate/recompute so the next draw reflects
            // the new scroll. On some Sora builds the scroller doesn't
            // invalidate automatically when duration=0.
            postInvalidateOnAnimation()
        } catch (_: Throwable) {
            // Fallback to the default jump if our scroller manipulation
            // fails (signature drift between Sora minor versions etc.).
            try { jumpToLine(line - 1) } catch (_: Throwable) {}
        }
    }
}
