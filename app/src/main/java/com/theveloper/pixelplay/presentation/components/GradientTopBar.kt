package com.theveloper.pixelplay.presentation.components

import androidx.compose.ui.draw.clip

import androidx.compose.material.icons.rounded.MusicNote

import androidx.compose.material.icons.rounded.AccountCircle

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.PixelPlayStatusBarStyle
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreGradientTopBar(
    title: String,
    startColor: Color,
    endColor: Color,
    contentColor: Color,
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigationIconClick: () -> Unit,
) {
    val gradientBrush = remember(startColor, endColor) {
        Brush.verticalGradient(colors = listOf(startColor, endColor))
    }

    PixelPlayStatusBarStyle(color = startColor)

    LargeTopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
            Text(
                modifier = Modifier.padding(start = 6.dp),
                text = title,
                color = contentColor,
                fontFamily = GoogleSansRounded
            )
        },
        expandedHeight = 160.dp,
        modifier = Modifier.background(brush = gradientBrush),
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(start = 10.dp),
                onClick = onNavigationIconClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = contentColor
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = startColor
                )
            }
        },
        colors = topAppBarColors(
            containerColor = Color.Transparent, // Background is handled by the gradient brush
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer, // Or a color that contrasts well with your typical gradient
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer // Same as title
        )
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeGradientTopBar(
    onNavigationIconClick: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    onBetaClick: () -> Unit,
    onTelegramClick: () -> Unit,
    onMenuClick: () -> Unit = {},
    isScrolled: Boolean = false,
    musicAccountAvatar: String? = null,
    isMusicConnected: Boolean = false,
) {
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHighest

    PixelPlayStatusBarStyle(color = surfaceContainerHigh)

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "topbar_alpha_transition"
    )

    TopAppBar(
        modifier = Modifier.background(surfaceContainerHigh.copy(alpha = animatedAlpha)),
        title = {
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    letterSpacing = (-0.6).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(end = 16.dp)
            ) {
                FilledIconButton(
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = onTelegramClick
                ) {
                    if (isMusicConnected && musicAccountAvatar != null) {
                        SmartImage(musicAccountAvatar, "YouTube Music account",
                            modifier = Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape),
                            targetSize = coil.size.Size(128, 128))
                    } else Icon(
                        imageVector = if (isMusicConnected) Icons.Rounded.AccountCircle else Icons.Rounded.MusicNote,
                        contentDescription = if (isMusicConnected) "YouTube Music account" else "Connect YouTube Music"
                    )
                }
                FilledIconButton(
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = onNavigationIconClick
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_settings_24),
                        contentDescription = stringResource(R.string.common_settings)
                    )
                }
            }
        },
        colors = topAppBarColors(
            containerColor = Color.Transparent
        )
    )
}
