package com.saab.tv.ui.theme

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.testing.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ThemeManagerRegressionTest {
    private lateinit var app: OfflineAppFixture
    private lateinit var vm: ThemeManager
    @Before fun setup() {
        app = OfflineAppFixture(RuntimeEnvironment.getApplication())
        vm = ThemeManager(app.dao)
        awaitAppState { runBlocking { app.dao.getThemeById(DefaultThemes.VOID.id) != null } }
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); app.close() }
    @Test fun seedsBuiltInsWithoutDuplicateAvailableEntries() {
        awaitAppState { vm.availableThemes.value.size == DefaultThemes.ALL.size }
        assertEquals(DefaultThemes.ALL.map { it.id }.toSet(), vm.availableThemes.value.map { it.id }.toSet())
    }
    @Test fun createsCustomThemeAndDerivesSurfaceColorPreservingAlpha() {
        val id = vm.createCustomTheme("Night", 0xFF0088FF, 0x80204060)
        awaitAppState { vm.availableThemes.value.any { it.id == id } }
        val theme = vm.availableThemes.value.single { it.id == id }
        assertEquals("Night", theme.name); assertFalse(theme.isBuiltIn)
        assertEquals(0x801C3956L, theme.surfaceColor)
    }
    @Test fun selectsThemeOnlyForRequestedProfile() = runBlocking {
        app.dao.insertProfile(ProfileEntity(id = 1, name = "One"))
        app.dao.insertProfile(ProfileEntity(id = 2, name = "Two"))
        val chosen = DefaultThemes.ALL.last()
        vm.selectTheme(1, chosen.id)
        awaitAppState { runBlocking { app.dao.getProfileById(1)?.themeId == chosen.id } }
        assertEquals(ProfileEntity(id = 2, name = "Two"), app.dao.getProfileById(2))
    }
    @Test fun activeProfileThemeChangesImmediatelyAfterPersistence() {
        runBlocking { app.dao.insertProfile(ProfileEntity(id = 1, name = "One")) }
        val chosen = DefaultThemes.ALL.last()
        vm.setCurrentProfile(1, DefaultThemes.VOID.id)
        awaitAppState { vm.currentTheme.value.id == DefaultThemes.VOID.id }
        vm.selectTheme(1, chosen.id)
        awaitAppState {
            vm.currentTheme.value.id == chosen.id &&
                runBlocking { app.dao.getProfileById(1)?.themeId == chosen.id }
        }
    }
    @Test fun unknownThemeFallsBackAndResetReturnsToDefault() {
        vm.setCurrentProfile(1, "missing")
        awaitAppState { vm.currentTheme.value == DefaultThemes.VOID }
        vm.resetTheme(); assertEquals(DefaultThemes.VOID, vm.currentTheme.value)
    }
    @Test fun editsActiveCustomThemeAndDeletesItFromAvailableThemes() {
        val id = vm.createCustomTheme("Original", 0xFF112233, 0xFF000000)
        awaitAppState { vm.availableThemes.value.any { it.id == id } }
        vm.setCurrentProfile(1, id)
        awaitAppState { vm.currentTheme.value.id == id }
        val updated = vm.currentTheme.value.copy(name = "Edited", primaryColor = 0xFF998877)
        vm.updateCustomTheme(updated)
        awaitAppState { vm.currentTheme.value == updated && vm.availableThemes.value.contains(updated) }
        vm.deleteCustomTheme(id)
        awaitAppState { vm.availableThemes.value.none { it.id == id } }
    }
    @Test fun builtInThemesCannotBeEditedOrDeleted() = runBlocking {
        vm.updateCustomTheme(DefaultThemes.VOID.copy(name = "Wrong"))
        vm.deleteCustomTheme(DefaultThemes.VOID.id)
        // Queue a later IO operation and await its persistence before asserting the guard.
        val barrier = vm.createCustomTheme("Barrier", 0L, 0L)
        awaitAppState { vm.availableThemes.value.any { it.id == barrier } }
        assertEquals(DefaultThemes.VOID, app.dao.getThemeById(DefaultThemes.VOID.id))
    }
}
