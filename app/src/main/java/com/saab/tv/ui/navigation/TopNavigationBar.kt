package com.saab.tv.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.saab.tv.R
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.ui.components.SaabMotion
import com.saab.tv.ui.utils.requestFocusOrFallback
import com.saab.tv.ui.utils.requestFocusSafely
import com.saab.tv.ui.profiles.ProfileAssets
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.text.style.TextOverflow

/**
 * Top Navigation Bar for TV interface.
 * An alternative to the left NavDrawer, displayed as a horizontal row at the top.
 * 
 * Layout:
 * [Exit] [Profile] [Settings]  |  [Search]  |  [Home] [Movies] [Series]
 *        LEFT EDGE                MIDDLE-LEFT        CENTER
 * 
 * Icon-only by default, text reveals on focus with bubble background
 */
@Composable
fun TopNavigationBar(
    currentDestination: NavDestination,
    currentProfile: ProfileEntity?,
    topNavRequesters: Map<NavDestination, FocusRequester>,
    onNavigate: (NavDestination) -> Unit,
    onEnterContent: () -> Unit,
    onLogout: () -> Unit = {},
    onExit: () -> Unit = {},
    hideNavigation: Boolean = false,
    navigationActive: Boolean = true,
    content: @Composable () -> Unit
) {
    // Center: Search + content tabs, shared with the drawer and focus policy.
    val centerItems = NavigationDestinations.topBar

    // Left Logic (Settings + Menu)
    val settingsItem = NavDestination.Settings
    val profileItem = NavDestination.Profile
    val exitItem = NavDestination.Exit

    // Focus Tracking - track separately for each section
    var isSettingsAreaFocused by remember { mutableStateOf(false) }
    var isCenterAreaFocused by remember { mutableStateOf(false) }
    // Combined: navbar is active if either section has focus
    val hidden = hideNavigation || com.saab.tv.ui.trailer.InlineTrailerAnchor.fullscreenActive
    val navigationBlocked = !navigationActive || hidden || com.saab.tv.ui.trailer.LocalManualTrailerActive.current ||
        com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null
    val isTopNavActive = (isSettingsAreaFocused || isCenterAreaFocused) && !navigationBlocked
    val focusRecoveryBlocked = hidden || com.saab.tv.ui.trailer.LocalManualTrailerActive.current ||
        com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null
    val focusGuard = rememberNavigationFocusGuard(
        navigationBlocked, Key.DirectionUp, onEnterContent, focusRecoveryBlocked
    )
    val boundaryPressGate = remember { DpadFreshPressGate() }
    val focusHandoffRegistry = LocalNavigationFocusHandoffRegistry.current
    val focusHandoff: (KeyEvent?) -> Boolean = remember(focusGuard, currentDestination, topNavRequesters) {
        { event ->
            val requestFocus = {
                topNavRequesters[currentDestination]?.requestFocusSafely() ?: false
            }
            if (event == null) focusGuard.handoffBack(requestFocus)
            else focusGuard.handoff(event, requestFocus)
        }
    }
    val focusHandoffOwner = remember(focusGuard) { Any() }
    SideEffect {
        if (navigationActive) focusHandoffRegistry?.register(focusHandoffOwner, focusHandoff)
        else focusHandoffRegistry?.clear(focusHandoffOwner)
    }
    DisposableEffect(focusHandoffRegistry, focusHandoffOwner) {
        onDispose { focusHandoffRegistry?.clear(focusHandoffOwner) }
    }
    
    // Back from the navigation surface is an app-exit request and must confirm.
    AppBackHandler(enabled = isTopNavActive, priority = AppBackPriority.Navigation) {
        onExit()
    }

    // Dropdown State
    var isSettingsFocused by remember { mutableStateOf(false) }
    var isProfileFocused by remember { mutableStateOf(false) }
    var isExitFocused by remember { mutableStateOf(false) }
    LaunchedEffect(navigationBlocked) {
        if (navigationBlocked) {
            isSettingsAreaFocused = false
            isCenterAreaFocused = false
            isSettingsFocused = false
            isProfileFocused = false
            isExitFocused = false
        }
    }
    // Menu is open if Settings or any menu item is focused
    // A child can report focus for one frame before NavigationFocusGuard rejects
    // an unsolicited focus restore. Only expand the menu after the shell accepts
    // focus, matching NavDrawer's guarded expansion behavior.
    val showSettingsMenu = isTopNavActive &&
        (isSettingsFocused || isProfileFocused || isExitFocused)

    val backgroundColor = MaterialTheme.colorScheme.background
    val showStaticMask = currentDestination in listOf(
        NavDestination.Home,
        NavDestination.Movies,
        NavDestination.Series,
        NavDestination.Ott
    )

    // Only arm navigation entry after focused content declines the key. Preview
    // sees handled Up presses too and can make later focus restoration open the
    // menu without a fresh remote action.
    Box(modifier = Modifier.fillMaxSize().onKeyEvent(focusGuard::key)) {

        // LAYER 1: Content (Full Screen)
        Box(modifier = Modifier.fillMaxSize().zIndex(
            if (hidden || com.saab.tv.ui.trailer.InlineTrailerAnchor.session != null) 3f else 0f)) {
            CompositionLocalProvider(LocalNavigationFocusHandoff provides focusHandoff) {
                content()
            }
        }

        // LAYER 2: Static Top Gradient (Hero Mask)
        if (showStaticMask && !hidden) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp) // Height to cover header area
                    .zIndex(1f)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to backgroundColor.copy(alpha = 0.8f),
                                0.12f to backgroundColor.copy(alpha = 0.72f),
                                0.25f to backgroundColor.copy(alpha = 0.62f),
                                0.38f to backgroundColor.copy(alpha = 0.50f),
                                0.50f to backgroundColor.copy(alpha = 0.38f),
                                0.65f to backgroundColor.copy(alpha = 0.24f),
                                0.78f to backgroundColor.copy(alpha = 0.13f),
                                0.90f to backgroundColor.copy(alpha = 0.05f),
                                1.0f to Color.Transparent
                            ),
                            startY = 0f,
                            endY = 180f
                        )
                    )
            )
        }

        // LAYER 3: Dynamic Focused Gradient
        androidx.compose.animation.AnimatedVisibility(
            visible = isTopNavActive,
            enter = androidx.compose.animation.fadeIn(animationSpec = SaabMotion.navigationRevealSpec()),
            exit = androidx.compose.animation.fadeOut(animationSpec = SaabMotion.navigationRevealSpec()),
            modifier = Modifier.zIndex(1.5f).fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to backgroundColor.copy(alpha = 0.95f),
                                0.12f to backgroundColor.copy(alpha = 0.90f),
                                0.25f to backgroundColor.copy(alpha = 0.82f),
                                0.38f to backgroundColor.copy(alpha = 0.70f),
                                0.50f to backgroundColor.copy(alpha = 0.55f),
                                0.65f to backgroundColor.copy(alpha = 0.38f),
                                0.78f to backgroundColor.copy(alpha = 0.20f),
                                0.90f to backgroundColor.copy(alpha = 0.08f),
                                1.0f to Color.Transparent
                            ),
                            startY = 0f,
                            endY = 400f
                        )
                    )
            )
        }

        // Noise overlay to reduce gradient banding on budget panels
        if (!hidden) com.saab.tv.ui.components.NoiseOverlay(modifier = Modifier.zIndex(1.6f))

        // LAYER 4: Main Navigation Bar (Settings + Center Items)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .graphicsLayer { alpha = if (hidden) 0f else 1f }
                .then(Modifier.focusProperties { canFocus = !navigationBlocked })
                .zIndex(2f)
                .onFocusChanged { 
                    isCenterAreaFocused = focusGuard.focus("top", it.hasFocus)
                }.focusGroup()
        ) {
            // Profile Button (Left aligned) - fades with navbar
            val profileAlpha by animateFloatAsState(
                targetValue = if (isTopNavActive) 1f else 0f,
                animationSpec = SaabMotion.navigationRevealSpec(),
                label = "profileAlpha"
            )
            
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .graphicsLayer { alpha = profileAlpha }
            ) {
                TopNavProfileAvatar(
                    profile = currentProfile,
                    onNavigate = onLogout,
                    modifier = Modifier
                        // Keep the faded profile control out of traversal until the
                        // top navigation has actually accepted focus.
                        .focusProperties { canFocus = !navigationBlocked && isTopNavActive }
                        .focusRequester(topNavRequesters[profileItem]!!)
                        .testTag("top-nav-profile-avatar")
                        .onFocusChanged { isProfileFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                             if (event.type == KeyEventType.KeyDown) {
                                 when(event.key) {
                                     Key.DirectionDown -> {
                                         topNavRequesters[settingsItem].requestFocusOrFallback(topNavRequesters[profileItem]!!)
                                         true
                                     }
                                     Key.DirectionRight -> {
                                         topNavRequesters[NavDestination.Search].requestFocusOrFallback(topNavRequesters[profileItem]!!)
                                         true
                                     }
                                     else -> false
                                 }
                             } else false
                        }
                )
            }

            // Center Navigation Items
            Row(
                 modifier = Modifier
                    .align(Alignment.Center)
                    .focusGroup(),
                 horizontalArrangement = Arrangement.spacedBy(8.dp),
                 verticalAlignment = Alignment.CenterVertically
            ) {
                 centerItems.forEachIndexed { index, destination ->
                     val isFirst = index == 0
                     val isSelected = currentDestination == destination

                     TopNavItem(
                         destination = destination,
                         isSelected = isSelected,
                         isTopNavActive = isTopNavActive,
                         onNavigate = {
                             if (currentDestination == destination) onEnterContent()
                             else onNavigate(destination)
                         },
                         modifier = Modifier
                             .focusProperties { canFocus = !navigationBlocked }
                             .focusRequester(topNavRequesters[destination]!!)
                             .onPreviewKeyEvent { event ->
                                 if (event.type == KeyEventType.KeyDown) {
                                     when(event.key) {
                                         Key.DirectionLeft -> {
                                             if (isFirst) {
                                                 topNavRequesters[profileItem].requestFocusOrFallback(topNavRequesters[destination]!!)
                                                 true
                                             } else false
                                         }
                                         Key.DirectionDown -> {
                                             dispatchFreshDpadAction(
                                                 event = event,
                                                 expectedKey = Key.DirectionDown,
                                                 gate = boundaryPressGate,
                                                 action = onEnterContent
                                             )
                                         }
                                         else -> false
                                     }
                                 } else false
                             }
                     )
                 }
            }
        }

        // LAYER 5: Dropdown Menu Overlay. Closed items must not remain focus targets:
        // focus restoration can otherwise land on an invisible menu item and reopen it.
        val dropdownAlpha by animateFloatAsState(
            targetValue = if (showSettingsMenu) 1f else 0f,
            animationSpec = SaabMotion.navigationRevealSpec(),
            label = "dropdownAlpha"
        )
        
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 70.dp)
                .zIndex(if (showSettingsMenu) 5f else -1f) // Behind everything when hidden
                .graphicsLayer { alpha = if (hidden) 0f else dropdownAlpha }
                .onFocusChanged { isSettingsAreaFocused = focusGuard.focus("dropdown", it.hasFocus) }.focusGroup()
        ) {
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                // SETTINGS
                TopNavItem(
                    destination = settingsItem,
                    isSelected = currentDestination == settingsItem,
                    isTopNavActive = true,
                    onNavigate = { 
                        if (currentDestination == settingsItem) onEnterContent() 
                        else onNavigate(settingsItem)
                    },
                    modifier = Modifier
                        .focusProperties {
                            // A Settings screen handoff must be able to focus this
                            // destination before its dropdown becomes visible.
                            canFocus = !navigationBlocked && (showSettingsMenu || currentDestination == settingsItem)
                        }
                        .focusRequester(topNavRequesters[settingsItem]!!)
                        .onFocusChanged { isSettingsFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                             if (currentDestination == settingsItem &&
                                 event.key == Key.DirectionRight
                             ) {
                                 return@onPreviewKeyEvent dispatchFreshDpadAction(
                                     event = event,
                                     expectedKey = Key.DirectionRight,
                                     gate = boundaryPressGate,
                                     action = onEnterContent
                                 )
                             }
                             if (event.type == KeyEventType.KeyDown) {
                                 when(event.key) {
                                     Key.DirectionUp -> {
                                         topNavRequesters[profileItem].requestFocusOrFallback(topNavRequesters[settingsItem]!!)
                                         true
                                     }
                                     Key.DirectionDown -> {
                                         topNavRequesters[exitItem].requestFocusOrFallback(topNavRequesters[settingsItem]!!)
                                         true
                                     }
                                     Key.DirectionRight -> {
                                          topNavRequesters[NavDestination.Search].requestFocusOrFallback(topNavRequesters[settingsItem]!!)
                                         true
                                     }
                                     else -> false
                                 }
                             } else false
                        }
                )

                Spacer(Modifier.height(4.dp))

                // EXIT
                TopNavItem(
                    destination = exitItem,
                    isSelected = false,
                    isTopNavActive = true,
                    onNavigate = onExit,
                    modifier = Modifier
                        .focusProperties { canFocus = showSettingsMenu && !navigationBlocked }
                        .focusRequester(topNavRequesters[exitItem]!!)
                        .onFocusChanged { isExitFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                             if (currentDestination == settingsItem &&
                                 event.key == Key.DirectionRight
                             ) {
                                 return@onPreviewKeyEvent dispatchFreshDpadAction(
                                     event = event,
                                     expectedKey = Key.DirectionRight,
                                     gate = boundaryPressGate,
                                     action = onEnterContent
                                 )
                             }
                             if (currentDestination == settingsItem &&
                                 event.key == Key.DirectionDown
                             ) {
                                 return@onPreviewKeyEvent dispatchFreshDpadAction(
                                     event = event,
                                     expectedKey = Key.DirectionDown,
                                     gate = boundaryPressGate,
                                     action = onEnterContent
                                 )
                             }
                             if (event.type == KeyEventType.KeyDown) {
                                 when(event.key) {
                                     Key.DirectionUp -> {
                                         topNavRequesters[settingsItem].requestFocusOrFallback(topNavRequesters[exitItem]!!)
                                         true
                                     }
                                     Key.DirectionDown -> {
                                         true
                                     }
                                      Key.DirectionRight -> {
                                           topNavRequesters[NavDestination.Search].requestFocusOrFallback(topNavRequesters[exitItem]!!)
                                          true
                                     }
                                     else -> false
                                 }
                             } else false
                        }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TopNavItem(
    destination: NavDestination,
    isSelected: Boolean,
    isTopNavActive: Boolean,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val accentColor = MaterialTheme.colorScheme.primary

    // Like NavDrawer: when bar is active, show indicator on focus; when inactive, show on selected
    val showIndicator = if (isTopNavActive) isFocused else isSelected

    // Text only shows when focused (not when merely selected)
    val showText = isFocused

    // Animations
    val iconColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            showIndicator -> Color.White
            else -> Color.White.copy(alpha = 0.5f)
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "iconColor"
    )

    // Dynamic bubble width
    val bubbleWidth by animateDpAsState(
        targetValue = if (showText) 110.dp else 46.dp,
        animationSpec = SaabMotion.focusRetargetSpec(),
        label = "bubbleWidth"
    )

    // Text fade in/out
    val textAlpha by animateFloatAsState(
        targetValue = if (showText) 1f else 0f,
        animationSpec = SaabMotion.focusLabelSpec(),
        label = "textAlpha"
    )

    // Text slide in from right
    val textOffset by animateDpAsState(
        targetValue = if (showText) 0.dp else (-8).dp,
        animationSpec = SaabMotion.focusRetargetSpec(),
        label = "textOffset"
    )

    // Bubble background color
    val bubbleColor by animateColorAsState(
        targetValue = when {
            isFocused -> accentColor.copy(alpha = 0.20f)
            showIndicator -> accentColor.copy(alpha = 0.20f)
            else -> Color.Transparent
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "bubbleColor"
    )

    // Bubble height
    val bubbleHeight = 38.dp

    // Fixed layout width to prevent neighbor shifting
    val layoutWidth = 110.dp
    val destinationLabel = stringResource(destination.labelRes)

    // Get background color for solid layer
    val backgroundColor = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .width(layoutWidth)
            .height(56.dp),
        contentAlignment = Alignment.CenterStart // Align to start so expansion goes right
    ) {
        // LAYER 1: Solid background that expands with bubble (makes accent overlay appear opaque)
        Box(
            modifier = Modifier
                .width(bubbleWidth)
                .height(bubbleHeight)
                .background(
                    color = if (showIndicator) backgroundColor else Color.Transparent,
                    shape = RoundedCornerShape(19.dp)
                )
        )

        // LAYER 2: Accent-colored Surface on top
        Surface(
            onClick = onNavigate,
            modifier = modifier
                .width(bubbleWidth)
                .height(bubbleHeight)
                .onFocusChanged { isFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(19.dp)), // Pill shape
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = bubbleColor,
                focusedContainerColor = accentColor.copy(alpha = 0.20f),
                pressedContainerColor = accentColor.copy(alpha = 0.20f),
                contentColor = iconColor,
                focusedContentColor = Color.White
            )
        ) {
            // Icon + Text row
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 13.dp), // Fixed start padding to pin icon
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start // Start aligned
            ) {
                // Icon (static)
                Icon(
                    painter = painterResource(id = destination.iconRes),
                    contentDescription = destinationLabel,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )

                // Text with fade + slide animation
                if (showText) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = destinationLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = iconColor,
                        maxLines = 1,
                        modifier = Modifier
                            .graphicsLayer {
                                alpha = textAlpha
                                translationX = textOffset.toPx()
                            }
                    )
                }
            }
        }
    }
}

