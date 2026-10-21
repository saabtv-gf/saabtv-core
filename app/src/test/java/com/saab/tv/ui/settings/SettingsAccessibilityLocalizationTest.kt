package com.saab.tv.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import com.saab.tv.data.localization.AppLanguage
import com.saab.tv.ui.components.SaabMotion
import com.saab.tv.ui.components.ReorderIndicator
import com.saab.tv.ui.navigation.NavDestination
import com.saab.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SettingsAccessibilityLocalizationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingTogglePublishesLabeledSwitchStateAndRespondsToActivation() {
        var enabled by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SettingToggleRow(
                    label = "Diagnostics",
                    subtitle = "Record useful troubleshooting information",
                    isChecked = enabled,
                    onCheckedChange = { enabled = it }
                )
            }
        }

        val switch = compose.onNode(
            hasText("Diagnostics") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        )
        switch.assertIsOff()
        switch.performClick()
        compose.runOnIdle { assertTrue(enabled) }
        switch.assertIsOn()
    }

    @Test fun reorderIndicatorAnnouncesLocalizedDpadInstructions() {
        val instructions = RuntimeEnvironment.getApplication()
            .getString(R.string.dashboard_reorder_hint)
        compose.setContent {
            MaterialTheme { ReorderIndicator(instructions) }
        }

        val indicator = compose.onNodeWithText("▲▼").fetchSemanticsNode()
        assertEquals(instructions, indicator.config[SemanticsProperties.StateDescription])
    }

    @Test fun settingsSupportKannadaAndMalayalamSystemLocales() {
        val base = RuntimeEnvironment.getApplication()
        val malayalam = AppLanguage.localizedContext(base, "ml")
        assertNotEquals(base.getString(R.string.settings_title), malayalam.getString(R.string.settings_title))
        assertNotEquals(
            base.getString(R.string.integration_torbox_title),
            malayalam.getString(R.string.integration_torbox_title)
        )
        assertNotEquals(
            base.getString(R.string.sort_filter_exclude_phrases),
            malayalam.getString(R.string.sort_filter_exclude_phrases)
        )

        val kannada = AppLanguage.localizedContext(base, "kn")
        assertNotEquals(base.getString(R.string.account_title), kannada.getString(R.string.account_title))
        assertNotEquals(base.getString(R.string.diagnostics_basic_logging), kannada.getString(R.string.diagnostics_basic_logging))
        assertNotEquals(base.getString(R.string.app_language), kannada.getString(R.string.app_language))
        assertNotEquals(
            base.getString(R.string.integration_trakt_auth_expired),
            kannada.getString(R.string.integration_trakt_auth_expired)
        )
        assertNotEquals(
            base.getString(R.string.sort_filter_zero_seeders_description),
            kannada.getString(R.string.sort_filter_zero_seeders_description)
        )
    }

    @Test fun titleCardWatchedAndLibraryAnnouncementsAreLocalized() {
        val base = RuntimeEnvironment.getApplication()
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            val watched = localized.getString(R.string.title_badge_watched)
            val watchlisted = localized.getString(R.string.title_badge_watchlisted)
            assertNotEquals("Watched badge should be localized for $language", base.getString(R.string.title_badge_watched), watched)
            assertNotEquals("Library badge should be localized for $language", base.getString(R.string.title_badge_watchlisted), watchlisted)
            assertEquals(
                "$watched, $watchlisted",
                localized.getString(R.string.title_badge_combined_state, watched, watchlisted)
            )
        }
    }

    @Test fun navigationDestinationLabelsFollowKannadaAndMalayalamLocales() {
        val base = RuntimeEnvironment.getApplication()
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            NavDestination.values().filterNot { it == NavDestination.Ott }.forEach { destination ->
                assertNotEquals(
                    "${destination.name} label should be localized for $language",
                    base.getString(destination.labelRes),
                    localized.getString(destination.labelRes)
                )
            }
        }
    }

    @Test fun remoteAccountPairingInstructionsAndErrorsAreLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.account_remote_connecting,
            R.string.account_remote_ready,
            R.string.account_remote_unavailable,
            R.string.account_remote_received,
            R.string.account_remote_retrying,
            R.string.account_remote_closed,
            R.string.account_remote_continue_as,
            R.string.account_remote_confirmation_warning,
            R.string.account_remote_checking_username,
            R.string.account_remote_qr_description,
            R.string.account_remote_pairing_steps,
            R.string.account_remote_privacy_note,
            R.string.account_remote_expiry,
            R.string.account_remote_username_taken,
            R.string.account_remote_username_check_failed,
            R.string.account_remote_qr_expired
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                val arguments = if (resource == R.string.account_remote_continue_as) arrayOf("Sanju") else emptyArray()
                assertNotEquals(
                    "Remote pairing resource $resource should be localized for $language",
                    base.getString(resource, *arguments),
                    localized.getString(resource, *arguments)
                )
            }
        }
    }

    @Test fun profileOnboardingAndManagementCopyIsLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.common_back,
            R.string.common_continue,
            R.string.profile_setup_progress,
            R.string.profile_welcome_title,
            R.string.profile_welcome_description,
            R.string.profile_create_first,
            R.string.profile_selector_title,
            R.string.profile_unlock_title,
            R.string.profile_pin_enter_description,
            R.string.profile_pin_incorrect,
            R.string.profile_initial_setup_title,
            R.string.profile_initial_setup_description,
            R.string.profile_copy_settings,
            R.string.profile_no_profiles_to_copy,
            R.string.profile_start_from_scratch,
            R.string.profile_copy_dialog_title,
            R.string.profile_copy_dialog_description,
            R.string.profile_no_available_to_copy,
            R.string.profile_scratch_confirm_title,
            R.string.profile_scratch_confirm_description,
            R.string.profile_start_fresh,
            R.string.profile_edit_action,
            R.string.profile_set_pin_action,
            R.string.profile_edit_pin_action,
            R.string.profile_remove_pin_action,
            R.string.profile_delete_action,
            R.string.profile_pin_title_set,
            R.string.profile_pin_title_confirm,
            R.string.profile_pin_title_enter_current,
            R.string.profile_pin_title_enter_new,
            R.string.profile_pin_title_confirm_new,
            R.string.profile_pin_title_remove,
            R.string.profile_pin_current_description,
            R.string.profile_pin_same_description,
            R.string.profile_pin_choose_description,
            R.string.profile_pin_confirm,
            R.string.profile_pin_mismatch,
            R.string.profile_pin_exactly_four,
            R.string.profile_pin_clear,
            R.string.profile_delete_confirm_title,
            R.string.profile_delete_confirm_description,
            R.string.profile_yes,
            R.string.profile_no,
            R.string.profile_name_create_title,
            R.string.profile_name_edit_title,
            R.string.profile_name_description,
            R.string.profile_name_stage,
            R.string.profile_name_placeholder,
            R.string.profile_avatar_title,
            R.string.profile_avatar_description,
            R.string.profile_avatar_stage,
            R.string.profile_upload_photo,
            R.string.profile_upload_your_own,
            R.string.profile_theme_title,
            R.string.profile_theme_description,
            R.string.profile_theme_stage,
            R.string.profile_create_theme,
            R.string.profile_create_your_own,
            R.string.profile_preferences_title,
            R.string.profile_preferences_description,
            R.string.profile_preferences_stage,
            R.string.profile_manual_setup,
            R.string.profile_language_first,
            R.string.profile_language_second,
            R.string.profile_language_third,
            R.string.profile_language_title,
            R.string.profile_language_description,
            R.string.profile_language_stage,
            R.string.profile_language_choose_below,
            R.string.profile_language_up_next,
            R.string.profile_language_change,
            R.string.profile_create_action,
            R.string.profile_upload_title,
            R.string.profile_upload_description,
            R.string.profile_upload_qr_description,
            R.string.profile_upload_expiry,
            R.string.profile_setup_failed,
            R.string.profile_add
        )
        val namedResources = mapOf(
            R.string.profile_unlock_title to "profile_unlock_title",
            R.string.profile_initial_setup_title to "profile_initial_setup_title",
            R.string.profile_copy_dialog_description to "profile_copy_dialog_description",
            R.string.profile_scratch_confirm_description to "profile_scratch_confirm_description",
            R.string.profile_pin_current_description to "profile_pin_current_description",
            R.string.profile_pin_choose_description to "profile_pin_choose_description",
            R.string.profile_delete_confirm_description to "profile_delete_confirm_description",
            R.string.profile_language_title to "profile_language_title",
            R.string.profile_language_change to "profile_language_change"
        )

        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                val args: Array<out Any> = when (resource) {
                    R.string.profile_language_stage -> arrayOf(2)
                    in namedResources.keys -> arrayOf("Sample Profile")
                    else -> emptyArray()
                }
                assertNotEquals(
                    "Profile resource ${namedResources[resource] ?: resource} should be localized for $language",
                    base.getString(resource, *args),
                    localized.getString(resource, *args)
                )
            }
        }
    }

    @Test fun addonManagementAndProfileAccessibilityCopyIsLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.addons_title,
            R.string.addons_description,
            R.string.addons_install,
            R.string.addons_installed,
            R.string.addons_import_title,
            R.string.addons_import_summary,
            R.string.addons_import_select_all,
            R.string.addons_import_deselect_all,
            R.string.addons_import_action,
            R.string.addons_import_installed,
            R.string.common_selected,
            R.string.common_not_selected,
            R.string.addons_reorder_hint,
            R.string.addons_manage_title,
            R.string.addons_move,
            R.string.addons_uninstall,
            R.string.addons_uninstall_title,
            R.string.addons_confirm_remove,
            R.string.addons_name_placeholder,
            R.string.addons_configure_sync,
            R.string.addons_select_catalogs,
            R.string.addons_home_catalog,
            R.string.addons_movies_catalog,
            R.string.addons_series_catalog,
            R.string.profile_avatar_content_description
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                val arguments: Array<out Any> = when (resource) {
                    R.string.addons_confirm_remove -> arrayOf("Example Add-on")
                    R.string.addons_import_summary -> arrayOf(3, 1)
                    R.string.addons_import_action -> arrayOf(1)
                    else -> emptyArray()
                }
                assertNotEquals(
                    "Resource $resource should be localized for $language",
                    base.getString(resource, *arguments),
                    localized.getString(resource, *arguments)
                )
            }
        }
    }

    @Test fun myLibraryLabelsAndEmptyStatesAreLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.library_title,
            R.string.library_filter_all,
            R.string.library_filter_watchlist,
            R.string.library_filter_in_progress,
            R.string.library_filter_watched,
            R.string.library_media_all,
            R.string.library_media_movies,
            R.string.library_media_series,
            R.string.library_continue_watching,
            R.string.library_empty,
            R.string.library_empty_movies,
            R.string.library_empty_series,
            R.string.library_empty_titles
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                assertNotEquals(
                    "My Library resource $resource should be localized for $language",
                    base.getString(resource),
                    localized.getString(resource)
                )
            }
        }
    }

    @Test fun homeHubAndCastDiscoveryLabelsAreLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.ott_loading_title,
            R.string.ott_loading_description,
            R.string.ott_catalog_title,
            R.string.ott_refresh,
            R.string.hub_bulk_manage_images,
            R.string.hub_bulk_scan_description,
            R.string.hub_bulk_press_back_to_finish,
            R.string.cast_filmography
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                assertNotEquals(
                    "Home/Hub/Discovery resource $resource should be localized for $language",
                    base.getString(resource),
                    localized.getString(resource)
                )
            }
        }
    }

    @Test fun detailsActionsEpisodesAndSourcePickerCopyIsLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.details_more_episodes,
            R.string.details_more_like_this,
            R.string.details_cast_and_crew,
            R.string.details_crew,
            R.string.details_cast,
            R.string.details_no_episodes,
            R.string.source_select,
            R.string.source_loading,
            R.string.source_language,
            R.string.source_language_all,
            R.string.source_filter_all_addons,
            R.string.source_checking_remaining,
            R.string.source_no_streams,
            R.string.source_score,
            R.string.playback_status_playing,
            R.string.episode_season,
            R.string.episode_fallback_title,
            R.string.episode_show_description,
            R.string.episode_hide_description,
            R.string.episode_runtime,
            R.string.episode_watched,
            R.string.episode_mark_watched,
            R.string.details_action_episodes,
            R.string.details_action_trailer,
            R.string.details_action_clear_progress,
            R.string.details_action_watchlisted,
            R.string.details_action_add_watchlist,
            R.string.details_action_resume,
            R.string.details_action_play_movie,
            R.string.details_action_play_episode,
            R.string.details_action_resume_episode_numbered,
            R.string.details_action_play_next_episode,
            R.string.details_action_play_next_episode_numbered,
            R.string.details_clear_progress_title,
            R.string.details_clear_progress_message,
            R.string.details_clear_progress_action
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                val arguments: Array<out Any> = when (resource) {
                    R.string.source_score -> arrayOf("87")
                    R.string.episode_season,
                    R.string.episode_fallback_title,
                    R.string.episode_runtime -> arrayOf(5)
                    R.string.details_action_play_episode,
                    R.string.details_action_resume_episode_numbered,
                    R.string.details_action_play_next_episode_numbered -> arrayOf(2, 5)
                    else -> emptyArray()
                }
                assertNotEquals(
                    "Details resource $resource should be localized for $language",
                    base.getString(resource, *arguments),
                    localized.getString(resource, *arguments)
                )
            }
        }
    }

    @Test fun integrationSettingsCopyAndDynamicStatusesAreLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.integration_screen_description,
            R.string.integration_status_connected,
            R.string.integration_status_not_connected,
            R.string.integration_status_availability_enabled,
            R.string.integration_status_api_key_required,
            R.string.integration_status_setup_required,
            R.string.integration_status_device_language,
            R.string.integration_status_enabled_language,
            R.string.integration_status_disabled,
            R.string.integration_connect_action,
            R.string.integration_connecting,
            R.string.integration_email,
            R.string.integration_password,
            R.string.integration_stremio_connect_title,
            R.string.integration_stremio_connect_description,
            R.string.integration_stremio_account,
            R.string.integration_addons_title,
            R.string.integration_addons_description,
            R.string.integration_disconnect_account,
            R.string.integration_remove_connection,
            R.string.integration_disconnect_title,
            R.string.integration_disconnect_description,
            R.string.integration_disconnect_action,
            R.string.integration_tmdb_settings,
            R.string.integration_tmdb_description,
            R.string.integration_api_access,
            R.string.integration_tmdb_api_access_description,
            R.string.integration_tmdb_api_key,
            R.string.integration_tmdb_save_key,
            R.string.integration_api_key_configured,
            R.string.integration_change_key,
            R.string.integration_enable_tmdb,
            R.string.integration_fetch_tmdb_metadata,
            R.string.integration_metadata_language,
            R.string.integration_metadata_language_description,
            R.string.integration_trakt_description,
            R.string.integration_trakt_credentials_description,
            R.string.integration_trakt_client_id,
            R.string.integration_trakt_client_secret,
            R.string.integration_trakt_save_connect,
            R.string.integration_trakt_connect_description,
            R.string.integration_trakt_authorization_prompt,
            R.string.integration_trakt_waiting,
            R.string.integration_edit_credentials,
            R.string.integration_trakt_connected,
            R.string.integration_trakt_qr_code,
            R.string.integration_trakt_connected_success,
            R.string.integration_trakt_auth_expired,
            R.string.common_done
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                val arguments: Array<out Any> = when (resource) {
                    R.string.integration_status_enabled_language -> arrayOf("English")
                    R.string.integration_trakt_credentials_description -> arrayOf("app.trakt.tv/settings/apps")
                    else -> emptyArray()
                }
                assertNotEquals(
                    "Integration resource $resource should be localized for $language",
                    base.getString(resource, *arguments),
                    localized.getString(resource, *arguments)
                )
            }
        }
    }

    @Test fun hubManagementDialogActionsAreLocalizedInKannadaAndMalayalam() {
        val base = RuntimeEnvironment.getApplication()
        val translatedResources = listOf(
            R.string.common_close,
            R.string.common_remove,
            R.string.common_rename,
            R.string.common_save,
            R.string.common_delete,
            R.string.common_confirm,
            R.string.account_sync_not_yet,
            R.string.account_sync_checking_cloud,
            R.string.account_sync_restored_reopening,
            R.string.account_sync_in_progress,
            R.string.account_sync_synced,
            R.string.hub_manage_categories_title,
            R.string.hub_reordering_title,
            R.string.hub_save_changes,
            R.string.hub_no_categories,
            R.string.hub_manage_images,
            R.string.hub_move_item,
            R.string.hub_remove_from_hub_title,
            R.string.hub_confirm_remove_item,
            R.string.hub_remove_image_title,
            R.string.hub_confirm_remove_image,
            R.string.hub_editor_name,
            R.string.hub_editor_row_name,
            R.string.hub_editor_shape,
            R.string.hub_editor_category_count,
            R.string.hub_editor_add_category,
            R.string.dashboard_tab_home,
            R.string.dashboard_tab_movies,
            R.string.dashboard_tab_series,
            R.string.dashboard_manage_category_title,
            R.string.dashboard_move_category,
            R.string.dashboard_layout_settings,
            R.string.dashboard_hide_from_tab,
            R.string.dashboard_hidden_from_tab,
            R.string.dashboard_reorder_hint,
            R.string.dashboard_category_renamed,
            R.string.dashboard_category_name,
            R.string.dashboard_layout_updated,
            R.string.dashboard_enable_grid_view,
            R.string.dashboard_enable_infinite_looping,
            R.string.dashboard_items_before_view_more,
            R.string.dashboard_add_to_tab,
            R.string.dashboard_no_hidden_items,
            R.string.dashboard_added_to_tab,
            R.string.dashboard_hub_created,
            R.string.dashboard_manage_hub_row,
            R.string.dashboard_hub_category_shape,
            R.string.dashboard_move_hub_row,
            R.string.dashboard_edit_hub_row,
            R.string.dashboard_delete_hub_row,
            R.string.dashboard_delete_hub_row_confirm_title,
            R.string.dashboard_hub_deleted,
            R.string.dashboard_confirm_delete_hub_row,
            R.string.dashboard_shape_landscape,
            R.string.dashboard_shape_portrait,
            R.string.dashboard_shape_square,
            R.string.dashboard_editor_title,
            R.string.dashboard_editor_description,
            R.string.dashboard_add,
            R.string.dashboard_new_hub,
            R.string.dashboard_hero_carousel_category,
            R.string.dashboard_hero_carousel_unknown,
            R.string.dashboard_hero_carousel_state_disabled,
            R.string.dashboard_posters_count,
            R.string.dashboard_tap_to_configure,
            R.string.dashboard_switch_to_simple_to_configure,
            R.string.dashboard_layout_simple,
            R.string.dashboard_layout_cinematic,
            R.string.dashboard_reorder_banner,
            R.string.dashboard_tab_rows,
            R.string.dashboard_no_rows_on_tab,
            R.string.dashboard_add_row,
            R.string.dashboard_reorder_complete,
            R.string.dashboard_reordering,
            R.string.dashboard_hub_row_default_name,
            R.string.dashboard_hub_row_saved,
            R.string.dashboard_add_category_to_hub,
            R.string.dashboard_create_hub_row_title,
            R.string.dashboard_select_categories_title,
            R.string.dashboard_all_categories_in_hub,
            R.string.dashboard_hero_carousel_title,
            R.string.dashboard_disable,
            R.string.dashboard_hero_carousel_updated,
            R.string.dashboard_unknown,
            R.string.dashboard_none,
            R.string.dashboard_category,
            R.string.dashboard_off,
            R.string.dashboard_seconds_count,
            R.string.dashboard_auto_scroll,
            R.string.dashboard_disable_hero_confirm_title,
            R.string.dashboard_disable_hero_confirm_message,
            R.string.dashboard_hero_carousel_disabled_toast,
            R.string.dashboard_select_category,
            R.string.dashboard_manage,
            R.string.dashboard_hub,
            R.string.dashboard_has_custom_image,
            R.string.dashboard_manage_chevron
        )

        fun formatArguments(resource: Int): Array<out Any> = when (resource) {
            R.string.hub_editor_category_count -> arrayOf(3)
            R.string.hub_confirm_remove_item,
            R.string.hub_confirm_remove_image,
            R.string.dashboard_hide_from_tab,
            R.string.dashboard_hidden_from_tab,
            R.string.dashboard_add_to_tab,
            R.string.dashboard_added_to_tab -> arrayOf("Home")
            R.string.dashboard_hero_carousel_category -> arrayOf("Action", "Movie")
            R.string.dashboard_posters_count -> arrayOf(10)
            R.string.dashboard_tab_rows,
            R.string.dashboard_no_rows_on_tab,
            R.string.dashboard_disable_hero_confirm_message -> arrayOf("Home")
            R.string.dashboard_seconds_count -> arrayOf(10)
            R.string.dashboard_auto_scroll -> arrayOf("10 sec")
            R.string.dashboard_items_before_view_more -> arrayOf(10)
            R.string.dashboard_hub_category_shape -> arrayOf(3, "Landscape")
            R.string.dashboard_confirm_delete_hub_row -> arrayOf("Sample hub")
            else -> emptyArray()
        }

        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            translatedResources.forEach { resource ->
                val args = formatArguments(resource)
                val english = base.getString(resource, *args)
                val translation = localized.getString(resource, *args)
                assertNotEquals("Resource $resource should be localized for $language", english, translation)
            }
        }
    }

    @Test fun chosenAppLanguagePersistsAndChangesTheLocalizedContext() {
        val context = RuntimeEnvironment.getApplication()
        val previous = AppLanguage.currentTag(context)
        try {
            AppLanguage.setLanguage(context, "ml")
            assertEquals("ml", AppLanguage.currentTag(context))
            assertNotEquals(
                context.getString(R.string.settings_title),
                AppLanguage.localizedContext(context).getString(R.string.settings_title)
            )
        } finally {
            AppLanguage.setLanguage(context, previous)
        }
    }

    @Test fun themeScreenAndEditorCopyAreLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.common_edit,
            R.string.common_yes,
            R.string.common_no,
            R.string.theme_screen_title,
            R.string.theme_screen_description,
            R.string.theme_tab_all,
            R.string.theme_tab_dark,
            R.string.theme_tab_colorful,
            R.string.theme_tab_custom,
            R.string.theme_manage_title,
            R.string.theme_edit_title,
            R.string.theme_create_title,
            R.string.theme_colors,
            R.string.theme_primary,
            R.string.theme_background,
            R.string.theme_hue,
            R.string.theme_saturation,
            R.string.theme_lightness,
            R.string.theme_default_name,
            R.string.theme_name,
            R.string.theme_adjust_slot,
            R.string.theme_delete_confirm_title,
            R.string.theme_delete_confirm_message,
            R.string.theme_create_custom
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                val args: Array<out Any> = when (resource) {
                    R.string.theme_adjust_slot -> arrayOf("Primary")
                    R.string.theme_delete_confirm_message -> arrayOf("Cinema")
                    else -> emptyArray()
                }
                assertNotEquals(
                    "Theme resource $resource should be localized for $language",
                    base.getString(resource, *args),
                    localized.getString(resource, *args)
                )
            }
        }
    }

    @Test fun subtitleAppearanceControlsAreLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.playback_subtitle_style,
            R.string.playback_subtitle_preview,
            R.string.playback_subtitle_size,
            R.string.playback_subtitle_offset,
            R.string.playback_subtitle_text_color,
            R.string.playback_subtitle_background,
            R.string.subtitle_color_white,
            R.string.subtitle_color_gray,
            R.string.subtitle_color_yellow,
            R.string.subtitle_color_cyan,
            R.string.subtitle_color_red,
            R.string.subtitle_color_orange,
            R.string.subtitle_color_green,
            R.string.subtitle_background_none,
            R.string.subtitle_background_black,
            R.string.subtitle_background_semi,
            R.string.subtitle_background_dark
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                val arguments: Array<out Any> = when (resource) {
                    R.string.playback_subtitle_size -> arrayOf(125)
                    R.string.playback_subtitle_offset -> arrayOf(-5)
                    else -> emptyArray()
                }
                assertNotEquals(
                    "Subtitle appearance resource $resource should be localized for $language",
                    base.getString(resource, *arguments),
                    localized.getString(resource, *arguments)
                )
            }
        }
    }

    @Test fun playbackControlsAndAccessibilityActionsAreLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.common_none,
            R.string.episode_accessibility_label,
            R.string.title_type_movie,
            R.string.title_type_series,
            R.string.title_type_episode,
            R.string.title_runtime_hours_minutes,
            R.string.title_runtime_hours,
            R.string.title_runtime_minutes,
            R.string.player_audio_tracks_title,
            R.string.player_subtitles_title,
            R.string.player_play_next_episode,
            R.string.player_play,
            R.string.player_pause,
            R.string.player_seek_progress,
            R.string.player_seek_position,
            R.string.player_scene_preview_at,
            R.string.player_subtitles_control,
            R.string.player_audio_tracks_control,
            R.string.player_next_episode_control,
            R.string.player_episodes_control,
            R.string.player_more_sources_control,
            R.string.player_switch_source,
            R.string.player_tracks,
            R.string.player_no_audio_tracks,
            R.string.player_no_subtitles,
            R.string.player_subtitle_track,
            R.string.player_subtitle_embedded,
            R.string.player_subtitle_stream,
            R.string.player_subtitle_addon,
            R.string.player_subtitle_unsupported,
            R.string.player_subtitles_off,
            R.string.player_subtitle_offset,
            R.string.player_subtitle_size,
            R.string.player_subtitle_delay,
            R.string.player_subtitle_color,
            R.string.player_offset_label,
            R.string.player_size_label,
            R.string.player_delay_label,
            R.string.player_color_label,
            R.string.player_subtitle_text_short,
            R.string.player_subtitle_background_short,
            R.string.player_subtitle_text_color_option,
            R.string.player_subtitle_background_color_option,
            R.string.player_decrease_control,
            R.string.player_increase_control,
            R.string.player_close_control,
            R.string.player_ends_at,
            R.string.player_thumbnail_cache
        )
        fun args(resource: Int): Array<out Any> = when (resource) {
            R.string.episode_accessibility_label -> arrayOf(2, 5)
            R.string.title_runtime_hours_minutes -> arrayOf(2, 5)
            R.string.title_runtime_hours -> arrayOf(2)
            R.string.title_runtime_minutes -> arrayOf(5)
            R.string.player_decrease_control,
            R.string.player_increase_control,
            R.string.player_close_control -> arrayOf("offset")
            R.string.player_ends_at -> arrayOf("9:30 PM")
            R.string.player_subtitle_text_color_option,
            R.string.player_subtitle_background_color_option -> arrayOf("Cyan")
            R.string.player_thumbnail_cache -> arrayOf(72, " (18/25)")
            R.string.player_seek_position -> arrayOf("5:00", "10:00")
            R.string.player_scene_preview_at -> arrayOf("1:30")
            else -> emptyArray()
        }

        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                assertNotEquals(
                    "Player resource $resource should be localized for $language",
                    base.getString(resource, *args(resource)),
                    localized.getString(resource, *args(resource))
                )
            }
        }
    }

    @Test fun localizedLabeledValueTemplateKeepsBothTranslatedArguments() {
        val base = RuntimeEnvironment.getApplication()
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            val sortLabel = localized.getString(R.string.catalog_grid_sort_label)
            val newest = localized.getString(R.string.catalog_grid_sort_newest)
            assertEquals(
                "$sortLabel: $newest",
                localized.getString(R.string.common_labeled_value, sortLabel, newest)
            )
        }
    }

    @Test fun foregroundTorrentNotificationCopyIsLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.torrent_notification_channel,
            R.string.torrent_notification_title,
            R.string.torrent_notification_starting
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                assertNotEquals(
                    "Notification resource $resource should be localized for $language",
                    base.getString(resource),
                    localized.getString(resource)
                )
            }
        }
    }

    @Test fun sourceSortingAndFilterControlsAreLocalized() {
        val base = RuntimeEnvironment.getApplication()
        val resources = listOf(
            R.string.common_selected,
            R.string.sort_filter_title,
            R.string.sort_filter_description,
            R.string.sort_filter_sort_sources,
            R.string.sort_filter_ranking,
            R.string.sort_filter_sort_by,
            R.string.sort_filter_ranking_description,
            R.string.sort_filter_best_stream,
            R.string.sort_filter_quality,
            R.string.sort_filter_file_size,
            R.string.sort_filter_seeders,
            R.string.sort_filter_allowed_qualities,
            R.string.sort_filter_allowed_qualities_description,
            R.string.sort_filter_unknown,
            R.string.sort_filter_size_seasons_seeders,
            R.string.sort_filter_no_limit,
            R.string.sort_filter_max_file_size,
            R.string.sort_filter_season_packs,
            R.string.sort_filter_hide_zero_seeders,
            R.string.sort_filter_excluded_formats,
            R.string.sort_filter_excluded_formats_description,
            R.string.sort_filter_exclude_phrases,
            R.string.sort_filter_none
        )
        listOf("kn", "ml").forEach { language ->
            val localized = AppLanguage.localizedContext(base, language)
            resources.forEach { resource ->
                assertNotEquals(
                    "Source sorting resource $resource should be localized for $language",
                    base.getString(resource),
                    localized.getString(resource)
                )
            }
        }
    }

    @Test fun reducedMotionPreferenceDisablesSharedMotionDurations() {
        val context = RuntimeEnvironment.getApplication()
        val previous = SaabMotion.isReducedMotion
        try {
            SaabMotion.setReducedMotion(context, true)
            assertTrue(SaabMotion.isReducedMotion)
            assertEquals(0, SaabMotion.duration(SaabMotion.NAVIGATION_REVEAL_MS))
        } finally {
            SaabMotion.setReducedMotion(context, previous)
        }
    }
}
