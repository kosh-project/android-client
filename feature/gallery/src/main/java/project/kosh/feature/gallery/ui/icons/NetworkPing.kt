package project.kosh.feature.gallery.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
public val NetworkPingIcon: ImageVector
  get() {
    if (_networkPingIcon != null) {
      return _networkPingIcon!!
    }
    _networkPingIcon =
      ImageVector.Builder(
          name = "NetworkPing",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.Companion.NonZero,
          ) {
            moveTo(5f, 18f)
            quadTo(4.58f, 18f, 4.29f, 17.71f)
            quadTo(4f, 17.43f, 4f, 17f)
            reflectiveQuadTo(4.29f, 16.29f)
            reflectiveQuadTo(5f, 16f)
            horizontalLineToRelative(5.5f)
            lineTo(2.7f, 8.2f)
            quadTo(2.43f, 7.93f, 2.41f, 7.51f)
            reflectiveQuadTo(2.7f, 6.8f)
            quadTo(2.98f, 6.52f, 3.4f, 6.52f)
            reflectiveQuadTo(4.1f, 6.8f)
            lineTo(12f, 14.68f)
            lineToRelative(5.2f, -5.2f)
            quadTo(17.1f, 9.25f, 17.05f, 9.01f)
            reflectiveQuadTo(17f, 8.5f)
            quadTo(17f, 7.45f, 17.73f, 6.72f)
            reflectiveQuadTo(19.5f, 6f)
            reflectiveQuadToRelative(1.78f, 0.72f)
            reflectiveQuadTo(22f, 8.5f)
            reflectiveQuadToRelative(-0.72f, 1.77f)
            reflectiveQuadTo(19.5f, 11f)
            quadToRelative(-0.22f, 0f, -0.44f, -0.04f)
            quadTo(18.85f, 10.93f, 18.65f, 10.85f)
            lineTo(13.5f, 16f)
            horizontalLineTo(19f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(20f, 17f)
            reflectiveQuadToRelative(-0.29f, 0.71f)
            reflectiveQuadTo(19f, 18f)
            horizontalLineTo(5f)
            close()
          }
        }
        .build()
    return _networkPingIcon!!
  }

private var _networkPingIcon: ImageVector? = null
