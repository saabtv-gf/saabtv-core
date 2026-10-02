package com.saab.tv.ui.profiles

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.model.*
import com.saab.tv.data.profile.ProfilePin
import com.saab.tv.testing.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileWizardP1Test {
    private lateinit var f: FeatureFixture
    private lateinit var vm: ProfileViewModel
    @Before fun setup() {
        f = FeatureFixture(RuntimeEnvironment.getApplication())
        vm = ProfileViewModel(f.dao, f.app.configuration, f.traktAuth, f.cache)
        awaitAppState { !vm.isLoading.value }
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); f.close() }
    private fun manual() {
        vm.startWizard(); vm.setWizardName("New Profile"); vm.setWizardAvatar("avatar_2"); vm.setWizardTheme("void")
        vm.chooseManualSetup()
    }
    @Test fun wizardStepsAndCancelResetTransientState() {
        vm.startWizard(); assertEquals(1, vm.wizardStep.value)
        vm.setWizardName("One"); assertEquals(2, vm.wizardStep.value)
        vm.setWizardAvatar("avatar_3"); assertEquals(3, vm.wizardStep.value)
        vm.cancelWizard(); assertEquals(0, vm.wizardStep.value)
        vm.startWizard(); assertEquals("", vm.tempName); assertEquals("avatar_1", vm.tempAvatarRef)
    }
    @Test fun invalidLanguageStepAndDuplicateLanguageAreIgnored() {
        manual(); val original = vm.tempLanguages
        vm.setWizardLanguage(1, "ml"); vm.setWizardLanguage(0, "")
        assertEquals(original, vm.tempLanguages)
        vm.setWizardLanguage(0, "ml"); assertEquals(7, vm.wizardStep.value)
        vm.setWizardLanguage(1, "ml"); assertEquals(7, vm.wizardStep.value)
    }
    @Test fun manualWizardPersistsMalayalamKannadaAndEnglishPriorities() {
        manual(); vm.setWizardLanguage(0, "ml"); vm.setWizardLanguage(1, "kn"); vm.setWizardLanguage(2, "en")
        awaitAppState { vm.profiles.value.size == 1 && !vm.isInitializingProfile.value }
        val p = vm.profiles.value.single()
        assertEquals("New Profile", p.name); assertEquals("avatar_2", p.avatarRef)
        assertEquals(listOf("ml","kn","en"), listOf(p.sourceLanguagePriority1,p.sourceLanguagePriority2,p.sourceLanguagePriority3))
        assertFalse(vm.needsInitialSetup(p.id)); assertEquals(0, vm.wizardStep.value)
    }
    @Test fun choosingLaterDefaultLanguageRepairsDuplicateDefaults() {
        manual(); vm.setWizardLanguage(0, "te")
        assertEquals("te", vm.tempLanguages.first()); assertEquals(3, vm.tempLanguages.toSet().size)
    }
    @Test fun pinSetRejectsInvalidInputAndRemoveClearsHash() = runBlocking {
        f.dao.insertProfile(ProfileEntity(id = 1, name = "One")); awaitAppState { vm.profiles.value.size == 1 }
        vm.setProfilePin(1, "123"); assertNull(f.dao.getProfileById(1)!!.pinHash)
        vm.setProfilePin(1, "1234")
        awaitAppState { vm.profiles.value.single().pinHash != null }
        assertTrue(vm.verifyPin(vm.profiles.value.single(), "1234"))
        vm.removeProfilePin(1); awaitAppState { vm.profiles.value.single().pinHash == null }
    }
    @Test fun fifthWrongPinLocksEvenCorrectPinTemporarily() = runBlocking {
        val p = ProfileEntity(id = 1, name = "One", pinHash = ProfilePin.hash(1,"1234"))
        f.dao.insertProfile(p)
        repeat(5) { assertFalse(vm.verifyPin(p, "0000")) }
        assertFalse(vm.verifyPin(p, "1234"))
    }
    @Test fun editingIdentityPreservesPlaybackSortingAndPin() = runBlocking {
        val p = ProfileEntity(id = 1, name = "Old", subtitleSize = 150, sourceLanguagePriority1 = "ml", pinHash = "keep")
        f.dao.insertProfile(p); awaitAppState { vm.profiles.value.size == 1 }
        vm.startEditWizard(p); vm.setWizardName("Edited"); vm.setWizardAvatar("avatar_4"); vm.setWizardTheme("void")
        awaitAppState { vm.profiles.value.single().name == "Edited" && !vm.isInitializingProfile.value }
        assertEquals(p.copy(name = "Edited", avatarRef = "avatar_4", themeId = "void"), f.dao.getProfileById(1))
    }
    @Test fun deletionClearsProfileHistoryWatchlistAndLeavesOtherProfile() = runBlocking {
        f.dao.insertProfile(ProfileEntity(id = 1, name = "One")); f.dao.insertProfile(ProfileEntity(id = 2, name = "Two"))
        f.dao.insertHistory(WatchHistoryEntity(1,"tt1","Title",null,position=10_000,duration=100_000,lastWatched=1,type="movie"))
        f.dao.addToWatchlist(WatchlistEntity(1,"tt1","movie","Title",null,1))
        vm.deleteProfile(1)
        awaitAppState { vm.profiles.value.map { it.id } == listOf(2) }
        assertNull(f.dao.getHistoryItemForProfile(1,"tt1")); assertFalse(f.dao.isInWatchlist(1,"tt1"))
    }
}
