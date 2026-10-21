package kr.messazipper.demo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 지금 화면에 보이는 채팅 말풍선 글자를 읽어 온다.
 * 오른쪽 끝에 붙은 말풍선은 내가 보낸 것, 나머지는 상대가 보낸 것으로 본다.
 */
object ChatReader {
    private val NOISE = Regex(
        "^(오전|오후)\\s?\\d{1,2}:\\d{2}$|^\\d{1,2}:\\d{2}$|^\\d{1,3}$|^\\d{4}년.*|.*요일$|" +
        "^읽음$|^Seen.*|^전송됨$|^사진$|^동영상$|^이모티콘$|^AI 영상 만들기$"
    )

    fun read(root: AccessibilityNodeInfo?, inputTop: Int, screenW: Int, topLimit: Int, room: String?): List<ChatLine> {
        if (root == null) return emptyList()
        val found = ArrayList<Pair<Int, ChatLine>>()
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && !n.isEditable && t.length <= 300 && !NOISE.matches(t) && t != room) {
                val b = Rect()
                n.getBoundsInScreen(b)
                if (b.width() > 0 && b.bottom <= inputTop && b.top >= topLimit) {
                    val rightGap = screenW - b.right
                    val mine = rightGap < screenW * 0.09f && b.left > screenW * 0.12f
                    found.add(b.top to ChatLine(mine, t))
                }
            }
            for (k in 0 until n.childCount) visit(n.getChild(k), depth + 1)
        }
        visit(root, 0)
        val ordered = found.sortedBy { it.first }.map { it.second }
        val out = ArrayList<ChatLine>()
        for (l in ordered) if (out.lastOrNull() != l) out.add(l)
        return out.takeLast(14)
    }
}
