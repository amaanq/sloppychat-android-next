package io.element.android.features.home.impl.filters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import chat.schildi.lib.preferences.ScPreferencesStore
import chat.schildi.lib.preferences.ScPrefs
import chat.schildi.lib.preferences.settingState
import io.element.android.features.home.impl.filters.selection.FilterSelectionStrategy

@Composable
internal fun ScClearRoomFiltersEffect(
    filterSelectionStrategy: FilterSelectionStrategy,
    scPreferencesStore: ScPreferencesStore,
) {
    val isFeatureEnabled = scPreferencesStore.settingState(ScPrefs.ELEMENT_ROOM_LIST_FILTERS).value
    LaunchedEffect(isFeatureEnabled) {
        if (!isFeatureEnabled) {
            filterSelectionStrategy.clear()
        }
    }
}
