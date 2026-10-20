package kr.messazipper.demo

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면 위에 그려지는 부분. TYPE_ACCESSIBILITY_OVERLAY 를 쓰므로
 * 접근성 서비스만 켜면 되고 '다른 앱 위에 표시' 권한은 따로 필요 없다.
 */
class Overlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val density = svc.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    var onToggleMode: (() -> Unit)? = null
    var onCoverTap: (() -> Unit)? = null

    private var fab: TextView? = null
    private var cover: ZipView? = null
    private var panel: LinearLayout? = null

    private fun lp(w: Int, h: Int, x: Int, y: Int, gravity: Int = Gravity.TOP or Gravity.START) =
        WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { it.gravity = gravity; it.x = x; it.y = y }

    private fun add(v: View, p: WindowManager.LayoutParams) = try { wm.addView(v, p) } catch (_: Exception) {}
    private fun remove(v: View?) {
        try {
            if (v != null) wm.removeView(v)
        } catch (_: Exception) {}
    }

    // ───────── 떠 있는 지퍼 버튼(친구 ↔ 윗사람 모드 전환) ─────────
    fun showFab(formal: Boolean) {
        if (fab == null) {
            val t = TextView(svc).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { onToggleMode?.invoke() }
            }
            fab = t
            add(t, lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                dp(8), dp(120), Gravity.TOP or Gravity.END))
        }
        fab?.apply {
            text = if (formal) "🔒 윗사람 모드" else "🔓 친구 모드"
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (formal) Color.parseColor("#E61E3A5F") else Color.parseColor("#E6455A64"))
            }
        }
    }

    // ───────── 전송 버튼 위에 덮이는 지퍼 ─────────
    fun showCover(r: Rect) {
        hideCover()
        val w = maxOf(r.width(), dp(44))
        val h = maxOf(r.height(), dp(44))
        val v = ZipView(svc).apply {
            setOnClickListener { onCoverTap?.invoke() }
            alpha = 0f
            scaleX = 0.6f
            animate().alpha(1f).scaleX(1f).setDuration(180).start()
        }
        cover = v
        add(v, lp(w, h, r.left, r.top))
    }

    fun hideCover() { remove(cover); cover = null }

    // ───────── 잠금 안내 / 변환 결과 패널 ─────────
    fun showLockPanel(reason: String, onConvert: () -> Unit, onSendAnyway: () -> Unit, onRewrite: () -> Unit) {
        hidePanel()
        val p = card()
        p.addView(label("🔒 지퍼가 잠겼어요", 17f, true))
        p.addView(label(reason, 14f, false))
        p.addView(btn("말투 변환하기", "#1E3A5F", Color.WHITE, onConvert))
        p.addView(btn("다시 쓰기", "#E8ECEF", Color.parseColor("#1B2B34"), onRewrite))
        p.addView(btn("지퍼를 열고 그대로 보내기", "#FFFFFF", Color.parseColor("#B3261E"), onSendAnyway))
        panel = p
        add(p, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            0, 0, Gravity.BOTTOM))
    }

    fun showLoading() {
        hidePanel()
        val p = card()
        p.addView(label("말투를 바꾸는 중…", 15f, true))
        panel = p
        add(p, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0, Gravity.BOTTOM))
    }

    fun showCompare(original: String, converted: String, note: String, onUse: () -> Unit, onCancel: () -> Unit) {
        hidePanel()
        val p = card()
        p.addView(label("원문", 12f, true))
        p.addView(label(original, 14f, false))
        p.addView(label("변환문", 12f, true))
        p.addView(label(converted, 15f, true))
        if (note.isNotEmpty()) p.addView(label(note, 11f, false))
        p.addView(btn("변환문으로 바꾸기", "#1E3A5F", Color.WHITE, onUse))
        p.addView(btn("취소", "#E8ECEF", Color.parseColor("#1B2B34"), onCancel))
        panel = p
        add(p, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0, Gravity.BOTTOM))
    }

    fun hidePanel() { remove(panel); panel = null }

    fun hideAll() { remove(fab); fab = null; hideCover(); hidePanel() }

    // ───────── 작은 UI 도우미 ─────────
    private fun card() = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(24))
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            setColor(Color.WHITE)
        }
    }

    private fun label(s: String, size: Float, bold: Boolean) = TextView(svc).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor("#1B2B34"))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun btn(s: String, bg: String, fg: Int, click: () -> Unit) = Button(svc).apply {
        text = s
        isAllCaps = false
        setTextColor(fg)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor(bg))
            setStroke(dp(1), Color.parseColor("#C9D1D6"))
        }
        val lpp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        lpp.topMargin = dp(8)
        layoutParams = lpp
        setOnClickListener { click() }
    }

    /** 닫힌 지퍼를 흉내 낸 간단한 그림(가운데 줄 + 이빨 + 손잡이). */
    private class ZipView(ctx: android.content.Context) : FrameLayout(ctx) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F01B2B34") }
        private val tooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9D1D6"); strokeWidth = 3f }
        private val pull = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2B134") }
        init { setWillNotDraw(false) }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawRoundRect(0f, 0f, w, h, h / 4, h / 4, bg)
            val cy = h / 2
            var x = w * 0.12f
            var up = true
            while (x < w * 0.72f) {
                c.drawLine(x, if (up) cy - h * 0.12f else cy, x, if (up) cy else cy + h * 0.12f, tooth)
                up = !up; x += w * 0.05f
            }
            c.drawCircle(w * 0.78f, cy, h * 0.16f, pull)
        }
    }
}
