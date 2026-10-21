package kr.messazipper.demo

/** 화면에서 읽은 대화 한 줄. mine = 내가 보낸 말풍선(오른쪽). */
data class ChatLine(val mine: Boolean, val text: String)

/** 판정에 필요한 재료: 보낼 문장 + 최근 대화 + 상대 유형 */
data class JudgeInput(
    val text: String,
    val lines: List<ChatLine>,
    val formal: Boolean,
    val room: String,
    val sens: Int
) {
    fun key() = "$formal|$sens|${lines.lastOrNull()?.text}|$text"
}

/** 판정 결과. relation: 친구/윗사람, mood: 장난/평온/갈등/격앙 */
data class Verdict(val lock: Boolean, val reason: String, val relation: String, val mood: String)

/**
 * 기기 안에서만 도는 규칙 판정. API 키가 없거나 인터넷이 안 될 때 쓰인다.
 * 친구 사이에서는 '욕이 있냐'가 아니라 '감정이 얼마나 격한가'를 점수로 본다.
 */
object Judge {
    private val SEVERE = Regex("(죽어|죽을래|꺼져|닥쳐|시발|씨발|ㅅㅂ|병신|ㅂㅅ|새끼|미친놈|미친년|개같)")
    private val MILD = Regex("(멍청\\S*|바보|등신|놈아|한심\\S*|짜증나|돌았|찌질\\S*)")
    private val CASUAL = Regex("(ㅋ+|ㅎㅎ+|째\\S*|ㅇㅇ|ㄴㄴ|ㅡㅡ|~|존나|개[가-힣]|할래|싫어|몰라|해줘|갈게|할게)")
    private val FORMAL_END = Regex("(니다|세요|까요|해요|예요|이에요|에요|드려요|요)[.!?~]*$")
    private val SHORT_OK = Regex("^(네|넵|예|감사합니다|알겠습니다|죄송합니다)[.!~]*$")

    // 감정이 격해졌을 때 나오는 말들
    private val THREAT = Regex("(죽여|죽어|죽을래|뒤질래|뒤져|꺼져|닥쳐|차단|연락\\s?하지\\s?마|다시는|다신|절교|헤어지자|헤어져|끝이야|끝내자|두고\\s?봐|가만\\s?안\\s?둬|후회할|신고할)")
    private val BLAME = Regex("(너\\s?때문|니\\s?때문|니가\\s?뭔데|네가\\s?뭔데|그따위|어이없|짜증|열받|빡치|빡쳐|개빡|화나|화났|미쳤냐|미쳤어|정신\\s?차려|실망|최악|역겨|진짜\\s?싫|싫어\\s?진짜)")
    private val LAUGH = Regex("(ㅋ{2,}|ㅎ{2,}|😂|🤣|😆|장난|농담)")
    private val PUNCT = Regex("(!{2,}|\\?{2,}|\\?!|!\\?|ㅡㅡ|;;)")
    private val CONFLICT = Regex("(미안|사과|화났|화나|왜\\s?그래|짜증|열받|빡치|싸우|서운|실망|어이없|그만해|됐어|몰라|너\\s?때문|헤어|차단)")

    data class Result(val lock: Boolean, val reason: String)

    fun isHonorific(s: String) = FORMAL_END.containsMatchIn(s.trim())

    /** 윗사람용 규칙. @param sens 0 낮음 / 1 보통 / 2 높음 */
    fun judge(text: String, formal: Boolean, sens: Int): Result {
        val t = text.trim()
        if (t.isEmpty() || SHORT_OK.matches(t)) return Result(false, "")
        if (SEVERE.containsMatchIn(t)) return Result(true, "상대에게 상처가 될 수 있는 심한 표현이 있어요")
        if (MILD.containsMatchIn(t) && (formal || sens == 2))
            return Result(true, if (formal) "윗사람에게는 거친 표현이에요" else "듣는 사람이 기분 나쁠 수 있는 표현이에요")
        if (formal && sens >= 1) {
            if (CASUAL.containsMatchIn(t)) return Result(true, "윗사람에게 보내기엔 말투가 가벼워요")
            if (t.length >= 4 && !FORMAL_END.containsMatchIn(t)) return Result(true, "윗사람에게 보내기엔 존댓말이 필요해 보여요")
        }
        return Result(false, "")
    }

