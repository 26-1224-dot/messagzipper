package kr.messazipper.demo

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
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
    /** 지퍼를 왼쪽으로 스와이프해서 직접 풀었을 때(= 그대로 보내기와 같은 동작) */
    var onUnlockSwipe: (() -> Unit)? = null
    /**
     * 전송을 시도한(=게이트를 탭한) 바로 그 순간 한 번만 불린다.
     * true 를 반환하면 잠그고, false 를 반환하면 안전하다는 뜻 — 이땐 바로
     * 아래 실제 전송 버튼으로 탭을 그대로 흘려보낸다. 타이핑하는 동안에는
     * 전혀 호출되지 않는다(상시 경보가 아니라 누르는 순간 한 번뿐인 방지턱).
     */
    var onSendAttempt: (() -> Boolean)? = null

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

    // ───────── 전송 버튼 위에 덮이는(평소엔 안 보이는) 게이트 ─────────
    /**
     * 카톡/DM 채팅창에 입력칸이 보이는 동안은 계속 이 자리에 '투명한 터치
     * 차단판'이 떠 있어야 한다 — 실제 전송 버튼을 가로채려면 누르기 전부터
     * 이미 그 자리를 선점하고 있어야 하기 때문. 하지만 평소엔 아무 그림도
     * 그리지 않아 타이핑하는 동안은 화면이 전혀 바뀌지 않는다(상시 경보
     * 아님). 실제로 그 자리를 탭(=전송 시도)하는 그 순간에만 onSendAttempt
     * 로 지금 문구를 판정해서, 안전하면 그대로 아래 실제 버튼으로 탭을
     * 흘려보내고, 위험하면 그때 비로소 zip! 소리와 잠그는 애니메이션이
     * 재생된다.
     */
    fun armGate(r: Rect) {
        if (cover != null) return   // 이미 떠 있으면 다시 만들지 않는다(애니메이션/드래그 상태 보존)
        val w = maxOf(r.width(), dp(44))
        val h = maxOf(r.height(), dp(44))
        var dragStartX = 0f
        var downX = 0f
        var downY = 0f
        var dragging = false
        val v = ZipView(svc).apply { closeProgress = 0f }
        v.setOnTouchListener { view, ev ->
            val zv = view as ZipView
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = ev.rawX; downX = ev.rawX; downY = ev.rawY; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - dragStartX
                    // 이미 잠긴(닫힌) 상태에서만 왼쪽으로 끌면 풀리는 중으로 인식한다.
                    if (zv.closeProgress > 0.5f && dx < -dp(8)) {
                        dragging = true
                        val ww = view.width.toFloat().coerceAtLeast(1f)
                        val openAmount = (-dx / (ww * 0.7f)).coerceIn(0f, 1f)
                        zv.closeProgress = 1f - openAmount
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (zv.closeProgress < 0.55f) {
                            playZip(true)
                            animateProgress(zv, zv.closeProgress, 0f) { onUnlockSwipe?.invoke() }
                        } else {
                            animateProgress(zv, zv.closeProgress, 1f) {}
                        }
                    } else {
                        // 드래그가 아니면 탭 = 지금 막 전송을 시도한 것 — 바로 이 순간에만 판정한다.
                        val shouldLock = onSendAttempt?.invoke() ?: true
                        if (shouldLock) {
                            if (zv.closeProgress < 0.98f) {
                                playZip(false)
                                animateProgress(zv, zv.closeProgress, 1f) { onCoverTap?.invoke() }
                            } else {
                                onCoverTap?.invoke()
                            }
                        } else {
                            // 안전함: 이 게이트를 치우고, 방금 그 탭을 실제 버튼으로 그대로 흘려보낸다.
                            hideCover()
                            tapThrough(downX, downY)
                        }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
        cover = v
        add(v, lp(w, h, r.left, r.top))
    }

    /** 타이핑 중에 이전 잠금 모습(닫힌 지퍼)이 남아 있지 않도록 조용히 되돌린다. */
    fun resetGateVisual() { cover?.closeProgress = 0f }

    fun hasCover() = cover != null

    fun hideCover() { remove(cover); cover = null }

    private fun playZip(reverse: Boolean) { Thread { SoundFx.playZip(reverse) }.start() }

    /** 우리 게이트를 치운 바로 다음, 같은 위치에 진짜 탭을 한 번 더 보내 실제 버튼이 눌리게 한다. */
    private fun tapThrough(x: Float, y: Float) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val path = android.graphics.Path().apply { moveTo(x, y) }
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 40)
                val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                svc.dispatchGesture(gesture, null, null)
            } catch (_: Exception) { }
        }, 30)
    }

    private fun animateProgress(v: ZipView, from: Float, to: Float, onEnd: () -> Unit) {
        val anim = ValueAnimator.ofFloat(from, to)
        anim.duration = 260
        anim.addUpdateListener { v.closeProgress = it.animatedValue as Float }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { onEnd() }
        })
        anim.start()
    }

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

    /**
     * 지퍼 그림. closeProgress 0 = 아직 열려 있음(타이핑 중, 얇은 테두리만),
     * 1 = 완전히 잠김(이빨이 끝까지 맞물리고 손잡이가 오른쪽 끝에 와 있음).
     * 0→1로 애니메이션되면 손잡이가 왼쪽에서 오른쪽으로 이동하며 이빨이
     * 차례로 맞물리는 것처럼 보인다. 1→0은 그 반대(지퍼를 여는 동작).
     */
    private class ZipView(ctx: android.content.Context) : FrameLayout(ctx) {
        private val bgClosed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F01B2B34") }
        private val bgOpenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#661B2B34"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val tooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9D1D6"); strokeWidth = 3f }
        private val pull = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2B134") }
        private val pullDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#99F2B134") }

        var closeProgress: Float = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }

        init { setWillNotDraw(false) }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cy = h / 2
            val startX = w * 0.12f
            val trackW = w * 0.66f
            val pullX = startX + trackW * closeProgress

            if (closeProgress <= 0.02f) {
                // 아직 안 잠김: 테두리만 살짝 보여서 '여기 있다'는 정도만 표시.
                c.drawRoundRect(2f, 2f, w - 2f, h - 2f, h / 4, h / 4, bgOpenStroke)
                c.drawCircle(startX, cy, h * 0.1f, pullDim)
                return
            }

            c.drawRoundRect(0f, 0f, w, h, h / 4, h / 4, bgClosed)
            var x = startX
            var up = true
            while (x < pullX) {
                c.drawLine(x, if (up) cy - h * 0.12f else cy, x, if (up) cy else cy + h * 0.12f, tooth)
                up = !up; x += w * 0.05f
            }
            c.drawCircle(pullX, cy, h * 0.16f, pull)
        }
    }
}
