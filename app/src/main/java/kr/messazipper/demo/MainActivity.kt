package kr.messazipper.demo

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val sensButtons = ArrayList<Button>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "메세지퍼 🤐"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "카카오톡·인스타그램 입력창을 보다가, 보내기 전에 지퍼를 채워 한 번 더 생각하게 해요."
            textSize = 14f
            setPadding(0, dp(6), 0, dp(16))
        })

        status = TextView(this).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        root.addView(status)

        root.addView(button("① 접근성 설정 열기 (메세지퍼 켜기)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(Switch(this).apply {
            text = "보호 켜기"
            isChecked = Prefs.enabled(this@MainActivity)
            setPadding(0, dp(16), 0, dp(8))
            setOnCheckedChangeListener { _, v -> Prefs.setEnabled(this@MainActivity, v) }
        })

        root.addView(TextView(this).apply { text = "민감도"; setPadding(0, dp(12), 0, dp(4)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("낮음", "보통", "높음").forEachIndexed { i, s ->
            val btn = Button(this).apply {
                text = s
                setOnClickListener { Prefs.setSens(this@MainActivity, i); refreshSens() }
            }
            sensButtons.add(btn)
            row.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
        refreshSens()

        root.addView(TextView(this).apply {
            text = "OpenAI API 키 (넣으면 AI가 대화 분위기·감정까지 읽고 판단해요)"
            setPadding(0, dp(20), 0, dp(4))
        })
        val key = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-..."
            setText(Prefs.apiKey(this@MainActivity))
        }
        root.addView(key)
        root.addView(button("키 저장") {
            Prefs.setApiKey(this, key.text.toString())
            Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
        })

        // 키가 진짜 되는지 폰에서 바로 확인
        val testOut = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, 0) }
        root.addView(button("AI 판단 테스트 (싸우는 상황 예시)") {
            Prefs.setApiKey(this, key.text.toString())
            val k = Prefs.apiKey(this)
            val sample = JudgeInput(
                text = "시발 너 때문에 다 망했잖아 다신 연락하지마",
                lines = listOf(
                    ChatLine(false, "아까 그거 왜 말 안 했어?"),
                    ChatLine(true, "몰랐다니까"),
                    ChatLine(false, "너 진짜 어이없다")
                ),
                formal = false, room = "친구", sens = 1
            )
            if (k.isEmpty()) {
                val v = Judge.judgeContext(sample)
                testOut.text = "키 없음 → 기기 규칙 판단: ${if (v.lock) "🔒 잠금" else "통과"} (${v.mood}) ${v.reason}"
            } else {
                testOut.text = "AI에게 물어보는 중…"
                Llm.judge(k, sample) { v ->
                    testOut.text = if (v == null) "⚠️ AI 연결 실패 (키나 인터넷을 확인해 주세요)"
                    else "✅ AI 판단: ${if (v.lock) "🔒 잠금" else "통과"} · 상대 ${v.relation} · 분위기 ${v.mood}\n${v.reason}"
                }
            }
        })
        root.addView(testOut)

        root.addView(TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
            text = "• 타이핑하는 동안에는 아무것도 뜨지 않아요. 전송(종이비행기) 버튼을 누르는 순간에만 판단해요.\n" +
                "• 괜찮은 메시지는 그냥 전송돼요. 감정에 휩싸인 메시지만 그 순간 지퍼가 잠겨요.\n" +
                "• 친구끼리 장난으로 욕하는 분위기면 통과, 다투는 중 홧김에 쓰는 말이면 잠가요.\n" +
                "• 상대가 윗사람(방 이름에 선생님·부장님 등, 또는 내가 계속 존댓말을 쓰는 방)이면 무례한 말을 잠가요.\n" +
                "• 잠긴 지퍼는 왼쪽으로 밀면 풀려요. 차분하게/존댓말로 바꾸기, 다시 쓰기, 그대로 보내기도 고를 수 있어요.\n" +
                "• API 키를 넣으면 판단을 위해 화면의 최근 대화 몇 줄과 보낼 문장이 OpenAI로 보내져요. 키가 없으면 기기 안에서만 판단해요.\n" +
                "• 시연용 시제품이에요. 메신저 앱 업데이트로 버튼 위치를 못 찾을 수 있어요."
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceOn()
        status.text = if (on) "✅ 접근성 서비스 켜짐" else "⚠️ 접근성 서비스가 꺼져 있어요"
    }

    private fun refreshSens() {
        val cur = Prefs.sens(this)
        sensButtons.forEachIndexed { i, btn -> btn.alpha = if (i == cur) 1f else 0.45f }
    }

    private fun isServiceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
    }
}
