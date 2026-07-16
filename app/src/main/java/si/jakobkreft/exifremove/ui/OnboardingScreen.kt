package si.jakobkreft.exifremove.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import si.jakobkreft.exifremove.R

private const val PAGE_COUNT = 4

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == PAGE_COUNT - 1

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                OnboardingPage(page)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDone,
                    enabled = !isLast,
                ) {
                    Text(if (isLast) "" else stringResource(R.string.skip))
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(PAGE_COUNT) { index ->
                        val color by animateColorAsState(
                            if (index == pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            label = "dot",
                        )
                        Box(
                            modifier = Modifier
                                .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    if (isLast) {
                        onDone()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }) {
                    Text(
                        stringResource(if (isLast) R.string.get_started else R.string.next)
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(page: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (page) {
            0 -> IllustrationLeaks()
            1 -> IllustrationShare()
            2 -> IllustrationTemplates()
            3 -> IllustrationVerified()
        }
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(
                when (page) {
                    0 -> R.string.onb1_title
                    1 -> R.string.onb2_title
                    2 -> R.string.onb3_title
                    else -> R.string.onb4_title
                }
            ),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(
                when (page) {
                    0 -> R.string.onb1_body
                    1 -> R.string.onb2_body
                    2 -> R.string.onb3_body
                    else -> R.string.onb4_body
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IllustrationCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
private fun IllustrationRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(8.dp)
                    .size(22.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun IllustrationLeaks() {
    IllustrationCard {
        IllustrationRow(Icons.Outlined.LocationOn, stringResource(R.string.onb1_where))
        IllustrationRow(Icons.Outlined.Schedule, stringResource(R.string.onb1_when))
        IllustrationRow(Icons.Outlined.PhoneAndroid, stringResource(R.string.onb1_what))
    }
}

@Composable
private fun IllustrationShare() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepCircle(Icons.Outlined.Image, MaterialTheme.colorScheme.surfaceVariant)
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepCircle(Icons.Filled.Share, MaterialTheme.colorScheme.primaryContainer)
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepCircle(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.tertiaryContainer)
    }
}

@Composable
private fun StepCircle(icon: ImageVector, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = CircleShape, color = color) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier
                .padding(18.dp)
                .size(28.dp),
        )
    }
}

@Composable
private fun IllustrationTemplates() {
    IllustrationCard {
        TemplateExampleRow(
            stringResource(R.string.sum_location),
            stringResource(R.string.action_remove),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        TemplateExampleRow(
            stringResource(R.string.sum_date),
            stringResource(R.string.action_randomize),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        TemplateExampleRow(
            stringResource(R.string.sum_other),
            stringResource(R.string.action_keep),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun TemplateExampleRow(
    label: String,
    action: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun IllustrationVerified() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                Icons.Outlined.VerifiedUser,
                contentDescription = null,
                modifier = Modifier
                    .padding(28.dp)
                    .size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        Card {
            Text(
                "GPS 46.05, 14.51",
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
