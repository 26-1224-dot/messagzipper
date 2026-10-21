package kr.messazipper.demo

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 연동. 키는 앱 화면에서 입력한 값(기기 안에만 저장).
 * - judge: 최근 대화 + 보낼 문장을 보고 관계·분위기·감정을 읽어 잠글지 판단
 * - convert: 잠긴 문장을 상대에 맞게 바꿔 줌(윗사람→존댓말, 친구→차분한 반말)
 */
object Llm {
    private val main = Handler(Looper.getMainLooper())

    private const val JUDGE_SYS =
        "너는 메신저 앱의 '감정 브레이크'다. 사용자가 전송 버튼을 누른 순간, 보내려는 메시지를 그대로 보내도 될지 판단한다.\n" +
        "판단 기준:\n" +
        "1. 상대가 윗사람(선생님·교수·상사·부모님·목사님 등)이거나 대화에서 사용자가 존댓말을 쓰는 사이라면, 반말·욕설·비꼬기·무례한 표현은 막는다.\n" +
        "2. 친구·또래 사이에서 평소 욕이나 거친 농담을 웃으며 주고받는 편한 분위기라면, 욕이 들어 있어도 막지 않는다.\n" +
        "3. 관계와 상관없이, 화·분노·서운함에 휩싸여 홧김에 보내는 메시지는 막는다. 예: 상대를 깎아내리는 비난, 위협, 절교·차단 선언, 다툼 중에 감정이 폭발한 말, 나중에 후회할 만한 말.\n" +
        "4. 장난인지 진심인지는 최근 대화의 흐름(웃음 표현, 다툼 여부, 상대의 반응)과 메시지의 감정 강도로 판단한다.\n" +
        "5. 확실하지 않으면 막지 않는다. 불필요하게 막아서 대화를 방해하지 않는 것도 중요하다.\n" +
        "반드시 아래 형식의 JSON만 출력한다.\n" +
        "{\"lock\": true 또는 false, \"relation\": \"친구\" 또는 \"윗사람\" 또는 \"모름\", " +
        "\"mood\": \"장난\" 또는 \"평온\" 또는 \"갈등\" 또는 \"격앙\", " +
        "\"reason\": \"lock이 true일 때 사용자에게 보여줄 부드러운 존댓말 한 문장(30자 이내). false면 빈 문자열\"}"

    private const val CONVERT_FORMAL =
        "너는 한국어 메시지 말투 변환기다. 사용자의 문장을 선생님·상사·부모님 같은 윗사람에게 보내도 " +
        "예의 바른 존댓말로 바꿔라. 욕설·비하는 순화하고, 원래 의미와 의도는 유지하며, " +
        "없던 사과나 약속을 새로 지어내지 마라. 바꾼 문장 한 줄만 출력해라."

    private const val CONVERT_CALM =
        "너는 친구에게 보내는 메시지의 감정을 가라앉혀 주는 도우미다. 사용자가 홧김에 쓴 문장을, " +
        "하고 싶은 말(핵심 의도·서운한 마음)은 그대로 전달하되 욕설·비하·위협·절교 선언처럼 상처 주는 표현을 뺀 " +
        "차분한 반말로 바꿔라. 친구 사이 말투(반말)는 유지하고, 없던 사과나 약속을 지어내지 마라. 바꾼 문장 한 줄만 출력해라."

    private fun describe(i: JudgeInput): String {
        val sb = StringBuilder()
        sb.append("채팅방 이름: ").append(i.room.ifBlank { "알 수 없음" }).append('\n')
        sb.append("상대 유형(앱 설정·추정): ").append(if (i.formal) "윗사람" else "친구 또는 또래").append('\n')
        sb.append("최근 대화(위가 과거, 화면에서 읽어서 잡음이 섞일 수 있음):\n")
        if (i.lines.isEmpty()) sb.append("(읽힌 대화 없음)\n")
        for (l in i.lines) sb.append(if (l.mine) "[나] " else "[상대] ").append(l.text).append('\n')
        sb.append("\n보내려는 메시지: \"").append(i.text).append("\"")
        return sb.toString()
    }

    private fun call(apiKey: String, system: String, user: String, json: Boolean, onDone: (String?) -> Unit) {
        Thread {
            var out: String? = null
            try {
                val body = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put("temperature", 0.2)
                    if (json) put("response_format", JSONObject().put("type", "json_object"))
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", system))
                        put(JSONObject().put("role", "user").put("content", user))
                    })
                }
                val c = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 4000
                c.readTimeout = 6000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("Authorization", "Bearer $apiKey")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (c.responseCode == 200) {
                    val resp = c.inputStream.bufferedReader(Charsets.UTF_8).readText()
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

    fun judge(apiKey: String, i: JudgeInput, onDone: (Verdict?) -> Unit) {
        call(apiKey, JUDGE_SYS, describe(i), true) { s ->
            val v = try {
                if (s == null) null else {
                    val o = JSONObject(s)
                    Verdict(
                        o.optBoolean("lock", false),
                        o.optString("reason", ""),
                        o.optString("relation", ""),
                        o.optString("mood", "")
                    )
                }
            } catch (_: Exception) { null }
            onDone(v)
        }
    }

    fun convert(apiKey: String, i: JudgeInput, onDone: (String?) -> Unit) {
        val sys = if (i.formal) CONVERT_FORMAL else CONVERT_CALM
        call(apiKey, sys, describe(i) + "\n\n위 '보내려는 메시지'를 바꿔 줘.", false) { s ->
            onDone(s?.trim()?.trim('"')?.ifBlank { null })
        }
    }
}
