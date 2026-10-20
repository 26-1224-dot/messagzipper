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

    /** 화면에서 전송 버튼을 찾아 위치를 얻는다. 못 찾으면 입력칸 오른쪽 끝을 대신 쓴다(대략적 위치). */
    private fun findSendBounds(): Rect {
        val r = Rect()
        val root = rootInActiveWindow
        if (root != null) {
            val screenW = resources.displayMetrics.widthPixels
            for (label in sendLabels) {
                for (n in root.findAccessibilityNodeInfosByText(label)) {
                    if (!n.isClickable && n.parent?.isClickable != true) continue
                    n.getBoundsInScreen(r)
                    if (r.width() in 1 until screenW / 3) return Rect(r)
                }
            }
        }
        val b = Rect()
        inputNode?.getBoundsInScreen(b)
        val size = (56 * resources.displayMetrics.density).toInt()
        return Rect(b.right - size, b.top, b.right, b.top + size.coerceAtLeast(b.height()))
    }

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