    /** 대화 맥락까지 보고 판단하는 규칙 버전. */
    fun judgeContext(i: JudgeInput): Verdict {
        val t = i.text.trim()
        val rel = if (i.formal) "윗사람" else "친구"
        if (t.isEmpty() || SHORT_OK.matches(t)) return Verdict(false, "", rel, "평온")

        if (i.formal) {
            val r = judge(t, true, i.sens)
            return Verdict(r.lock, r.reason, rel, if (r.lock) "무례" else "평온")
        }

        // 친구 사이: 욕 자체가 아니라 감정의 세기를 본다.
        var heat = 0
        heat += THREAT.findAll(t).count() * 2
        heat += SEVERE.findAll(t).count()
        heat += BLAME.findAll(t).count()
        if (PUNCT.containsMatchIn(t)) heat += 1
        if (t.length > 60 && (SEVERE.containsMatchIn(t) || BLAME.containsMatchIn(t))) heat += 1
        val laughing = LAUGH.containsMatchIn(t)
        if (laughing) heat -= 2

        val recent = i.lines.takeLast(6)
        val conflict = recent.count { CONFLICT.containsMatchIn(it.text) && !LAUGH.containsMatchIn(it.text) }
        val banter = recent.count { LAUGH.containsMatchIn(it.text) }
        val theirAngry = recent.lastOrNull { !it.mine }?.let {
            (SEVERE.containsMatchIn(it.text) || BLAME.containsMatchIn(it.text) || THREAT.containsMatchIn(it.text)) &&
                !LAUGH.containsMatchIn(it.text)
        } ?: false

        if (heat > 0) {
            heat += minOf(conflict, 2)          // 다투는 중이면 더 위험
            if (theirAngry) heat += 1           // 상대도 화가 나 있으면 더 위험
            if (banter >= 2 && conflict == 0) heat -= 1   // 웃으면서 놀던 중이면 덜 위험
        }

        val threshold = when (i.sens) { 0 -> 4; 2 -> 2; else -> 3 }
        val mood = when {
            heat >= threshold -> "격앙"
            conflict > 0 -> "갈등"
            banter > 0 || laughing -> "장난"
            else -> "평온"
        }
        if (heat >= threshold) {
            val reason = when {
                THREAT.containsMatchIn(t) -> "홧김에 관계를 끊는 말을 하려는 것 같아요"
                conflict > 0 || theirAngry -> "다투는 중에 감정이 격해진 것 같아요"
                else -> "감정이 많이 실린 메시지 같아요"
            }
            return Verdict(true, reason, rel, mood)
        }
        return Verdict(false, "", rel, mood)
    }

    /** 인터넷 없이 쓰는 윗사람용 간단 변환. 단순 치환이라 어색할 수 있다. */
    fun localConvert(text: String): String {
        var s = text.trim()
        s = s.replace(Regex("ㅋ+|ㅎㅎ+|ㅡㅡ|~"), "")
        s = SEVERE.replace(s, "")
        s = MILD.replace(s, "")
        s = s.replace("존나", "매우").replace("할래", "하겠습니다").replace("갈게", "가겠습니다")
            .replace("할게", "하겠습니다").replace("해줘", "해 주시면 감사하겠습니다")
            .replace("몰라", "잘 모르겠습니다").replace("싫어", "어렵습니다")
        s = s.replace(Regex("\\s{2,}"), " ").trim()
        if (s.isNotEmpty() && !FORMAL_END.containsMatchIn(s)) s += "습니다"
        return s
    }

    /** 인터넷 없이 쓰는 친구용 간단 진정. 욕·위협만 덜어낸다. */
    fun localCalm(text: String): String {
        var s = text
        s = SEVERE.replace(s, "")
        s = THREAT.replace(s, "")
        s = s.replace(Regex("!{2,}"), "!").replace(Regex("\\?{2,}"), "?")
        s = s.replace(Regex("ㅡㅡ|;;+"), "")
        s = s.replace(Regex("\\s{2,}"), " ").trim()
        return if (s.length < 3) "지금은 좀 속상해서, 이따 차분하게 얘기하자" else s
    }
}
