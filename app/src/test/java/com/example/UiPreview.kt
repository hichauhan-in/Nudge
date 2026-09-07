package com.example

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue

fun SemanticsNodeInteraction.savePreview(name: String) {
    if (System.getProperty("roborazzi.test.record") != "true") return
    val file = File("build/ui-previews/$name.png")
    file.parentFile?.mkdirs()
    captureRoboImage(file.path)
    val bitmap = ImageIO.read(file)
    val colors = mutableSetOf<Int>()
    for (row in 0 until bitmap.height step 3) {
        for (column in 0 until bitmap.width step 3) colors.add(bitmap.getRGB(column, row))
    }
    assertTrue("Rendered preview must not be blank", colors.size > 10)
}