package com.saab.tv.ui.common

internal object ListStateSafety {
    fun boundedIndex(requestedIndex: Int, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        return requestedIndex.coerceIn(0, itemCount - 1)
    }
}
