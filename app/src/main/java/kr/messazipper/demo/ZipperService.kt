package kr.messazipper.demo

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 동작 원칙
 * 1) 타이핑하는 동안 화면에는 아무것도 뜨지 않는다.
 *    전송(종이비행기) 버튼 위에 '투명한 판'만 깔아 둔다.
 * 2) 전송 버튼을 누른 그 순간에만, 화면의 최근 대화 + 보낼 문장을 보고 판단한다.
 *    (API 키가 있으면 AI가 관계·분위기·감정을 읽고, 없으면 기기 안 규칙으로)
 * 3) 괜찮으면 → 아무것도 안 뜨고 실제 전송 버튼이 그대로 눌린다.
 *    감정에 휩싸인 메시지면 → 그때 zip! 소리와 함께 채팅바 전체에 지퍼가 잠긴다.
 */
class ZipperService : AccessibilityService() {

    private lateinit var overlay: Overlay
    private val main = Handler(Looper.getMainLooper())

    private var inputNode: AccessibilityNodeInfo? = null
    private var currentText = ""
    private var allowedText: String? = null   // 그대로 보내기/풀기/변환 적용한 문장 → 다시 막지 않음
    private var lockedText: String? = null    // 지금 지퍼에 잠겨 있는 문장
    private var verdict: Verdict? = null
    private var lastInput: JudgeInput? = null

    private var lastRoomTitle: String? = null
    private var deciding = false
    private var lastPlace = 0L

    private val targets = setOf("com.kakao.talk", "com.instagram.android")
    private val ignorePkgs = setOf("com.android.systemui")
    private val sendLabels = listOf("전송", "보내기", "Send")

    private val cache = HashMap<String, Verdict>()
    private val inflight = HashMap<String, MutableList<(Verdict) -> Unit>>()

    /** 타이핑을 잠깐 멈추면 미리 AI 판단을 받아 둔다(화면엔 아무것도 안 뜸). 전송 때 바로 쓰려고. */
    private val prejudge = Runnable {
        if (currentText.isNotBlank() && lockedText == null) requestVerdict(gather(currentText)) {}
    }

    private val screenW get() = resources.displayMetrics.widthPixels
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 채팅방 제목(상대 이름)에 윗사람 단서가 있으면 윗사람 모드로. */
    private val formalHints = Regex(
        "(선생님|쌤|담임|교생|교수|교감|교장|목사|전도사|사모|과장|부장|팀장|실장|차장|대리|이사|주임|" +
        "사장|대표|회장|원장|관장|감독|코치|교관|국장|처장|선배|" +
        "어머니|아버지|엄마|아빠|할머니|할아버지|이모|고모|삼촌|작은아버지|큰아버지)"
    )

    // ───────── 상대 이름(방 제목) 읽기 ─────────
    private val NUM_OR_TIME = Regex("^\\d+$|^(오전|오후)\\s?\\d{1,2}:\\d{2}$|^\\d{1,2}:\\d{2}$")
    private var pendingTitle: String? = null
    private var lastTitleCheck = 0L
    private val checkRoomTask = Runnable { checkRoom(force = false) }

