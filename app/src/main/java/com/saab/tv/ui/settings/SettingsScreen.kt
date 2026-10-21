package com.saab.tv.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.saab.tv.R
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.ui.components.SaabMotion
import com.saab.tv.ui.components.scrollToItemUsingSaabMotion
import com.saab.tv.ui.navigation.LocalNavigationFocusHandoff
import com.saab.tv.ui.navigation.isBackOrEscape
import com.saab.tv.ui.navigation.handoffNavigationFocus
import com.saab.tv.ui.navigation.isInitialBackPress
import com.saab.tv.ui.navigation.isInitialKeyPress
import com.saab.tv.ui.addons.AddonsScreen
import com.saab.tv.ui.theme.ThemeManager
import com.saab.tv.ui.utils.requestFocusOrFallback
import com.saab.tv.ui.utils.requestFocusSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

import androidx.compose.ui.ExperimentalComposeUiApi

enum class SettingsSection(@StringRes val labelRes: Int, @DrawableRes val iconRes: Int) {
    Account(R.string.settings_section_account, R.drawable.info_icon),
    Personalization(R.string.settings_section_personalization, R.drawable.personalization_icon),
    Theme(R.string.settings_section_theme, R.drawable.palette_icon),
    Dashboard(R.string.settings_section_home_screen, R.drawable.home_icon),
    Playback(R.string.settings_section_playback, R.drawable.playback_icon),
    SourcePreferences(R.string.settings_section_sort_filter, R.drawable.source_preferences_icon),
    Addons(R.string.settings_section_addons, R.drawable.puzzle_icon),
    Integrations(R.string.settings_section_integrations, R.drawable.integrations_icon),
    PlaybackDiagnostics(R.string.settings_section_diagnostics, R.drawable.info_icon),
    About(R.string.settings_section_about, R.drawable.info_icon)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    currentProfile: ProfileEntity?,
    onBack: () -> Unit,
    entryRequester: FocusRequester,
    drawerRequester: FocusRequester,
    onDashboardChanged: () -> Unit = {},
    onScreenFocusChanged: (Boolean) -> Unit = {},
    onContentFocusChanged: (Boolean) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val authorizeNavigationHandoff = LocalNavigationFocusHandoff.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedSection by remember { mutableStateOf(SettingsSection.Personalization) }
    var displayedSection by remember { mutableStateOf(SettingsSection.Personalization) }
    var isContentFocused by remember { mutableStateOf(false) }

    val sidebarListState = rememberLazyListState()
    val contentPaneRequester = remember { FocusRequester() }
    val itemRequesters = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    fun restoreSelectedSectionFocus() {
        itemRequesters[selectedSection].requestFocusOrFallback(entryRequester)
    }

    fun returnToNavigation() {
        val handedOff = authorizeNavigationHandoff?.invoke(null) == true
        val drawerFocused = drawerRequester.requestFocusSafely()
        if (!handedOff && !drawerFocused) onBack()
    }

    val scope = rememberCoroutineScope()

    // DEBOUNCE LOGIC (Only for Scrolling)
    LaunchedEffect(selectedSection) {
        com.saab.tv.AppDiagnostics.event(context, "Navigation", "Settings Section", "section=${selectedSection.name}")
        delay(300)
        displayedSection = selectedSection
    }

    val isTopNav = currentProfile?.navPosition == "top"

    // SCREEN FOCUS TRACKING (Content + Sidebar)
    var isScreenFocused by remember { mutableStateOf(false) }

    // BACK LOGIC
    // Enabled when:
    // 1. Side Nav (Always)
    // 2. Top Nav AND Screen is Focused (Handle = Open Nav/Go Back)
    // Disabled when Top Nav AND Screen NOT Focused (Nav is focused) -> Let Nav handle Close.
    val dispatchBackFromKey = com.saab.tv.ui.navigation.rememberAppBackKeyAction {
        returnToNavigation()
    }
    com.saab.tv.ui.navigation.AppBackHandler(
        enabled = isScreenFocused,
        priority = com.saab.tv.ui.navigation.AppBackPriority.Screen
    ) {
        if (isContentFocused) {
            restoreSelectedSectionFocus()
        } else {
            returnToNavigation()
        }
    }
    
