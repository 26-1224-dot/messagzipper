package kr.messazipper.demo

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ZipperService : AccessibilityService() {

    private lateinit var overlay: Overlay
    private var inputNode: AccessibilityNodeInfo? = null
    private var currentText = ""
    private var allowedText: String? = null   // 사용자가 '그대로 보내기' 또는 변환 적용한 문장
    private var lastReason = ""

    private val targets = setOf("com.kakao.talk", "com.instagram.android")
    private val sendLabels = listOf("전송", "보내기", "Send")

    override fun onServiceConnected() {
        overlay = Overlay(this)
        overlay.onToggleMode = {
            Prefs.setFormal(this, !Prefs.formal(this))
            overlay.showFab(Prefs.formal(this))
            evaluate(currentText)
        }
        overlay.onCoverTap = { openLockPanel() }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in targets || !Prefs.enabled(this)) {
            overlay.hideAll()
            return
        }
        overlay.showFab(Prefs.formal(this))

        if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val node = e.source ?: return
            if (!node.isEditable) return
            inputNode = node
            // 입력칸이 비어 있을 때 힌트 문구가 text 로 잡히는 경우를 걸러낸다.
            val t = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
            currentText = t
            evaluate(t)
        }
    }

    private fun evaluate(text: String) {
        if (text.isBlank() || text == allowedText) {
            overlay.hideCover()
            return
        }
        val r = Judge.judge(text, Prefs.formal(this), Prefs.sens(this))
        if (r.lock) {
            lastReason = r.reason
            overlay.showCover(findSendBounds())
        } else {
            overlay.hideCover()
            overlay.hidePanel()
        }
    }

    /**
     * 전송 버튼의 정확한 위치를 매번 믿고 찾기는 어렵다(카톡 전송 버튼은
     * 글자 없는 아이콘이라 텍스트로 못 찾는 경우가 많다). 그래서 기본은
     * 입력칸과 같은 줄 전체를 화면 끝까지 덮어서, 전송 버튼이 그 줄 어디에
     * 있든 같이 막히게 한다. 텍스트로 된 전송 버튼을 찾으면 그 버튼까지
     * 포함하도록 범위를 넓힌다.
     */
    private fun findSendBounds(): Rect {
        val b = Rect()
        inputNode?.getBoundsInScreen(b)
        val screenW = resources.displayMetrics.widthPixels
        // 입력칸이 있는 줄 전체: 왼쪽 끝부터 화면 오른쪽 끝까지, 위아래로 살짝 여유를 둔다.
        val pad = (6 * resources.displayMetrics.density).toInt()
        var row = Rect(0, (b.top - pad).coerceAtLeast(0), screenW, b.bottom + pad)

        val root = rootInActiveWindow
        if (root != null) {
            for (label in sendLabels) {
                for (n in root.findAccessibilityNodeInfosByText(label)) {
                    if (!n.isClickable && n.parent?.isClickable != true) continue
                    val br = Rect()
                    n.getBoundsInScreen(br)
                    if (br.width() in 1 until screenW / 2 && br.top in (b.top - dp(80))..(b.bottom + dp(80))) {
                        // 버튼이 입력칸과 비슷한 높이에 있을 때만 범위를 그 버튼까지 넓힌다.
                        row = Rect(row.left, minOf(row.top, br.top), maxOf(row.right, br.right), maxOf(row.bottom, br.bottom))
                    }
                }
            }
        }
        return row
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun openLockPanel() {
        overlay.showLockPanel(
            reason = lastReason,
            onConvert = { convertFlow() },
            onSendAnyway = {
                allowedText = currentText
                overlay.hidePanel()
                overlay.hideCover()       // 덮개가 사라지면 실제 전송 버튼을 직접 누르면 된다
            },
            onRewrite = { overlay.hidePanel() }
        )
    }

    private fun convertFlow() {
        val original = currentText
        val key = Prefs.apiKey(this)
        overlay.showLoading()
        if (key.isEmpty()) {
            overlay.showCompare(original, Judge.localConvert(original),
                "API 키가 없어 기기 안의 간단 변환을 썼어요. 어색할 수 있어요.",
                { applyText(Judge.localConvert(original)) }, { overlay.hidePanel() })
            return
        }
        Llm.convert(key, original) { res ->
            if (res == null) {
                overlay.showCompare(original, Judge.localConvert(original),
                    "변환 서비스에 연결하지 못해 기기 안의 간단 변환을 썼어요.",
                    { applyText(Judge.localConvert(original)) }, { overlay.hidePanel() })
            } else {
                overlay.showCompare(original, res, "",
                    { applyText(res) }, { overlay.hidePanel() })
            }
        }
    }

    /** 입력칸의 글을 변환문으로 바꾼다. 전송은 사용자가 직접 누른다. */
    private fun applyText(newText: String) {
        allowedText = newText
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        inputNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        overlay.hidePanel()
        overlay.hideCover()
    }

    override fun onInterrupt() { overlay.hideAll() }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.hideAll()
        super.onDestroy()
    }
}