    /** 화면 맨 위 제목줄(뒤로가기 버튼 옆)에 있는 이름을 읽는다. */
    private fun readRoomTitle(): String? {
        val root = rootInActiveWindow ?: return null
        val sh = resources.displayMetrics.heightPixels
        val sw = screenW
        var bestScore = Int.MAX_VALUE
        var bestText: String? = null
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25) return
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && !n.isEditable && t.length <= 30 && !NUM_OR_TIME.matches(t)) {
                val b = Rect()
                n.getBoundsInScreen(b)
                if (b.height() > 0 && b.top < sh * 0.11f && b.bottom < sh * 0.15f &&
                    b.left > sw * 0.05f && b.left < sw * 0.6f) {
                    val id = n.viewIdResourceName ?: ""
                    val idBonus = if (id.contains("title", true) || id.contains("name", true)) 0 else 1000
                    val score = idBonus + b.top + b.left / 10
                    if (score < bestScore) { bestScore = score; bestText = t }
                }
            }
            for (k in 0 until n.childCount) visit(n.getChild(k), depth + 1)
        }
        visit(root, 0)
        return bestText
    }

    private var roomEnteredAt = 0L
    private val forcedCheck = Runnable { checkRoom(force = true) }
    private val forcedCheck2 = Runnable { checkRoom(force = true) }
    private val forcedCheck3 = Runnable { checkRoom(force = true) }

    /**
     * 방이 바뀌었는지 확인.
     * force=true  : 화면 전환 직후(확실한 신호) → 읽히는 즉시 확정
     * force=false : 평소 화면 갱신 중 → 같은 이름이 두 번 연속 읽혀야 확정(잘못 읽어 흔들리지 않게)
     */
    private fun checkRoom(force: Boolean) {
        lastTitleCheck = SystemClock.uptimeMillis()
        val title = readRoomTitle() ?: return
        if (title == lastRoomTitle) {
            pendingTitle = null
            // 방에 막 들어온 직후엔 대화가 늦게 그려지므로, 저장된 모드가 없으면 대화 말투로 한 번 더 판단
            if (Prefs.roomMode(this, title) == null && lastTitleCheck - roomEnteredAt < 1500) applyAutoMode(title)
            return
        }
        if (!force && pendingTitle != title) { pendingTitle = title; main.postDelayed(checkRoomTask, 250); return }
        pendingTitle = null
        lastRoomTitle = title
        roomEnteredAt = lastTitleCheck
        resetRoom()
        val saved = Prefs.roomMode(this, title)
        if (saved != null) Prefs.setFormal(this, saved) else applyAutoMode(title)
        overlay.showFab(Prefs.formal(this), title)
    }

    /** 이름 단서 + 이 방에서 내가 계속 존댓말을 쓰는지로 자동 판정 */
    private fun applyAutoMode(title: String) {
        val formal = guessFormal(title) || honorificConversation(currentLines())
        if (formal != Prefs.formal(this)) {
            Prefs.setFormal(this, formal)
            cache.clear()
        }
        overlay.showFab(formal, title)
    }

    private fun guessFormal(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return false
        return formalHints.containsMatchIn(t) || t.endsWith("님")
    }

    private fun honorificConversation(lines: List<ChatLine>): Boolean {
        val mine = lines.filter { it.mine }.takeLast(5)
        val hon = mine.count { Judge.isHonorific(it.text) }
        return mine.size >= 2 && hon * 10 >= mine.size * 7
    }

    private fun currentLines(): List<ChatLine> {
        val b = Rect()
        inputNode?.getBoundsInScreen(b)
        val inputTop = if (b.isEmpty) resources.displayMetrics.heightPixels else b.top
        return ChatReader.read(rootInActiveWindow, inputTop, screenW, dp(96), lastRoomTitle)
    }

    override fun onServiceConnected() {
        overlay = Overlay(this)
        overlay.onToggleMode = {
            val f = !Prefs.formal(this)
            Prefs.setFormal(this, f)
            lastRoomTitle?.let { Prefs.setRoomMode(this, it, f) }   // 이 방은 앞으로 이 모드로 기억
            cache.clear()
            overlay.showFab(f, lastRoomTitle)
        }
        overlay.onGateTap = { x, y -> onSendTapped(x, y) }
        overlay.onZipTap = { openLockPanel() }
        overlay.onUnlockSwipe = { unlock(sendAfter = false, allow = true) }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignorePkgs) return

        if (pkg !in targets) {
            // 키보드 같은 다른 창의 이벤트일 수도 있으니, 실제 앞에 있는 앱이 카톡/인스타가 아닐 때만 숨긴다.
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val active = rootInActiveWindow?.packageName?.toString()
                if (active == null || active !in targets) leaveApp()
            }
            return
        }
        if (!Prefs.enabled(this)) { leaveApp(); return }

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 화면이 바뀌면 제목줄을 여러 번(빨리 → 다 그려진 뒤) 읽어서 바로 반영한다.
                main.removeCallbacks(checkRoomTask)
                main.removeCallbacks(forcedCheck); main.removeCallbacks(forcedCheck2); main.removeCallbacks(forcedCheck3)
                main.postDelayed(forcedCheck, 120)
                main.postDelayed(forcedCheck2, 450)
                main.postDelayed(forcedCheck3, 1000)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(e)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastTitleCheck > 500) checkRoom(force = false)
                if (lockedText == null && now - lastPlace > 300) {
                    lastPlace = now
                    if (inputNode == null) recoverInput()
                    if (currentText.isNotBlank()) placeGate()
                }
            }
        }
        overlay.showFab(Prefs.formal(this), lastRoomTitle)
    }

    private fun onTextChanged(e: AccessibilityEvent) {
        val node = e.source ?: return
        if (!node.isEditable) return
        inputNode = node
        val t = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        if (t == currentText && overlay.hasGate()) return
        currentText = t

        // 잠긴 뒤 글을 고치기 시작하면 지퍼·패널은 조용히 치운다.
        if (lockedText != null && t != lockedText) {
            lockedText = null
            overlay.hideZip()
            overlay.hidePanel()
        }

        main.removeCallbacks(prejudge)
        if (t.isBlank()) { overlay.hideGate(); return }
        placeGate()
        if (Prefs.apiKey(this).isNotEmpty()) main.postDelayed(prejudge, 600)
    }

    private fun recoverInput() {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!f.isEditable) return
        inputNode = f
        currentText = if (f.isShowingHintText) "" else (f.text?.toString() ?: "")
    }

    // ───────── 전송 버튼 찾기 ─────────
    /**
     * 입력칸과 같은 줄, 입력칸 오른쪽에 있는 클릭 가능한 것 중 가장 오른쪽 = 전송 버튼.
     * (카톡 전송 버튼은 글자 없는 종이비행기 아이콘이라 글자로는 못 찾는다)
     * '전송/보내기' 설명이 붙은 노드가 있으면 그걸 우선한다.
     */
    private fun findSendRect(): Rect? {
        val inp = inputNode ?: return null
        if (!inp.refresh()) return null
        val b = Rect()
        inp.getBoundsInScreen(b)
        if (b.isEmpty) return null
        val sw = screenW
        val bandTop = b.top - dp(20)
        val bandBottom = b.bottom + dp(20)
        var best: Rect? = null
        var bestLabeled = false

        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30) return
            if (n.isVisibleToUser && !n.isEditable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val label = "${n.contentDescription ?: ""} ${n.text ?: ""}"
                val labeled = sendLabels.any { label.contains(it) }
                val inBand = r.centerY() in bandTop..bandBottom
                val sizeOk = r.width() in 1..(sw / 3) && r.height() in 1..dp(90)
                if (inBand && sizeOk && (labeled || (n.isClickable && r.left >= b.right - dp(12)))) {
                    val cur = best
                    if (cur == null || (labeled && !bestLabeled) || (labeled == bestLabeled && r.right > cur.right)) {
                        best = r
                        bestLabeled = labeled
                    }
                }
            }
            for (k in 0 until n.childCount) visit(n.getChild(k), depth + 1)
        }
        visit(rootInActiveWindow, 0)

        best?.let { return Rect(it) }
        // 못 찾으면: 입력줄 오른쪽 끝 정사각형(카톡 전송 버튼 자리)
        val side = maxOf(b.height() + dp(12), dp(48))
        return Rect(sw - side, b.centerY() - side / 2, sw, b.centerY() + side / 2)
    }

    private fun placeGate() {
        val r = findSendRect()
        if (r == null) { overlay.hideGate(); return }
        r.inset(-dp(4), -dp(4))
        overlay.placeGate(r)
    }

    /** 지퍼가 덮을 채팅바 전체 영역 */
    private fun findRow(): Rect {
        val b = Rect()
        inputNode?.getBoundsInScreen(b)
        val s = findSendRect()
        val top = minOf(b.top, s?.top ?: b.top) - dp(6)
        val bottom = maxOf(b.bottom, s?.bottom ?: b.bottom) + dp(6)
        return Rect(0, top.coerceAtLeast(0), screenW, bottom)
    }

    // ───────── 전송 버튼을 누른 순간 ─────────
    private fun onSendTapped(x: Float, y: Float) {
        if (deciding) return
        val text = currentText
        if (text.isBlank() || text == allowedText) { overlay.passThrough(x, y); return }

        deciding = true
        val input = gather(text)
        var done = false
        val finish: (Verdict) -> Unit = { v ->
            if (!done) {
                done = true
                deciding = false
                if (currentText == text) {
                    verdict = v
                    lastInput = input
                    if (v.lock) lockNow() else overlay.passThrough(x, y)
                }
            }
        }
        val timeout = Runnable { finish(Judge.judgeContext(input)) }
        main.postDelayed(timeout, 4500)
        requestVerdict(input) { v ->
            main.removeCallbacks(timeout)
            finish(v)
        }
    }

    private fun lockNow() {
        lockedText = currentText
        main.removeCallbacks(prejudge)
        overlay.lockZip(findRow()) { openLockPanel() }
    }

    // ───────── 판단(캐시 + AI 또는 규칙) ─────────
    private fun gather(text: String): JudgeInput {
        val b = Rect()
        inputNode?.getBoundsInScreen(b)
        val inputTop = if (b.isEmpty) Int.MAX_VALUE else b.top
        val lines = ChatReader.read(rootInActiveWindow, inputTop, screenW, dp(96), lastRoomTitle)
        val saved = lastRoomTitle?.let { Prefs.roomMode(this, it) }
        val formal = saved ?: (Prefs.formal(this) || honorificConversation(lines))
        return JudgeInput(text, lines, formal, lastRoomTitle ?: "", Prefs.sens(this), relationFixed = saved != null)
    }

    private fun requestVerdict(input: JudgeInput, cb: (Verdict) -> Unit) {
        val key = input.key()
        cache[key]?.let { cb(it); return }
        inflight[key]?.let { it.add(cb); return }
        val local = Judge.judgeContext(input)
        val apiKey = Prefs.apiKey(this)
        if (apiKey.isEmpty()) {
            cache[key] = local
            cb(local)
            return
        }
        inflight[key] = mutableListOf(cb)
        Llm.judge(apiKey, input) { v ->
            val res = v ?: local
            if (cache.size > 40) cache.clear()
            cache[key] = res
            inflight.remove(key)?.forEach { it(res) }
        }
    }

    // ───────── 잠긴 뒤 ─────────
    private fun openLockPanel() {
        val v = verdict ?: return
        val formal = lastInput?.formal ?: Prefs.formal(this)
        val rel = v.relation.ifBlank { if (formal) "윗사람" else "친구" }
        val dots = "●".repeat(v.level) + "○".repeat(3 - v.level)
        overlay.showLockPanel(
            reason = v.reason.ifBlank { "보내기 전에 한 번만 더 생각해 볼까요?" },
            mood = "상대: $rel · 대화 분위기: ${v.atmosphere} · 이 메시지: ${v.intent} · 감정 세기 $dots",
            convertLabel = if (formal || rel == "윗사람") "존댓말로" else "차분하게",
            onConvert = { convertFlow() },
            onRewrite = { unlock(sendAfter = false, allow = false) },
            onSendAnyway = { unlock(sendAfter = true, allow = true) }
        )
    }

    private fun unlock(sendAfter: Boolean, allow: Boolean) {
        if (allow) allowedText = currentText
        lockedText = null
        overlay.hidePanel()
        overlay.unlockZip {
            if (sendAfter) {
                val g = findSendRect()
                if (g != null) overlay.passThrough(g.exactCenterX(), g.exactCenterY())
            } else if (currentText.isNotBlank()) {
                placeGate()
            }
        }
    }

    private fun convertFlow() {
        val input = lastInput ?: gather(currentText)
        val original = input.text
        val formal = input.formal || verdict?.relation == "윗사람"
        val local = if (formal) Judge.localConvert(original) else Judge.localCalm(original)
        val apiKey = Prefs.apiKey(this)
        overlay.showLoading()
        if (apiKey.isEmpty()) {
            overlay.showCompare(original, local, "API 키가 없어 기기 안의 간단 변환을 썼어요. 어색할 수 있어요.",
                { applyText(local) }, { openLockPanel() })
            return
        }
        Llm.convert(apiKey, input.copy(formal = formal)) { res ->
            if (res.isNullOrBlank()) {
                overlay.showCompare(original, local, "변환 서비스에 연결하지 못해 간단 변환을 썼어요.",
                    { applyText(local) }, { openLockPanel() })
            } else {
                overlay.showCompare(original, res, "", { applyText(res) }, { openLockPanel() })
            }
        }
    }

    /** 입력칸의 글을 바꾼 문장으로 교체. 전송은 사용자가 직접 누른다. */
    private fun applyText(newText: String) {
        allowedText = newText
        lockedText = null
        overlay.hidePanel()
        overlay.unlockZip {}
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        inputNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ───────── 정리 ─────────
    private fun resetRoom() {
        main.removeCallbacks(prejudge)
        overlay.hideGate()
        overlay.hideZip()
        overlay.hidePanel()
        inputNode = null
        currentText = ""
        allowedText = null
        lockedText = null
        deciding = false
        cache.clear()
    }

    private fun leaveApp() {
        main.removeCallbacks(checkRoomTask)
        main.removeCallbacks(forcedCheck); main.removeCallbacks(forcedCheck2); main.removeCallbacks(forcedCheck3)
        pendingTitle = null
        resetRoom()
        overlay.hideAll()
        lastRoomTitle = null
    }

    override fun onInterrupt() { if (::overlay.isInitialized) overlay.hideAll() }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.hideAll()
        super.onDestroy()
    }
}
