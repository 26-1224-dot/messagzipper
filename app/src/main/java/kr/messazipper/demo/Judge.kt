package kr.messazipper.demo

/** 화면에서 읽은 대화 한 줄. mine = 내가 보낸 말풍선(오른쪽). */
data class ChatLine(val mine: Boolean, val text: String)

/**
 * 판정에 필요한 재료: 보낼 문장 + 최근 대화 + 상대 유형.
 * relationFixed = 사용자가 이 방의 모드를 직접 정해 둠(AI가 관계를 바꾸지 않음)
 */
data class JudgeInput(
    val text: String,
    val lines: List<ChatLine>,
    val formal: Boolean,
    val room: String,
    val sens: Int,
    val relationFixed: Boolean = false
) {
    fun key() = "$formal|$sens|${lines.takeLast(3).joinToString("/") { it.text }}|$text"
}

/**
 * 판정 결과. 두 단계를 따로 판단한 뒤 표(decide)로 합친다.
 *  atmosphere : 보내기 직전까지의 대화 분위기  — 편한 장난 / 평범 / 긴장 / 다툼
 *  intent     : 보내려는 메시지의 성격          — 장난 / 일상 / 거친말 / 불만 / 공격
 *  level      : 메시지에 실린 화의 세기 0~3
 */
data class Verdict(
    val lock: Boolean,
    val reason: String,
    val relation: String,
    val atmosphere: String,
    val intent: String,
    val level: Int
)

object Judge {
    private val SEVERE = Regex("(죽어|죽을래|꺼져|닥쳐|시발|씨발|ㅅㅂ|병신|ㅂㅅ|새끼|미친놈|미친년|개같)")
    private val MILD = Regex("(멍청\\S*|바보|등신|놈아|한심\\S*|짜증나|돌았|찌질\\S*)")
    private val CASUAL = Regex("(ㅋ+|ㅎㅎ+|째\\S*|ㅇㅇ|ㄴㄴ|ㅡㅡ|~|존나|개[가-힣]|할래|싫어|몰라|해줘|갈게|할게)")
    private val FORMAL_END = Regex("(니다|세요|까요|해요|예요|이에요|에요|드려요|요)[.!?~]*$")
    private val SHORT_OK = Regex("^(네|넵|예|감사합니다|알겠습니다|죄송합니다)[.!~]*$")

    private val THREAT = Regex("(죽여|죽어|죽을래|뒤질래|뒤져|꺼져|닥쳐|차단|연락\\s?하지\\s?마|다시는|다신|절교|헤어지자|헤어져|끝이야|끝내자|두고\\s?봐|가만\\s?안\\s?둬|후회할|신고할)")
    private val BLAME = Regex("(너\\s?때문|니\\s?때문|니가\\s?뭔데|네가\\s?뭔데|그따위|어이없|짜증|열받|빡치|빡쳐|개빡|화나|화났|미쳤냐|미쳤어|정신\\s?차려|실망|최악|역겨|진짜\\s?싫|싫어\\s?진짜|서운)")
    private val LAUGH = Regex("(ㅋ{2,}|ㅎ{2,}|😂|🤣|😆|장난|농담)")
    private val PUNCT = Regex("(!{2,}|\\?{2,}|\\?!|!\\?|ㅡㅡ|;;)")
    private val CONFLICT = Regex("(미안|사과|화났|화나|왜\\s?그래|짜증|열받|빡치|싸우|서운|실망|어이없|그만해|됐어|몰라|너\\s?때문|헤어|차단)")

    data class Result(val lock: Boolean, val reason: String)

    fun isHonorific(s: String) = FORMAL_END.containsMatchIn(s.trim())

    /** 윗사람에게 보내도 될 만큼 예의 바른지 보는 규칙. @param sens 0 낮음 / 1 보통 / 2 높음 */
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

    /**
     * 잠글지 말지 정하는 표. AI 판단이든 기기 규칙 판단이든 마지막엔 이 표 하나로 결정한다.
     *
     *  [윗사람]  예의에 어긋나거나(politeOk=false) 거친말·불만·공격이면 잠금
     *  [친구]    분위기        | 잠그는 경우
     *            편한 장난      | 공격이면서 화가 폭발(3)했을 때만
     *            평범          | 공격(화 2 이상) / 불만(화 3)
     *            긴장          | 공격 / 불만(화 2 이상) / 거친말(화 3)
     *            다툼          | 공격 / 불만 / 거친말(화가 조금이라도 실리면)
     *  민감도 낮음이면 기준 화 세기 +1, 높음이면 -1
     */
    fun decide(formal: Boolean, atmosphere: String, intent: String, level: Int, politeOk: Boolean, sens: Int): Boolean {
        if (formal) return !politeOk || intent == "공격" || intent == "불만" || intent == "거친말"
        val s = when (sens) { 0 -> 1; 2 -> -1; else -> 0 }
        return when (atmosphere) {
            "편한 장난" -> intent == "공격" && level >= 3 + s
            "다툼" -> intent == "공격" || ((intent == "불만" || intent == "거친말") && level >= 1 + s)
            "긴장" -> intent == "공격" || (intent == "불만" && level >= 2 + s) || (intent == "거친말" && level >= 3 + s)
            else -> (intent == "공격" && level >= 2 + s) || (intent == "불만" && level >= 3 + s)
        }
    }

