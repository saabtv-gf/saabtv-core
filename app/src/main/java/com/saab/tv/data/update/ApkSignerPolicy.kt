package com.saab.tv.data.update

/** Pins the published release key; platform compatibility never bypasses trust. */
internal object ApkSignerPolicy {
    val releaseKeys = setOf("66b97213dd01dc614bf0fd1ee90af62c70513c7c4cf70fd579baebcf5c8f2543")

    fun matches(candidate: Set<String>, installed: Set<String>, multipleInstalledSigners: Boolean): Boolean {
        if (candidate.isEmpty() || installed.isEmpty() || candidate.any { it !in releaseKeys }) return false
        return if (multipleInstalledSigners) candidate == installed
        else candidate.size == 1 && installed.containsAll(candidate)
    }
}
