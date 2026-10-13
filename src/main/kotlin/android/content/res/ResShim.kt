package android.content.res

import android.util.DisplayMetrics
import com.hdlee73.englishstudy.R
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class AssetManager {
    fun open(fileName: String): InputStream {
        val clean = fileName.trimStart('/')
        val resourcePath = "assets/$clean"
        Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath)?.let { return it }
        AssetManager::class.java.classLoader?.getResourceAsStream(resourcePath)?.let { return it }
        val localFile = File("src/main/resources/$resourcePath")
        if (localFile.exists()) return FileInputStream(localFile)
        throw java.io.FileNotFoundException("Asset not found: $fileName")
    }
}

class Configuration {
    var screenWidthDp: Int = 420
    var screenHeightDp: Int = 840
}

class Resources {
    val displayMetrics: DisplayMetrics = DisplayMetrics()
    val configuration: Configuration = Configuration()

    fun openRawResource(id: Int): InputStream {
        val path = R.raw.pathForId(id)
        Thread.currentThread().contextClassLoader?.getResourceAsStream(path)?.let { return it }
        Resources::class.java.classLoader?.getResourceAsStream(path)?.let { return it }
        val localFile = File("src/main/resources/$path")
        if (localFile.exists()) return FileInputStream(localFile)
        throw java.io.FileNotFoundException("Raw resource not found for id=$id ($path)")
    }
}

class ColorStateList private constructor(val color: Int) {
    companion object {
        fun valueOf(color: Int): ColorStateList = ColorStateList(color)
    }
}