    /** 1단계: 보내기 직전까지의 대화 분위기 */
    fun atmosphereOf(lines: List<ChatLine>): String {
        val recent = lines.takeLast(8)
        val laugh = recent.count { LAUGH.containsMatchIn(it.text) }
        val conflict = recent.count { CONFLICT.containsMatchIn(it.text) && !LAUGH.containsMatchIn(it.text) }
        val theirLast = recent.lastOrNull { !it.mine }?.text ?: ""
        val theirAngry = theirLast.isNotEmpty() && !LAUGH.containsMatchIn(theirLast) &&
            (SEVERE.containsMatchIn(theirLast) || BLAME.containsMatchIn(theirLast) || THREAT.containsMatchIn(theirLast))
        return when {
            conflict >= 2 || (theirAngry && conflict >= 1) -> "다툼"
            conflict == 1 || theirAngry -> "긴장"
            laugh >= 2 -> "편한 장난"
            else -> "평범"
        }
    }

    /** 2단계: 보내려는 메시지 자체의 성격과 화의 세기 */
    fun messageOf(text: String): Pair<String, Int> {
        val t = text.trim()
        var heat = THREAT.findAll(t).count() * 2 + SEVERE.findAll(t).count() + BLAME.findAll(t).count()
        if (PUNCT.containsMatchIn(t)) heat += 1
        if (t.length > 60 && (SEVERE.containsMatchIn(t) || BLAME.containsMatchIn(t))) heat += 1
        val laughing = LAUGH.containsMatchIn(t)
        if (laughing) heat -= 2
        val level = when { heat <= 0 -> 0; heat == 1 -> 1; heat <= 3 -> 2; else -> 3 }
        val intent = when {
            !laughing && (THREAT.containsMatchIn(t) || heat >= 4) -> "공격"
            !laughing && BLAME.containsMatchIn(t) -> "불만"
            laughing -> "장난"
            SEVERE.containsMatchIn(t) || MILD.containsMatchIn(t) -> "거친말"
            else -> "일상"
        }
        return intent to level
    }

    /**
     * 즉시 결정: 확실한 경우만 답하고, 애매하면 null(→ AI 판단을 기다림).
     * 대부분의 평범한 메시지는 여기서 바로 통과해서 전송이 지연되지 않는다.
     */
    fun quickVerdict(i: JudgeInput): Verdict? {
        val t = i.text.trim()
        val rel = if (i.formal) "윗사람" else "친구"
        val atmosphere = atmosphereOf(i.lines)
        if (t.isEmpty() || SHORT_OK.matches(t)) return Verdict(false, "", rel, atmosphere, "일상", 0)
        val (intent, level) = messageOf(t)
        val politeOk = !judge(t, true, i.sens).lock
        if (i.formal) {
            if (politeOk && intent == "일상" && level == 0) return Verdict(false, "", rel, atmosphere, intent, 0)
            if (SEVERE.containsMatchIn(t) || intent == "공격")
                return Verdict(true, reasonFor(true, atmosphere, intent, politeOk), rel, atmosphere, intent, level)
            return null
        }
        if ((intent == "일상" || intent == "장난") && level == 0 && atmosphere != "다툼")
            return Verdict(false, "", rel, atmosphere, intent, 0)
        if (intent == "공격" && level >= 3)
            return Verdict(true, reasonFor(false, atmosphere, intent, politeOk), rel, atmosphere, intent, level)
        return null
    }

    /** 기기 안 규칙 판단(API 키가 없거나 인터넷이 안 될 때) */
    fun judgeContext(i: JudgeInput): Verdict {
        val t = i.text.trim()
        val rel = if (i.formal) "윗사람" else "친구"
        val atmosphere = atmosphereOf(i.lines)
        if (t.isEmpty() || SHORT_OK.matches(t)) return Verdict(false, "", rel, atmosphere, "일상", 0)
        val (intent, level) = messageOf(t)
        val politeOk = !judge(t, true, i.sens).lock
        val lock = decide(i.formal, atmosphere, intent, level, politeOk, i.sens)
        return Verdict(lock, if (lock) reasonFor(i.formal, atmosphere, intent, politeOk) else "", rel, atmosphere, intent, level)
    }

    fun reasonFor(formal: Boolean, atmosphere: String, intent: String, politeOk: Boolean): String = when {
        formal && !politeOk -> "윗사람에게 보내기엔 말투가 맞지 않아요"
        formal -> "윗사람에게 상처가 될 수 있는 말이에요"
        intent == "공격" && atmosphere == "다툼" -> "다투는 중에 홧김에 하는 말 같아요"
        intent == "공격" -> "상대에게 상처를 줄 수 있는 말이에요"
        atmosphere == "다툼" || atmosphere == "긴장" -> "분위기가 날카로운데 감정이 실린 말이에요"
        else -> "감정이 많이 실린 메시지 같아요"
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
