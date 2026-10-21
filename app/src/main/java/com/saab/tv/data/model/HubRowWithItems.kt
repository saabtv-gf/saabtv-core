package com.saab.tv.data.model

data class HubRowWithItems(
    val hub: HubRowEntity,
    val items: List<HubRowItemEntity>
)
