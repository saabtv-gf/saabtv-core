package com.saab.tv.data.profile

import com.saab.tv.data.model.ProfileEntity

/** Applied only when creating a manually configured profile, never on app upgrade. */
internal fun ProfileEntity.withOnboardingPreferences(languages: List<String>): ProfileEntity {
    require(languages.size == 3 && languages.distinct().size == 3 && languages.all { it.isNotBlank() })
    return copy(
        sourceLanguagePriority1 = languages[0],
        sourceLanguagePriority2 = languages[1],
        sourceLanguagePriority3 = languages[2]
    )
}
