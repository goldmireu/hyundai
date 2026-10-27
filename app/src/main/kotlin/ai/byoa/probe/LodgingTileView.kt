package ai.byoa.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 숙소 확인 화면의 예시 일러스트(외관·객실·전망).
 * 실제 숙소 사진은 사용자가 연결한 숙소 에이전트가 제공한다. 시연에서는 단순한 그림으로 대신한다.
 */
class LodgingTileView(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { EXTERIOR, ROOM, VIEW }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, w, h), 24f, 24f, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        when (kind) {
            Kind.EXTERIOR -> exterior(c, w, h)
            Kind.ROOM -> room(c, w, h)
            Kind.VIEW -> view(c, w, h)
        }
        c.restore()
    }

    private fun sky(c: Canvas, w: Float, h: Float, until: Float) {
        p.shader = LinearGradient(0f, 0f, 0f, until, 0xFF8EC9F0.toInt(), 0xFFDCEFFB.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, until, p); p.shader = null
    }

    private fun sea(c: Canvas, w: Float, top: Float, h: Float) {
        p.shader = LinearGradient(0f, top, 0f, h, 0xFF2F7FB8.toInt(), 0xFF164E78.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, top, w, h, p); p.shader = null
        p.color = 0x55FFFFFF
        for (i in 0..5) c.drawRect(w * (0.08f + i * 0.15f), top + (h - top) * 0.3f, w * (0.16f + i * 0.15f), top + (h - top) * 0.3f + 4f, p)
    }

    private fun exterior(c: Canvas, w: Float, h: Float) {
        sky(c, w, h, h * 0.62f)
        sea(c, w, h * 0.62f, h)
        p.color = 0xFFF4F1EA.toInt()
        val l = w * 0.22f; val r = w * 0.78f; val t = h * 0.18f; val b = h * 0.78f
        c.drawRect(l, t, r, b, p)
        p.color = 0xFF2D5B7A.toInt()
        val cols = 5; val rows = 6
        for (i in 0 until cols) for (j in 0 until rows) {
            val x = l + (r - l) * (0.08f + i * 0.18f); val y = t + (b - t) * (0.06f + j * 0.15f)
            c.drawRect(x, y, x + (r - l) * 0.1f, y + (b - t) * 0.08f, p)
        }
        p.color = 0xFFC9B79C.toInt(); c.drawRect(0f, h * 0.78f, w, h * 0.84f, p)
    }

    private fun room(c: Canvas, w: Float, h: Float) {
        p.color = 0xFFEDE6DA.toInt(); c.drawRect(0f, 0f, w, h, p)
        // 창 너머 바다
        val wl = w * 0.52f; val wr = w * 0.92f; val wt = h * 0.12f; val wb = h * 0.52f
        sky(c, w, h, 0f)
        p.shader = LinearGradient(0f, wt, 0f, wb, 0xFF9ED3F2.toInt(), 0xFF2F7FB8.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(wl, wt, wr, wb, p); p.shader = null
        p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = 6f
        c.drawLine((wl + wr) / 2, wt, (wl + wr) / 2, wb, p)
        p.color = 0xFFB89F7E.toInt(); c.drawRect(0f, h * 0.78f, w, h, p)         // 바닥
        p.color = 0xFF7A5A43.toInt(); c.drawRect(w * 0.06f, h * 0.40f, w * 0.12f, h * 0.70f, p) // 헤드보드
        p.color = 0xFFFFFFFF.toInt(); c.drawRoundRect(RectF(w * 0.10f, h * 0.55f, w * 0.62f, h * 0.76f), 16f, 16f, p) // 침대
        p.color = 0xFFDDE7EF.toInt()
        c.drawRoundRect(RectF(w * 0.13f, h * 0.49f, w * 0.27f, h * 0.58f), 12f, 12f, p)
        c.drawRoundRect(RectF(w * 0.13f, h * 0.60f, w * 0.27f, h * 0.68f), 12f, 12f, p)
        p.color = 0xFF3D7BD9.toInt(); c.drawRect(w * 0.36f, h * 0.55f, w * 0.62f, h * 0.76f, p)  // 이불
    }

    private fun view(c: Canvas, w: Float, h: Float) {
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0xFFF6B26B.toInt(), 0xFFFCE3C2.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * 0.55f, p); p.shader = null
        p.color = 0xFFFFE08A.toInt(); c.drawCircle(w * 0.68f, h * 0.42f, h * 0.12f, p)
        sea(c, w, h * 0.55f, h)
        p.color = 0xFF2E5E4E.toInt()
        val island = Path().apply {
            moveTo(w * 0.05f, h * 0.56f); quadTo(w * 0.22f, h * 0.30f, w * 0.42f, h * 0.56f); close()
        }
        c.drawPath(island, p)
    }
}
