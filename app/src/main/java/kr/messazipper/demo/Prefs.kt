package kr.messazipper.demo

import android.content.Context

/** 설정 저장소. API 키는 코드에 넣지 않고 앱 화면에서 입력받아 기기 안에만 저장한다. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("mz", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** 0 낮음 / 1 보통 / 2 높음 */
    fun sens(c: Context) = sp(c).getInt("sens", 1)
    fun setSens(c: Context, v: Int) = sp(c).edit().putInt("sens", v).apply()

    /** true = 윗사람(선생님·목사님 등) 모드 */
    fun formal(c: Context) = sp(c).getBoolean("formal", false)
    fun setFormal(c: Context, v: Boolean) = sp(c).edit().putBoolean("formal", v).apply()

    fun apiKey(c: Context) = sp(c).getString("apikey", "") ?: ""
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("apikey", v.trim()).apply()
}
