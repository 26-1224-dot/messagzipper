package kr.messazipper.demo

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 규칙 기반 판정(기기 안에서만 동작). 데모 웹페이지와 같은 규칙. */
object Judge {
    private val SEVERE = Regex("(죽어|죽을래|꺼져|닥쳐|시발|씨발|ㅅㅂ|병신|새끼|미친놈|개같)")
    private val MILD = Regex("(멍청\\S*|바보|등신|놈아|한심\\S*|짜증나|돌았|찌질\\S*)")
    private val CASUAL = Regex("(ㅋ+|ㅎㅎ+|째\\S*|ㅇㅇ|ㄴㄴ|ㅡㅡ|~|존나|개[가-힣]|할래|싫어|몰라|해줘|갈게|할게)")
    private val FORMAL_END = Regex("(니다|세요|까요|해요|예요|이에요|에요|드려요|요)[.!?]*$")
    private val SHORT_OK = Regex("^(네|넵|예|감사합니다|알겠습니다|죄송합니다)[.!~]*$")

    data class Result(val lock: Boolean, val reason: String)

    /** @param formal 윗사람 모드  @param sens 0 낮음 / 1 보통 / 2 높음 */
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

    /** 인터넷 없이 쓰는 간단 변환(키가 없거나 네트워크 실패 시 대체). 단순 치환이라 어색할 수 있다. */
    fun localConvert(text: String): String {
        var s = text.trim()
        s = s.replace(Regex("ㅋ+|ㅎㅎ+|ㅡㅡ|~"), "")
        s = s.replace("존나", "매우").replace("할래", "하겠습니다").replace("갈게", "가겠습니다")
            .replace("할게", "하겠습니다").replace("해줘", "해 주시면 감사하겠습니다")
            .replace("몰라", "잘 모르겠습니다").replace("싫어", "어렵습니다")
        s = s.trim()
        if (s.isNotEmpty() && !FORMAL_END.containsMatchIn(s)) s += "습니다"
        return s
    }
}

/** 사용자가 '말투 변환하기'를 눌렀을 때만 해당 문장을 OpenAI로 보낸다. 키는 앱에서 입력한 값. */
object Llm {
    private val main = Handler(Looper.getMainLooper())

    fun convert(apiKey: String, text: String, onDone: (String?) -> Unit) {
        Thread {
            var out: String? = null
            try {
                val body = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put("temperature", 0.3)
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content",
                            "너는 한국어 메시지 말투 변환기다. 사용자의 문장을 선생님이나 목사님 같은 윗사람에게 보내도 " +
                            "예의 바른 존댓말로 바꿔라. 욕설·비하는 순화하고, 원래 의미와 의도는 유지하며, " +
                            "없던 사과나 약속을 새로 지어내지 마라. 변환된 문장 한 줄만 출력해라."))
                        put(JSONObject().put("role", "user").put("content", text))
                    })
                }
                val c = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000
                c.readTimeout = 15000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", "Bearer $apiKey")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode == 200) {
                    val resp = c.inputStream.bufferedReader().readText()
                    out = JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                }
                c.disconnect()
            } catch (_: Exception) {
                out = null
            }
            main.post { onDone(out) }
        }.start()
    }
}