    // Animated padding values for smooth transition when nav mode changes
    val rowStartPadding by animateDpAsState(
        targetValue = if (isTopNav) 0.dp else 80.dp,
        animationSpec = SaabMotion.navigationRevealSpec(),
        label = "rowStartPadding"
    )
    val colTopPadding by animateDpAsState(
        targetValue = if (isTopNav) 70.dp else 40.dp,
        animationSpec = SaabMotion.navigationRevealSpec(),
        label = "colTopPadding"
    )
    val contentTopPadding by animateDpAsState(
        targetValue = if (isTopNav) 70.dp else 40.dp,
        animationSpec = SaabMotion.navigationRevealSpec(),
        label = "contentTopPadding"
    )
    val colStartPadding by animateDpAsState(
        targetValue = if (isTopNav) 50.dp else 32.dp,
        animationSpec = SaabMotion.navigationRevealSpec(),
        label = "colStartPadding"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { isScreenFocused = it.hasFocus; onScreenFocusChanged(it.hasFocus) }
            .focusGroup()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = rowStartPadding)
        ) {

        // --- LEFT SIDEBAR ---
        Column(
            modifier = Modifier
                .weight(0.28f)
                .fillMaxHeight()
                .padding(top = colTopPadding, start = colStartPadding, end = 16.dp)
                .onFocusChanged { if (it.hasFocus) { isContentFocused = false; onContentFocusChanged(false) } }
                // Selected-section FocusRequesters below restore this list explicitly;
                // avoid focusRestorer's duplicate lazy-item pin release on section handoffs.
                .focusGroup()
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp
                ),
                color = Color.White,
                modifier = Modifier.padding(bottom = 32.dp, start = 16.dp)
            )

            LazyColumn(
                state = sidebarListState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 0.dp)
            ) {
                itemsIndexed(
                    items = SettingsSection.entries,
                    key = { _, section -> section.name }
                ) { index, section ->
                        val isSelected = selectedSection == section

                        // Move into the section immediately. The right-hand pane is
                        // a focus group, so request focus after AnimatedContent has
                        // installed the destination's first focusable control.
                        fun enterContent() {
                            selectedSection = section
                            displayedSection = section
                            scope.launch {
                                delay(SaabMotion.duration(SaabMotion.NAVIGATION_REVEAL_MS).toLong())
                                repeat(12) {
                                    if (isContentFocused) return@launch
                                    withFrameNanos { }
                                    runCatching { contentPaneRequester.requestFocus() }
                                    withFrameNanos { }
                                }
                            }
                        }

                        val isFirstSection = index == 0

                        val focusModifier = Modifier
                            .focusRequester(itemRequesters[section]!!)
                            .then(if (isSelected) Modifier.focusRequester(entryRequester) else Modifier)
                            .onPreviewKeyEvent {
                                if (it.isBackOrEscape()) {
                                    if (it.isInitialBackPress()) dispatchBackFromKey()
                                    true
                                } else if (it.type == KeyEventType.KeyDown) {
                                    when (it.key) {
                                        Key.DirectionLeft -> {
                                            when {
                                                isTopNav -> false
                                                else -> handoffNavigationFocus(it, authorizeNavigationHandoff, drawerRequester)
                                            }
                                        }
                                        Key.DirectionUp -> {
                                            // Up on first section -> go to drawer/topnav (ONLY in top nav mode)
                                            if (isFirstSection && isTopNav) {
                                                handoffNavigationFocus(it, authorizeNavigationHandoff, drawerRequester)
                                            } else false
                                        }
                                        Key.DirectionRight, Key.DirectionCenter, Key.Enter -> {
                                            if (it.isInitialKeyPress()) enterContent()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }

                        SettingsSidebarItem(
                            label = stringResource(section.labelRes),
                            iconRes = section.iconRes,
                            isSelected = isSelected,
                            modifier = focusModifier,
                            onClick = { enterContent() },
                            onFocus = {
                                selectedSection = section
                                scope.launch {
                                    runCatching { sidebarListState.scrollToItemUsingSaabMotion(index) }
                                }
                            }
                        )
                }
            }
        }

        // --- RIGHT CONTENT ---
        Box(
            modifier = Modifier
                .weight(0.72f) // Expanded content area
                .fillMaxHeight()
                .focusRequester(contentPaneRequester)
                .focusGroup()
                .onFocusChanged {
                    isContentFocused = it.hasFocus
                    onContentFocusChanged(it.hasFocus)
                }
        ) {
            // INCREASED PADDING: Gutter 64dp (Sidebar ends at weight 0.28, this starts at 0 without extra padding logic, so we add start padding here)
            Column(modifier = Modifier.fillMaxSize().padding(top = contentTopPadding, start = 64.dp, end = 80.dp)) {
                AnimatedContent(
                    targetState = displayedSection,
                    transitionSpec = {
                        fadeIn(SaabMotion.navigationRevealSpec()) togetherWith
                            fadeOut(SaabMotion.navigationRevealSpec())
                    },
                    label = "SettingsContent"
                ) { target ->
                    when (target) {
                        SettingsSection.Account -> com.saab.tv.ui.account.AccountSettingsScreen(
                            onBack = { restoreSelectedSectionFocus() }
                        )
                        SettingsSection.Personalization -> {
                            PersonalizationSettings(
                                currentProfile = currentProfile,
                                viewModel = viewModel,
                                onGoBack = { restoreSelectedSectionFocus() }
                            )
                        }
                        SettingsSection.Theme -> {
                            ThemeSettings(
                                currentProfile = currentProfile,
                                onGoBack = { restoreSelectedSectionFocus() },
                                isTopNav = isTopNav
                            )
                        }
                        SettingsSection.Dashboard -> {
                            DashboardEditorScreen(
                                onBack = {
                                    onDashboardChanged() // Invalidate home screen cache
                                    restoreSelectedSectionFocus()
                                },
                                isTopNav = isTopNav,
                                currentProfile = currentProfile
                            )
                        }
                        SettingsSection.Playback -> {
                            PlaybackSettings(
                                currentProfile = currentProfile,
                                viewModel = viewModel,
                                onGoBack = { restoreSelectedSectionFocus() }
                            )
                        }
                        SettingsSection.SourcePreferences -> {
                            SourcePreferencesSettings(
                                currentProfile = currentProfile,
                                viewModel = viewModel,
                                onGoBack = { restoreSelectedSectionFocus() }
                            )
                        }
                        SettingsSection.Addons -> {
                            AddonsScreen(
                                onBack = { restoreSelectedSectionFocus() },
                                isTopNav = isTopNav
                            )
                        }
                        SettingsSection.Integrations -> {
                            IntegrationsScreen(
                                onBack = { restoreSelectedSectionFocus() }
                            )
                        }
                        SettingsSection.PlaybackDiagnostics -> {
                            PlaybackDiagnosticsSettings(
                                onGoBack = { restoreSelectedSectionFocus() }
                            )
                        }
                        SettingsSection.About -> {
                            AboutSettings(
                                onGoBack = { restoreSelectedSectionFocus() }
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun SettingsSidebarItem(
    label: String,
    @DrawableRes iconRes: Int,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocus: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) onFocus()
    }

    // Visual logic (like VoidTabBtn):
    // - If focused: full highlight (white text)
    // - If selected but not focused: partial highlight (white text)
    val showHighlight = isFocused || isSelected
    
    val contentColor by animateColorAsState(
        when {
            isFocused -> MaterialTheme.colorScheme.onPrimary
            isSelected -> Color.White
            else -> Color.White.copy(0.52f)
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "Content"
    )

    val backgroundColor by animateColorAsState(
        when {
            isFocused -> MaterialTheme.colorScheme.primary
            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
            else -> Color.Transparent
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "SettingsItemBackground"
    )

    val borderColor by animateColorAsState(
        when {
            isFocused -> Color.White
            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
            else -> Color.Transparent
        },
        animationSpec = SaabMotion.focusFeedbackSpec(),
        label = "SettingsItemBorder"
    )
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(if (isFocused) 2.dp else if (isSelected) 1.dp else 0.dp, borderColor, RoundedCornerShape(10.dp))
            .semantics {
                role = Role.Tab
                selected = isSelected
            }
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes), 
            contentDescription = null, 
            tint = contentColor, 
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label, 
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                fontWeight = if (showHighlight) FontWeight.SemiBold else FontWeight.Normal
            ), 
            color = contentColor
        )
    }
}
