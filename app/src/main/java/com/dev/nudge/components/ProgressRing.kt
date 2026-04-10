package com.dev.nudge.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dev.nudge.CardBeige
import com.dev.nudge.ui.theme.SoftBlue

@Composable
fun ProgressRing(
    progress: Float, // 0f to 1f
    timeText: String
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(220.dp)
    ) {

        Canvas(modifier = Modifier.fillMaxSize()) {

            // Background circle
            drawCircle(
                color = CardBeige,
                style = Stroke(width = 20f)
            )

            // Progress arc
            drawArc(
                color = SoftBlue,
                startAngle = -90f,
                sweepAngle = 360 * progress,
                useCenter = false,
                style = Stroke(width = 20f, cap = StrokeCap.Round)
            )
        }

        Text(
            text = timeText,
            style = MaterialTheme.typography.headlineLarge
        )
    }
}