@Composable
fun TopNavProfileAvatar(
    profile: ProfileEntity?,
    isTopNavActive: Boolean = true,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val accentColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    
    val showIndicator = if (isTopNavActive) isFocused else false
    
    val avatarSource = profile?.let { ProfileAssets.getAvatarSource(it.avatarRef) }
    val displayName = profile?.name ?: "Profile"
    
    // Content color animation
    val contentColor by animateColorAsState(
        targetValue = if (showIndicator) Color.White else Color(0xFF8E9099),
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "contentColor"
    )
    
    // Border color animation
    val borderColor by animateColorAsState(
        targetValue = if (showIndicator) accentColor else Color.Transparent,
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "borderColor"
    )
    
    // Text only appears when profile item is focused
    val textAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = SaabMotion.focusLabelSpec(),
        label = "TextAlpha"
    )
    
    val textOffset by animateFloatAsState(
        targetValue = if (isFocused) 0f else -20f,
        animationSpec = SaabMotion.focusLabelSpec(),
        label = "TextOffset"
    )
    
    Box(
        modifier = Modifier
            .height(50.dp)
            .fillMaxWidth()
    ) {
        Surface(
            onClick = onNavigate,
            modifier = modifier
                .fillMaxSize()
                .onFocusChanged { isFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
                contentColor = contentColor,
                focusedContentColor = Color.White
            )
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                // Avatar circle with border on focus
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(2.dp, borderColor, CircleShape)
                        .background(Color(0xFF1A1A1A))
                ) {
                    if (avatarSource != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(avatarSource)
                                .size(100, 100)
                                .crossfade(true)
                                .build(),
                            contentDescription = stringResource(R.string.profile_avatar_content_description),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                
                // Profile name and Change Profile text
                Column(
                    verticalArrangement = Arrangement.spacedBy((-12).dp),
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .graphicsLayer {
                            alpha = textAlpha
                            translationX = textOffset
                        }
                ) {
                    Text(
                        text = displayName,
                        fontSize = 13.sp,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                    Text(
                        text = stringResource(R.string.nav_change_profile),
                        fontSize = 10.sp,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                }
            }
        }
    }
}
