package com.deepstudent.design

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.deepstudent.design.generated.GeneratedBrand
import com.deepstudent.design.generated.generatedIcons

enum class DstuIconWeight { Regular, Bold }

enum class DstuNavigationIcon(val id: String) {
    Chat("chat-v2"), Library("learning-hub"), Media("media"), Todo("todo"),
    Skills("skills-management"), Cards("flashcards"), UiLab("ui-lab"),
    Data("data-management"), Settings("settings"),
}

object DstuIcons {
    private val vectors by lazy {
        DstuNavigationIcon.entries.associateWith { icon ->
            val paths = generatedIcons.getValue(icon.id)
            mapOf(
                DstuIconWeight.Regular to iconVector(icon.id, paths.first),
                DstuIconWeight.Bold to iconVector(icon.id, paths.second),
            )
        }
    }

    fun navigation(icon: DstuNavigationIcon, weight: DstuIconWeight = DstuIconWeight.Regular): ImageVector =
        vectors.getValue(icon).getValue(weight)
}

private fun iconVector(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 256f, 256f).apply {
        addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
    }.build()

private fun brandVector(
    name: String,
    width: Float,
    paths: List<Pair<String, Boolean>>,
    foreground: Color,
    background: Color,
): ImageVector = ImageVector.Builder(name, (24 * width / 147).dp, 24.dp, width, 147f).apply {
    paths.forEach { (path, isForeground) ->
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(if (isForeground) foreground else background),
        )
    }
}.build()

@Composable
fun DstuBrandMark(contentDescription: String?, modifier: Modifier = Modifier) {
    val colors = DstuTheme.colors
    val image = remember(colors) {
        brandVector("brand-mark", 147f, GeneratedBrand.mark, colors.foreground, colors.background)
    }
    Image(imageVector = image, contentDescription = contentDescription, modifier = modifier)
}

@Composable
fun DstuBrandLogo(contentDescription: String?, modifier: Modifier = Modifier) {
    val colors = DstuTheme.colors
    val image = remember(colors) {
        brandVector("brand-logo", 409f, GeneratedBrand.logo, colors.foreground, colors.background)
    }
    Image(imageVector = image, contentDescription = contentDescription, modifier = modifier)
}

@Composable
fun DstuNavigationIcon(
    icon: DstuNavigationIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    weight: DstuIconWeight = DstuIconWeight.Regular,
) {
    Icon(DstuIcons.navigation(icon, weight), contentDescription, modifier)
}
