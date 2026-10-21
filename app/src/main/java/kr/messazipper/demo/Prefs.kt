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

    /** 방별로 직접 정한 모드 기억. null = 아직 안 정함(자동 판별) */
    fun roomMode(c: Context, title: String): Boolean? {
        val v = sp(c).getInt("room:$title", -1)
        return if (v < 0) null else v == 1
    }
    fun setRoomMode(c: Context, title: String, formal: Boolean) =
        sp(c).edit().putInt("room:$title", if (formal) 1 else 0).apply()
    fun clearRoomModes(c: Context) {
        val e = sp(c).edit()
        sp(c).all.keys.filter { it.startsWith("room:") }.forEach { e.remove(it) }
        e.apply()
    }

    fun apiKey(c: Context) = sp(c).getString("apikey", "") ?: ""
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("apikey", v.trim()).apply()
}
