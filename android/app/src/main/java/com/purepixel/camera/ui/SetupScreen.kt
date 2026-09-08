package com.purepixel.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.purepixel.camera.R

private val SetupAccent = Color(0xFF1C85F3)
private val SetupPillShape = RoundedCornerShape(28.dp)

/** Full-screen, localized first-run guide. The image resources switch with the app locale. */
@Composable
fun SetupScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val pages = listOf(R.drawable.setup_1, R.drawable.setup_2, R.drawable.setup_3)
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val isLastPage = pageIndex == pages.lastIndex

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.foundation.Image(
            painter = painterResource(pages[pageIndex]),
            contentDescription = null,
            // Preserve the authored 1080 x 2424 composition on every display.
            // Fit may scale it up or down, but never stretches either axis.
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageIndex) {
                    var dragDistance = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            dragDistance += dragAmount
                            change.consume()
                        },
                        onDragEnd = {
                            when {
                                dragDistance <= -72f -> pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex)
                                dragDistance >= 72f -> pageIndex = (pageIndex - 1).coerceAtLeast(0)
                            }
                        }
                    )
                }
        )

        SetupNavigation(
            pageIndex = pageIndex,
            pageCount = pages.size,
            onPrevious = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
            onNext = { pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex) },
            onFinished = onFinished,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 44.dp)
        )
    }
}

@Composable
private fun SetupNavigation(
    pageIndex: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLastPage = pageIndex == pageCount - 1
    Surface(
        modifier = modifier.width(172.dp).height(56.dp),
        color = SetupAccent,
        shape = SetupPillShape
    ) {
        if (isLastPage) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SetupArrow(
                    previous = true,
                    enabled = true,
                    onClick = onPrevious
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxSize().clickable(onClick = onFinished),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.setup_done),
                        color = Color.Black,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.size(48.dp))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SetupArrow(previous = true, enabled = pageIndex > 0, onClick = onPrevious)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    repeat(pageCount) { index -> SetupPageDot(active = index == pageIndex) }
                }
                SetupArrow(previous = false, enabled = true, onClick = onNext)
            }
        }
    }
}

@Composable
private fun SetupArrow(previous: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            painter = painterResource(
                if (previous) R.drawable.ic_pixel_chevron_left else R.drawable.ic_pixel_chevron_right
            ),
            contentDescription = null,
            tint = Color.Black.copy(alpha = if (enabled) 1f else .24f),
            modifier = Modifier.size(34.dp)
        )
    }
}

@Composable
private fun SetupPageDot(active: Boolean) {
    if (active) {
        Box(
            modifier = Modifier
                .size(15.dp)
                .border(2.dp, Color.Black, CircleShape)
                .clip(CircleShape)
        )
    } else {
        Box(modifier = Modifier.size(13.dp).background(Color.Black, CircleShape))
    }
}
