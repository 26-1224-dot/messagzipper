package kr.messazipper.demo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면 위에 그려지는 부분. TYPE_ACCESSIBILITY_OVERLAY 를 쓰므로
 * 접근성 서비스만 켜면 되고 '다른 앱 위에 표시' 권한은 따로 필요 없다.
 *
 * - gate : 전송(종이비행기) 버튼 위에만 놓이는 완전히 투명한 판. 아무것도 그리지 않는다.
 *          전송 버튼을 누르는 순간을 가로채기 위해서만 존재한다.
 * - zip  : 잠글 때만 나타나는 채팅바 전체 크기의 지퍼.
 * - panel: 잠긴 뒤 아래에 뜨는 선택지.
 */
class Overlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val density = svc.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * density).toInt()

    var onToggleMode: (() -> Unit)? = null
    var onGateTap: ((Float, Float) -> Unit)? = null
    var onZipTap: (() -> Unit)? = null
    var onUnlockSwipe: (() -> Unit)? = null

    private var fab: TextView? = null
    private var fabKey: String? = null
    private var gate: View? = null
    private var gateParams: WindowManager.LayoutParams? = null
    private var zip: ZipView? = null
    private var panel: LinearLayout? = null

    private fun lp(w: Int, h: Int, x: Int, y: Int, gravity: Int = Gravity.TOP or Gravity.START) =
        WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { it.gravity = gravity; it.x = x; it.y = y }

    private fun add(v: View, p: WindowManager.LayoutParams) {
        try { wm.addView(v, p) } catch (_: Exception) {}
    }

    private fun remove(v: View?) {
        try {
            if (v != null) wm.removeView(v)
        } catch (_: Exception) {}
    }

    // ───────── 모드 표시 버튼(친구 ↔ 윗사람) ─────────
    /** 모드 + 읽어 낸 상대 이름을 같이 보여 준다(이름을 제대로 읽었는지 바로 확인 가능). */
    fun showFab(formal: Boolean, name: String?) {
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
            fabKey = null
            add(t, lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                dp(8), dp(120), Gravity.TOP or Gravity.END))
        }
        val shortName = name?.trim()?.let { if (it.length > 8) it.take(8) + "…" else it }?.ifBlank { null }
        val key = "$formal|$shortName"
        if (fabKey == key) return
        fabKey = key
        fab?.apply {
            val mode = if (formal) "🔒 윗사람 모드" else "🔓 친구 모드"
            text = if (shortName != null) "$mode · $shortName" else mode
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (formal) Color.parseColor("#E61E3A5F") else Color.parseColor("#E6455A64"))
            }
        }
    }

    private fun hideFab() { remove(fab); fab = null; fabKey = null }

    // ───────── 투명 게이트(전송 버튼 위에만) ─────────
    fun placeGate(r: Rect) {
        val p = lp(r.width(), r.height(), r.left, r.top)
        val g = gate
        if (g == null) {
            val v = View(svc)   // 아무것도 그리지 않는 완전 투명 판
            var downX = 0f
            var downY = 0f
            v.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; true }
                    MotionEvent.ACTION_UP -> { onGateTap?.invoke(downX, downY); true }
                    else -> true
                }
            }
            gate = v
            gateParams = p
            add(v, p)
        } else {
            val o = gateParams
            if (o == null || o.x != p.x || o.y != p.y || o.width != p.width || o.height != p.height) {
                gateParams = p
                try { wm.updateViewLayout(g, p) } catch (_: Exception) {}
            }
        }
    }

    fun hideGate() { remove(gate); gate = null; gateParams = null }

    fun hasGate() = gate != null

    /** 안전한 메시지: 게이트를 치우고 같은 자리에 진짜 탭을 보내 실제 전송 버튼이 눌리게 한다. */
    fun passThrough(x: Float, y: Float) {
        hideGate()
        main.postDelayed({
            try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 40)
                svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            } catch (_: Exception) {}
        }, 40)
    }

    // ───────── 잠글 때만 나타나는 지퍼 ─────────
    fun lockZip(row: Rect, onClosed: () -> Unit) {
        hideZip()
        val v = ZipView(svc)
        var startX = 0f
        var startProgress = 1f
        var dragging = false
        v.setOnTouchListener { view, ev ->
            val z = view as ZipView
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startProgress = z.closeProgress; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    if (startProgress > 0.9f && (dragging || dx < -dp(10))) {
                        dragging = true
                        val travel = (view.width * 0.75f).coerceAtLeast(1f)
                        z.closeProgress = (1f + dx / travel).coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (z.closeProgress < 0.6f) {
                            playZip(true)
                            animate(z, z.closeProgress, 0f, 200, AccelerateInterpolator()) {
                                hideZip()
                                onUnlockSwipe?.invoke()
                            }
                        } else {
                            animate(z, z.closeProgress, 1f, 180, DecelerateInterpolator()) {}
                        }
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP && z.closeProgress > 0.9f) {
                        onZipTap?.invoke()
                    }
                    dragging = false
                    true
                }
                else -> true
            }
        }
        zip = v
        add(v, lp(row.width(), row.height(), row.left, row.top))
        v.post {
            playZip(false)
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            animate(v, 0f, 1f, 420, DecelerateInterpolator()) {
                // 다 잠기는 순간 '턱' 하고 걸리는 느낌
                v.animate().scaleY(1.08f).setDuration(70).withEndAction {
                    v.animate().scaleY(1f).setDuration(120).start()
                }.start()
                onClosed()
            }
        }
    }

    /** 지퍼를 여는 애니메이션(소리 포함) 후 치운다. */
    fun unlockZip(onOpened: () -> Unit) {
        val v = zip
        if (v == null) { onOpened(); return }
        if (v.closeProgress < 0.05f) { hideZip(); onOpened(); return }
        playZip(true)
        animate(v, v.closeProgress, 0f, 300, AccelerateInterpolator()) {
            hideZip()
            onOpened()
        }
    }

    fun hideZip() { remove(zip); zip = null }

    private fun playZip(reverse: Boolean) { Thread { SoundFx.playZip(reverse) }.start() }

    private fun animate(v: ZipView, from: Float, to: Float, ms: Long, interp: TimeInterpolator, onEnd: () -> Unit) {
        val a = ValueAnimator.ofFloat(from, to)
        a.duration = ms
        a.interpolator = interp
        a.addUpdateListener { v.closeProgress = it.animatedValue as Float }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { onEnd() }
        })
        a.start()
    }

    // ───────── 잠금 안내 / 변환 결과 패널 ─────────
    fun showLockPanel(
        reason: String, mood: String, convertLabel: String,
        onConvert: () -> Unit, onRewrite: () -> Unit, onSendAnyway: () -> Unit
    ) {
        hidePanel()
        val p = card()
        p.addView(label("🔒 지퍼가 잠겼어요", 16f, true, "#1B2B34"))
        p.addView(label(reason, 14f, false, "#1B2B34"))
        if (mood.isNotEmpty()) p.addView(label(mood, 11f, false, "#6B7A85"))
        p.addView(label("← 지퍼를 왼쪽으로 밀어도 풀 수 있어요", 11f, false, "#6B7A85"))
        p.addView(row(
            btn(convertLabel, "#1E3A5F", Color.WHITE, onConvert),
            btn("다시 쓰기", "#E8ECEF", Color.parseColor("#1B2B34"), onRewrite),
            btn("그대로 보내기", "#FFFFFF", Color.parseColor("#B3261E"), onSendAnyway)
        ))
        panel = p
        add(p, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0, Gravity.BOTTOM))
    }

    fun showLoading() {
        hidePanel()
        val p = card()
        p.addView(label("말투를 바꾸는 중…", 15f, true, "#1B2B34"))
        panel = p
        add(p, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0, Gravity.BOTTOM))
    }

    fun showCompare(original: String, converted: String, note: String, onUse: () -> Unit, onCancel: () -> Unit) {
        hidePanel()
        val p = card()
        p.addView(label("원래 문장", 11f, true, "#6B7A85"))
        p.addView(label(original, 14f, false, "#1B2B34"))
        p.addView(label("바꾼 문장", 11f, true, "#6B7A85"))
        p.addView(label(converted, 15f, true, "#1E3A5F"))
        if (note.isNotEmpty()) p.addView(label(note, 11f, false, "#6B7A85"))
        p.addView(row(
            btn("이걸로 바꾸기", "#1E3A5F", Color.WHITE, onUse),
            btn("취소", "#E8ECEF", Color.parseColor("#1B2B34"), onCancel)
        ))
        panel = p
        add(p, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0, Gravity.BOTTOM))
    }

    fun hidePanel() { remove(panel); panel = null }

    fun hideAll() { hideFab(); hideGate(); hideZip(); hidePanel() }

    // ───────── 작은 UI 도우미 ─────────
    private fun card() = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(18))
        background = GradientDrawable().apply {
            val r = dp(20).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(Color.WHITE)
        }
    }

    private fun label(s: String, size: Float, bold: Boolean, color: String) = TextView(svc).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun row(vararg buttons: Button) = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        buttons.forEachIndexed { i, b ->
            val p = LinearLayout.LayoutParams(0, dp(46), 1f)
            if (i > 0) p.leftMargin = dp(6)
            addView(b, p)
        }
    }

    private fun btn(s: String, bg: String, fg: Int, click: () -> Unit) = Button(svc).apply {
        text = s
        textSize = 13f
        isAllCaps = false
        setTextColor(fg)
        setPadding(dp(4), 0, dp(4), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor(bg))
            setStroke(dp(1), Color.parseColor("#C9D1D6"))
        }
        setOnClickListener { click() }
    }

    /**
     * 채팅바 전체를 덮는 지퍼 그림.
     * closeProgress 0→1 이면 손잡이가 왼쪽에서 오른쪽으로 달리며 이빨이 맞물리고,
     * 손잡이가 지나간 자리만 어둡게 덮인다. 1→0 이면 반대로 풀린다.
     */
    private class ZipView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val cover = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F21E2830") }
        private val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF34424E") }
        private val tooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD5DBE1") }
        private val toothShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF9AA5AF") }
        private val slider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF2B134") }
        private val sliderEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB07A12"); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        }
        private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF1E2830") }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD
        }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC0E1418") }

        var closeProgress = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f || closeProgress <= 0f) return
            val cy = h / 2f
            val sliderW = h * 0.62f
            val tabW = h * 0.5f
            val startX = h * 0.35f
            val endX = w - sliderW / 2f - tabW - 4f * d
            val sx = startX + (endX - startX) * closeProgress
            val coverRight = (sx + sliderW / 2f + tabW + 4f * d).coerceAtMost(w)
            val rr = h * 0.28f

            // 1) 잠긴 부분만 덮는다
            c.drawRoundRect(0f, 0f, coverRight, h, rr, rr, cover)

            // 2) 지퍼 천(테이프)
            val tapeH = h * 0.30f
            c.drawRect(startX - h * 0.2f, cy - tapeH / 2f, sx, cy + tapeH / 2f, tape)

            // 3) 맞물린 이빨
            val tw = h * 0.085f
            val tl = h * 0.13f
            val half = tw * 1.1f
            var x = startX
            var up = true
            while (x < sx - sliderW / 2f) {
                if (up) c.drawRoundRect(x, cy - tl, x + tw, cy + tl * 0.35f, tw / 3f, tw / 3f, tooth)
                else c.drawRoundRect(x, cy - tl * 0.35f, x + tw, cy + tl, tw / 3f, tw / 3f, toothShade)
                up = !up
                x += half
            }

            // 4) 손잡이 앞쪽: 아직 안 잠긴 이빨이 위아래로 벌어져 있음
            if (closeProgress < 0.999f) {
                val from = sx + sliderW / 2f
                val openLen = h * 1.6f
                var ox = from
                var k = 0
                while (ox < from + openLen && ox < w) {
                    val gap = (ox - from) / openLen * h * 0.32f
                    if (k % 2 == 0) c.drawRoundRect(ox, cy - tl - gap, ox + tw, cy - gap + tl * 0.2f, tw / 3f, tw / 3f, tooth)
                    else c.drawRoundRect(ox, cy + gap - tl * 0.2f, ox + tw, cy + tl + gap, tw / 3f, tw / 3f, toothShade)
                    ox += half
                    k++
                }
            }

            // 5) 손잡이 몸통 + 당김 탭
            val bodyTop = cy - h * 0.27f
            val bodyBottom = cy + h * 0.27f
            c.drawRoundRect(sx - sliderW / 2f, bodyTop, sx + sliderW / 2f, bodyBottom, h * 0.12f, h * 0.12f, slider)
            c.drawRoundRect(sx - sliderW / 2f, bodyTop, sx + sliderW / 2f, bodyBottom, h * 0.12f, h * 0.12f, sliderEdge)
            val tabL = sx + sliderW / 2f - h * 0.04f
            c.drawRoundRect(tabL, cy - h * 0.13f, tabL + tabW, cy + h * 0.13f, h * 0.13f, h * 0.13f, slider)
            c.drawCircle(tabL + tabW - h * 0.14f, cy, h * 0.05f, hole)

            // 6) 다 잠기면 안내 문구
            if (closeProgress > 0.97f) {
                val msg = "🔒 잠김   ← 밀어서 풀기"
                val mw = textPaint.measureText(msg)
                val areaL = startX
                val areaR = sx - sliderW / 2f
                val px = (areaL + (areaR - areaL) / 2f - mw / 2f).coerceAtLeast(8f * d)
                val ph = textPaint.textSize * 1.8f
                c.drawRoundRect(px - 10f * d, cy - ph / 2f, px + mw + 10f * d, cy + ph / 2f, ph / 2f, ph / 2f, pill)
                c.drawText(msg, px, cy + textPaint.textSize * 0.36f, textPaint)
            }
        }
    }
}
