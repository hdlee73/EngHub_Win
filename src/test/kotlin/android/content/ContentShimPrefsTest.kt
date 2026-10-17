package android.content

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 윈도우 앱은 설정을 `~/.enghub/prefs` 폴더의 json 파일로 저장한다. 이 파일을 메모장 같은 편집기로 열어 저장하면
 * UTF-8 BOM(EF BB BF)이 앞에 붙는다. BOM 이 있으면 `JSONObject(...)` 가 예외를 던지고, 그 예외가 조용히
 * 삼켜져 **모든 설정이 사라진 것처럼** 보인 뒤 다음 저장 때 실제로 덮어써 버린다. 사용자가 겪는
 * "설정이 저절로 초기화된다"의 원인이 되므로 BOM 을 떼고 읽어야 한다.
 */
class ContentShimPrefsTest {

    private fun tempPrefsFile(): File {
        val dir = Files.createTempDirectory("enghub_prefs").toFile()
        dir.deleteOnExit()
        return File(dir, "probe.json")
    }

    @Test
    fun readsSettingsFileWithUtf8Bom() {
        val file = tempPrefsFile()
        val body = """{"threshold":260,"engine":"array","mic":"bt-headset","enabled":true}"""
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        file.writeBytes(bom + body.toByteArray(Charsets.UTF_8))

        val prefs = FileSharedPreferences(file)

        assertEquals("BOM 이 붙어 있어도 정수 설정을 읽어야 한다", 260, prefs.getInt("threshold", -1))
        assertEquals("BOM 이 붙어 있어도 문자열 설정을 읽어야 한다", "array", prefs.getString("engine", null))
        assertEquals("BOM 이 붙어 있어도 마이크 설정을 읽어야 한다", "bt-headset", prefs.getString("mic", null))
        assertEquals("BOM 이 붙어 있어도 불리언 설정을 읽어야 한다", true, prefs.getBoolean("enabled", false))
    }

    @Test
    fun readsPlainSettingsFileUnchanged() {
        val file = tempPrefsFile()
        file.writeBytes("""{"threshold":260,"engine":"array"}""".toByteArray(Charsets.UTF_8))

        val prefs = FileSharedPreferences(file)

        assertEquals(260, prefs.getInt("threshold", -1))
        assertEquals("array", prefs.getString("engine", null))
    }

    @Test
    fun savesAndReloadsRoundTrip() {
        val file = tempPrefsFile()
        FileSharedPreferences(file).edit().putInt("threshold", 200).putString("engine", "sapi").apply()

        val reloaded = FileSharedPreferences(file)
        assertEquals(200, reloaded.getInt("threshold", -1))
        assertEquals("sapi", reloaded.getString("engine", null))
        assertEquals("저장 파일에는 BOM 을 붙이지 않는다", false, file.readBytes().take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
    }
}
