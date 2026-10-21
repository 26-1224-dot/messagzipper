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
        "너는 메신저 대화 분석기다. 최근 대화와 사용자가 지금 보내려는 메시지를 보고 아래 항목을 '각각 따로' 판단한다.\n" +
        "1) relation: 상대와의 관계. 방 이름과 대화 말투(사용자가 존댓말을 쓰는지)를 근거로 \"친구\" 또는 \"윗사람\".\n" +
        "2) atmosphere: 보내려는 메시지는 빼고, 그 직전까지의 대화 분위기.\n" +
        "   - \"편한 장난\": 서로 웃으며(ㅋㅋ 등) 거친 말이나 욕을 장난으로 주고받는 중\n" +
        "   - \"평범\": 일상적인 대화\n" +
        "   - \"긴장\": 한쪽이 서운함이나 불만을 드러내기 시작함\n" +
        "   - \"다툼\": 서로 탓하거나 화를 내며 싸우는 중\n" +
        "3) intent: 보내려는 메시지의 성격.\n" +
        "   - \"장난\": 웃자고 하는 말 / \"일상\": 평범한 말 / \"거친말\": 욕이 섞였지만 악의는 없음\n" +
        "   - \"불만\": 서운함·탓하기·짜증 / \"공격\": 상대를 깎아내리는 모욕, 위협, 절교·차단 선언\n" +
        "4) emotion_level: 메시지에 실린 화·분노의 세기. 0(없음) 1(약함) 2(뚜렷함) 3(폭발).\n" +
        "5) polite_ok: 윗사람에게 보내도 될 만큼 예의 바른 말투인지 true/false.\n" +
        "6) reason: 다시 생각해 보라고 사용자에게 보여줄 부드러운 존댓말 한 문장(30자 이내). 분위기와 메시지 감정을 근거로.\n" +
        "중요: 욕이 있다는 사실만으로 공격이나 높은 감정으로 판단하지 마라. 같은 욕이라도 웃으며 주고받는 장난인지, " +
        "다툼 중에 홧김에 하는 말인지를 대화 흐름(웃음 표현, 서로 탓하는지, 상대의 마지막 반응)으로 구분해라.\n" +
        "JSON만 출력: {\"relation\":\"\",\"atmosphere\":\"\",\"intent\":\"\",\"emotion_level\":0,\"polite_ok\":true,\"reason\":\"\"}"

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
                    val aiRel = o.optString("relation", "")
                    // 사용자가 방 모드를 직접 정했으면 그걸 따르고, 아니면 AI가 윗사람이라고 본 경우도 윗사람으로.
                    val formal = i.formal || (!i.relationFixed && aiRel == "윗사람")
                    val atm = o.optString("atmosphere", "평범").ifBlank { "평범" }
                    val intent = o.optString("intent", "일상").ifBlank { "일상" }
                    val level = o.optInt("emotion_level", 0).coerceIn(0, 3)
                    val politeOk = o.optBoolean("polite_ok", true)
                    val lock = Judge.decide(formal, atm, intent, level, politeOk, i.sens)
                    val reason = o.optString("reason", "").ifBlank { Judge.reasonFor(formal, atm, intent, politeOk) }
                    Verdict(lock, if (lock) reason else "", if (formal) "윗사람" else "친구", atm, intent, level)
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
