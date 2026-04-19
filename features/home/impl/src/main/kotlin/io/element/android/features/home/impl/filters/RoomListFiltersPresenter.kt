/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.filters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import chat.schildi.lib.preferences.ScPreferencesStore
import dev.zacsweers.metro.Inject
import io.element.android.features.home.impl.filters.selection.FilterSelectionStrategy
import io.element.android.libraries.architecture.Presenter
import kotlinx.collections.immutable.toImmutableList

@Inject
class RoomListFiltersPresenter(
    private val filterSelectionStrategy: FilterSelectionStrategy,
    private val scPreferencesStore: ScPreferencesStore,
) : Presenter<RoomListFiltersState> {
    @Composable
    override fun present(): RoomListFiltersState {
        fun handleEvent(event: RoomListFiltersEvent) {
            when (event) {
                RoomListFiltersEvent.ClearSelectedFilters -> {
                    filterSelectionStrategy.clear()
                }
                is RoomListFiltersEvent.ToggleFilter -> {
                    filterSelectionStrategy.toggle(event.filter)
                }
            }
        }

        ScClearRoomFiltersEffect(filterSelectionStrategy, scPreferencesStore)

        val filters by filterSelectionStrategy.filterSelectionStates.collectAsState()
        return RoomListFiltersState(
            filterSelectionStates = filters.toImmutableList(),
            eventSink = ::handleEvent,
        )
    }
}
