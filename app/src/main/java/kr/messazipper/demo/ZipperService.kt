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

    // ───────── 방(상대) 인식 ─────────
    // 원칙: 화면이 바뀌는 순간 이전 방의 상태는 전부 버린다.
    //       모드는 '전역 설정'이 아니라 '지금 방'에 대해서만 계산하고,
    //       전송 버튼을 누른 순간 방 이름과 대화를 화면에서 새로 읽어 다시 확인한다.
    private val NUM_OR_TIME = Regex("^\\d+$|^(오전|오후)\\s?\\d{1,2}:\\d{2}$|^\\d{1,2}:\\d{2}$")
    private val NON_ROOM = setOf(
        "채팅", "친구", "오픈채팅", "더보기", "쇼핑", "뷰", "지갑", "Chats", "Friends",
        "메시지", "Messages", "요청", "검색"
    )
    private var pendingTitle: String? = null
    private var lastTitleCheck = 0L
    private var roomEnteredAt = 0L
    private var curMode: Boolean? = null   // 지금 방이 윗사람이면 true, 친구면 false, 아직 모르면 null
    private val checkRoomTask = Runnable { checkRoom(force = false) }
    private val forcedChecks = listOf(0L, 100L, 250L, 500L, 900L, 1600L).map { d -> d to Runnable { checkRoom(force = true) } }

    private val statusBarH: Int by lazy {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private data class TextNode(val text: String, val id: String, val rect: Rect)

    private fun textsIn(root: AccessibilityNodeInfo, top: Int, bottom: Int): List<TextNode> {
        val out = ArrayList<TextNode>()
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25) return
            // 카톡은 방에 들어가도 뒤에 채팅 목록 화면을 '안 보이게' 남겨 둔다 → 안 보이는 건 통째로 무시
            if (depth > 0 && !n.isVisibleToUser) return
            val b = Rect()
            n.getBoundsInScreen(b)
            if (depth > 0 && b.height() > 0 && b.top > bottom) return   // 제목줄보다 아래에 있는 덩어리는 통째로 건너뜀
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && !n.isEditable && t.length <= 30 && !NUM_OR_TIME.matches(t)) {
                if (b.height() > 0 && b.top >= top && b.bottom <= bottom) out.add(TextNode(t, n.viewIdResourceName ?: "", b))
            }
            for (k in 0 until n.childCount) visit(n.getChild(k), depth + 1)
        }
        visit(root, 0)
        return out
    }

    /** 상태바 바로 아래 제목줄(높이 약 64dp)에 있는 글자 = 방 이름. 카톡/인스타 화면일 때만 읽는다. */
    /**
     * 방 이름 읽기: 왼쪽 위 '← 뒤로' 버튼을 먼저 찾고, 그 버튼과 같은 줄 오른쪽에 있는 글자를 방 이름으로 본다.
     * 채팅 목록 화면에는 뒤로 버튼이 없으므로 null(= 채팅방 아님).
     * 화면에 실제로 보이는 것만 본다(뒤에 숨은 목록 화면은 무시).
     */
    private fun readRoomTitle(): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() !in targets) return null
        val sw = screenW
        val sh = resources.displayMetrics.heightPixels
        var back: Rect? = null
        val texts = ArrayList<TextNode>()
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30) return
            if (depth > 0 && !n.isVisibleToUser) return
            val b = Rect()
            n.getBoundsInScreen(b)
            if (depth > 0 && b.height() > 0 && b.top > sh * 0.2f) return   // 화면 위쪽만 본다
            if (b.height() > 0 && b.top < sh * 0.15f) {
                val desc = n.contentDescription?.toString() ?: ""
                val looksBack = desc.contains("뒤로") || desc.contains("Back", true) || desc.contains("Navigate up", true)
                if ((n.isClickable || looksBack) && b.left < sw * 0.12f && b.width() in 1..(sw / 5) && b.height() < dp(80) &&
                    n.text.isNullOrEmpty()) {
                    val cur = back
                    if (cur == null || looksBack || b.top < cur.top) back = Rect(b)
                }
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && !n.isEditable && t.length <= 40 && !NUM_OR_TIME.matches(t)) {
                    texts.add(TextNode(t, n.viewIdResourceName ?: "", Rect(b)))
                }
            }
            for (k in 0 until n.childCount) visit(n.getChild(k), depth + 1)
        }
        visit(root, 0)
        val bk = back ?: return null
        return texts
            .filter { it.rect.centerY() in (bk.top - dp(6))..(bk.bottom + dp(6)) && it.rect.left >= bk.right - dp(4) && it.rect.left < sw * 0.75f }
            .minWithOrNull(compareBy({ it.rect.left }, { it.rect.top }))
            ?.text
    }

    /** 화면 바뀜 신호: 아무것도 지우지 않고 방 이름만 다시 읽어 본다. 이름이 실제로 달라졌을 때만 새 방으로 처리. */
    private fun onScreenChanged() {
        main.removeCallbacks(checkRoomTask)
        forcedChecks.forEach { (d, r) -> main.removeCallbacks(r); main.postDelayed(r, d) }
    }

    private var nullReads = 0

    private fun checkRoom(@Suppress("UNUSED_PARAMETER") force: Boolean) {
        val now = SystemClock.uptimeMillis()
        lastTitleCheck = now
        val title = readRoomTitle()
        if (title == null) {
            // 뒤로 버튼이 두 번 연속 안 보이면 채팅방 밖(목록 등) → 모드 버튼 숨김
            if (++nullReads >= 2 && lastRoomTitle != null) {
                resetRoom()
                lastRoomTitle = null
                curMode = null
                overlay.hideFab()
            }
            return
        }
        nullReads = 0
        if (title == lastRoomTitle) {
            // 들어온 직후엔 대화가 늦게 그려지므로, 저장된 모드가 없으면 대화 말투로 다시 판단
            if (Prefs.roomMode(this, title) == null && now - roomEnteredAt < 2500) {
                curMode = modeFor(title, currentLines())
                overlay.showFab(curMode == true, title)
            }
            return
        }
        enterRoom(title, clear = lastRoomTitle != null)   // 뒤로 버튼 기준이라 믿을 수 있으므로 바로 확정
    }

    private fun enterRoom(title: String, clear: Boolean) {
        pendingTitle = null
        if (clear) resetRoom() else cache.clear()
        lastRoomTitle = title
        roomEnteredAt = SystemClock.uptimeMillis()
        if (title in NON_ROOM) {          // 채팅 목록 같은 화면 → 방이 아님
            curMode = null
            overlay.hideFab()
            return
        }
        curMode = modeFor(title, currentLines())
        overlay.showFab(curMode == true, title)
    }

    /** 이 방의 모드: 직접 정해 둔 게 있으면 그것, 없으면 이름 단서 또는 내가 존댓말을 쓰는지 */
    private fun modeFor(title: String, lines: List<ChatLine>): Boolean =
        Prefs.roomMode(this, title) ?: (guessFormal(title) || honorificConversation(lines))

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

    private fun chatTop() = statusBarH + dp(64)

    private fun currentLines(): List<ChatLine> {
        val b = Rect()
        inputNode?.getBoundsInScreen(b)
        val inputTop = if (b.isEmpty) resources.displayMetrics.heightPixels else b.top
        return ChatReader.read(rootInActiveWindow, inputTop, screenW, chatTop(), lastRoomTitle)
    }

    /** 모드 버튼을 길게 누르면: 앱이 화면에서 무엇을 읽었는지 보여 준다(인식이 틀릴 때 원인 확인용). */
    private fun showDiagnostics() {
        val root = rootInActiveWindow
        val sb = StringBuilder()
        sb.append("읽은 방 이름: ").append(readRoomTitle() ?: "(못 읽음)").append('\n')
        sb.append("현재 모드: ").append(when (curMode) { true -> "윗사람"; false -> "친구"; else -> "모름" })
        sb.append(if (lastRoomTitle != null && Prefs.roomMode(this, lastRoomTitle!!) != null) " (직접 지정)" else " (자동)").append('\n')
        sb.append("상태바 높이: ").append(statusBarH).append("px\n\n화면 위쪽 글자들:\n")
        if (root != null) {
            val sh = resources.displayMetrics.heightPixels
            textsIn(root, 0, (sh * 0.2f).toInt()).sortedBy { it.rect.top }.take(8).forEach {
                sb.append("· ").append(it.text).append("  [y=").append(it.rect.top).append(", ")
                    .append(it.id.substringAfter(":id/", "-")).append("]\n")
            }
        }
        val lines = currentLines()
        sb.append("\n읽은 대화 ").append(lines.size).append("줄 (마지막 4줄):\n")
        lines.takeLast(4).forEach { sb.append(if (it.mine) "[나] " else "[상대] ").append(it.text.take(30)).append('\n') }
        overlay.showInfo("메세지퍼 인식 상태", sb.toString())
    }

    override fun onServiceConnected() {
        overlay = Overlay(this)
        overlay.onToggleMode = {
            val t = lastRoomTitle
            if (t != null && t !in NON_ROOM) {
                val f = curMode != true
                Prefs.setRoomMode(this, t, f)     // 이 방은 앞으로 이 모드로 기억
                curMode = f
                cache.clear()
                overlay.showFab(f, t)
            }
        }
        overlay.onFabLongPress = { showDiagnostics() }
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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onScreenChanged()
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(e)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastTitleCheck > 250) checkRoom(force = false)
                if (lockedText == null && now - lastPlace > 300) {
                    lastPlace = now
                    if (inputNode == null) recoverInput()
                    if (currentText.isNotBlank()) placeGate()
                }
            }
        }
        val t = lastRoomTitle
        val m = curMode
        if (t != null && m != null) overlay.showFab(m, t)   // 채팅방이 아니면(t == null) 모드 버튼을 띄우지 않음
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
        if (Prefs.apiKey(this).isNotEmpty()) main.postDelayed(prejudge, 350)
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
        val node = inputNode?.takeIf { it.refresh() } ?: run { inputNode = null; recoverInput(); inputNode }
        if (node != null) currentText = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val text = currentText
        if (text.isBlank() || text == allowedText) { overlay.passThrough(x, y); return }

        deciding = true
        // 누른 바로 그 순간 방 이름을 화면에서 새로 읽어 확인한다(방 전환을 놓쳤어도 여기서 바로잡힘).
        val fresh = readRoomTitle()
        if (fresh != null && fresh != lastRoomTitle) enterRoom(fresh, clear = false)
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
        // 누가 봐도 괜찮은 일상 메시지 / 누가 봐도 위험한 메시지는 AI를 기다리지 않고 바로 결정
        Judge.quickVerdict(input)?.let { finish(it); return }
        val timeout = Runnable { finish(Judge.judgeContext(input)) }
        main.postDelayed(timeout, 3000)
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
        val lines = ChatReader.read(rootInActiveWindow, inputTop, screenW, chatTop(), lastRoomTitle)
        val title = lastRoomTitle
        val saved = title?.let { Prefs.roomMode(this, it) }
        // 지금 방에 대해서만 계산(이전 방의 모드를 절대 끌고 오지 않음)
        val formal = if (title != null) modeFor(title, lines) else honorificConversation(lines)
        curMode = formal
        return JudgeInput(text, lines, formal, title ?: "", Prefs.sens(this), relationFixed = saved != null)
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
        val formal = lastInput?.formal ?: (curMode == true)
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
        forcedChecks.forEach { (_, r) -> main.removeCallbacks(r) }
        pendingTitle = null
        resetRoom()
        overlay.hideAll()
        lastRoomTitle = null
        curMode = null
    }

    override fun onInterrupt() { if (::overlay.isInitialized) overlay.hideAll() }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.hideAll()
        super.onDestroy()
    }
}
