package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import com.hdlee73.englishstudy.R
import org.xml.sax.InputSource
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

private fun openClasspathOrFile(path: String): InputStream? {
    Thread.currentThread().contextClassLoader?.getResourceAsStream(path)?.let { return it }
    R::class.java.classLoader?.getResourceAsStream(path)?.let { return it }
    val local = File("src/main/resources/$path")
    if (local.exists()) return FileInputStream(local)
    return null
}

@Composable
fun painterResource(id: Int): Painter {
    val path = R.drawable.pathForId(id)
    val density = LocalDensity.current
    if (path.endsWith(".xml", ignoreCase = true)) {
        val vector = remember(id, density) {
            runCatching {
                openClasspathOrFile(path)?.use { stream ->
                    loadXmlImageVector(InputSource(stream), density)
                }
            }.getOrNull()
        }
        if (vector != null) {
            return rememberVectorPainter(vector)
        }
    }
    val bitmap = remember(id) {
        runCatching {
            openClasspathOrFile(path)?.use { stream ->
                loadImageBitmap(stream)
            }
        }.getOrNull()
    }
    return if (bitmap != null) BitmapPainter(bitmap) else ColorPainter(Color.Transparent)
}
