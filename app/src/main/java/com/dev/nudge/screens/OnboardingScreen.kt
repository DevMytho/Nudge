package com.dev.nudge.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dev.nudge.R
import kotlinx.coroutines.launch

/**
 * 🚀 First-run onboarding (spec §5): three pages — what Nudge is, reminder
 * permissions, and sync vs guest. Completing it flips
 * [com.dev.nudge.data.UserSettings.onboardingCompleted]; the flow is also
 * replayable from Settings ("Replay tutorial").
 */
@Composable
fun OnboardingScreen(
    onRequestPermissions: () -> Unit,
    onSignIn: () -> Unit,
    onDone: () -> Unit
) {

    val pages = 3
    val pagerState = rememberPagerState(pageCount = { pages })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {

        // Skip — top right
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (pagerState.currentPage < pages - 1) {
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> OnboardingPage(
                    icon = { Icon(Icons.Default.CheckCircle, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary) },
                    title = stringResource(R.string.onboarding_welcome_title),
                    body = stringResource(R.string.onboarding_welcome_body)
                )

                1 -> OnboardingPage(
                    icon = { Icon(Icons.Default.NotificationsActive, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary) },
                    title = stringResource(R.string.onboarding_reminders_title),
                    body = stringResource(R.string.onboarding_reminders_body),
                    action = {
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.onboarding_reminders_allow))
                        }
                    }
                )

                else -> OnboardingPage(
                    icon = { Icon(Icons.Default.CloudSync, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary) },
                    title = stringResource(R.string.onboarding_sync_title),
                    body = stringResource(R.string.onboarding_sync_body),
                    action = {
                        Button(
                            onClick = onSignIn,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.onboarding_sign_in))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.onboarding_guest))
                        }
                    }
                )
            }
        }

        // Page dots
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            repeat(pages) { i ->
                val active = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    R.string.onboarding_page_of,
                    pagerState.currentPage + 1,
                    pages
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (pagerState.currentPage < pages - 1) {
                OnboardingNextButton(pagerState = pagerState, pages = pages)
            } else {
                Button(onClick = onDone) {
                    Text(stringResource(R.string.onboarding_done))
                }
            }
        }
    }
}

@Composable
private fun OnboardingNextButton(
    pagerState: androidx.compose.foundation.pager.PagerState,
    pages: Int
) {
    val scope = rememberCoroutineScope()
    Button(
        onClick = {
            scope.launch {
                pagerState.animateScrollToPage(
                    (pagerState.currentPage + 1).coerceAtMost(pages - 1)
                )
            }
        }
    ) {
        Text(stringResource(R.string.onboarding_next))
    }
}

@Composable
private fun OnboardingPage(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        icon()
        Spacer(Modifier.height(28.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        action?.let {
            Spacer(Modifier.height(24.dp))
            it()
        }
    }
}