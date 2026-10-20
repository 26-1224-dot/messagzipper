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
            text = "말투 변환용 OpenAI API 키 (선택)"
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

        root.addView(TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
            text = "• 판정은 기기 안에서만 해요. 글자는 '말투 변환하기'를 누른 순간에만 변환 서비스로 보내져요.\n" +
                "• 쓰는 법: 카톡을 열면 오른쪽에 모드 버튼이 떠요. 눌러서 친구 ↔ 윗사람을 바꿔요.\n" +
                "• 부적절한 문장을 쓰면 전송 버튼 위에 지퍼가 덮여요. 지퍼를 누르면 선택지가 나와요.\n" +
                "• 이 앱은 대신 전송하지 않아요. 보낼지는 항상 본인이 정해요.\n" +
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
