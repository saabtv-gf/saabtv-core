package com.saab.tv.ui.profiles

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.ProfileEntity
import com.saab.tv.data.profile.ProfilePin
import com.saab.tv.testing.FeatureFixture
import com.saab.tv.testing.awaitAppState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileViewModelScenarioTest {
    @get:Rule val testName = TestName()
    private lateinit var fixture: FeatureFixture
    private lateinit var viewModel: ProfileViewModel

    @Before fun setUp() {
        fixture = FeatureFixture(RuntimeEnvironment.getApplication())
        viewModel = ProfileViewModel(fixture.dao, fixture.app.configuration, fixture.traktAuth, fixture.cache)
        awaitAppState { !viewModel.isLoading.value }
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        fixture.close()
    }

    @Test fun manualWizardPersistsOrderedLanguagesAndInitializesTheNewProfile() {
        viewModel.startWizard()
        viewModel.setWizardName("Family Room")
        viewModel.setWizardAvatar("avatar_4")
        viewModel.setWizardTheme("void")
        assertEquals(6, viewModel.wizardStep.value)

        viewModel.setWizardLanguage(0, "ml")
        viewModel.setWizardLanguage(1, "kn")
        viewModel.setWizardLanguage(2, "en")

        awaitAppState {
            viewModel.wizardStep.value == 0 && !viewModel.isInitializingProfile.value &&
                runBlocking { fixture.dao.getProfiles().first().any { it.name == "Family Room" } }
        }
        val created = runBlocking { fixture.dao.getProfiles().first().single { it.name == "Family Room" } }
        assertEquals("avatar_4", created.avatarRef)
        assertEquals("void", created.themeId)
        assertEquals(listOf("ml", "kn", "en"), listOf(
            created.sourceLanguagePriority1,
            created.sourceLanguagePriority2,
            created.sourceLanguagePriority3
        ))
        assertEquals(0, viewModel.wizardStep.value)
        assertFalse(viewModel.isInitializingProfile.value)
    }

    @Test fun wizardRejectsOutOfOrderAndDuplicateLanguageSelectionsWithoutLosingState() {
        viewModel.startWizard()
        viewModel.setWizardLanguage(0, "ml") // Wrong step: wizard is still at step one.
        assertEquals(listOf("en", "te", "hi"), viewModel.tempLanguages)
        assertEquals(1, viewModel.wizardStep.value)

        viewModel.setWizardName("Languages")
        viewModel.setWizardAvatar("avatar_1")
        viewModel.setWizardTheme("void")
        viewModel.setWizardLanguage(0, "ml")
        assertEquals(7, viewModel.wizardStep.value)
        viewModel.setWizardLanguage(1, "ml")
        assertEquals(7, viewModel.wizardStep.value)
        assertEquals(listOf("ml", "te", "hi"), viewModel.tempLanguages)

        viewModel.goBackStep()
        assertEquals(6, viewModel.wizardStep.value)
        viewModel.goBackStep()
        assertEquals(3, viewModel.wizardStep.value)
        viewModel.cancelWizard()
        assertEquals(0, viewModel.wizardStep.value)
    }

    @Test fun editingProfileUpdatesIdentityButRetainsPlaybackAndLanguageSettings() {
        val insertedId = runBlocking {
            fixture.dao.insertProfile(ProfileEntity(
                name = "Old Name",
                avatarRef = "avatar_2",
                themeId = "midnight",
                seekTimeIntervalSeconds = 20,
                sourceLanguagePriority1 = "kn"
            )).toInt()
        }
        val original = runBlocking { fixture.dao.getProfileById(insertedId)!! }
        awaitAppState { viewModel.profiles.value.any { it.id == insertedId } }

        viewModel.startEditWizard(original)
        viewModel.setWizardName("Renamed")
        viewModel.setWizardAvatar("avatar_8")
        viewModel.setWizardTheme("ocean")

        awaitAppState { runBlocking { fixture.dao.getProfileById(insertedId)?.name == "Renamed" } }
        val updated = runBlocking { fixture.dao.getProfileById(insertedId)!! }
        assertEquals("avatar_8", updated.avatarRef)
        assertEquals("ocean", updated.themeId)
        assertEquals(20, updated.seekTimeIntervalSeconds)
        assertEquals("kn", updated.sourceLanguagePriority1)
    }

    @Test fun invalidPinIsIgnoredValidPinCanBeVerifiedAndRemoved() {
        val profileId = runBlocking { fixture.dao.insertProfile(ProfileEntity(name = testName.methodName)).toInt() }
        viewModel.setProfilePin(profileId, "12")
        awaitAppState { runBlocking { fixture.dao.getProfileById(profileId)?.pinHash == null } }

        viewModel.setProfilePin(profileId, "4826")
        awaitAppState { runBlocking { fixture.dao.getProfileById(profileId)?.pinHash != null } }
        val withPin = runBlocking { fixture.dao.getProfileById(profileId)!! }
        assertTrue(viewModel.verifyPin(withPin, "4826"))
        assertFalse(viewModel.verifyPin(withPin, "0000"))

        viewModel.removeProfilePin(profileId)
        awaitAppState { runBlocking { fixture.dao.getProfileById(profileId)?.pinHash == null } }
        assertNull(runBlocking { fixture.dao.getProfileById(profileId)?.pinHash })
    }

    @Test fun repeatedWrongPinAttemptsAreRateLimited() {
        val profile = ProfileEntity(name = "Locked", pinHash = ProfilePin.hash(4, "7391"), id = 4)
        repeat(5) { assertFalse(viewModel.verifyPin(profile, "0000")) }
        assertFalse(viewModel.verifyPin(profile, "7391"))
    }
}
