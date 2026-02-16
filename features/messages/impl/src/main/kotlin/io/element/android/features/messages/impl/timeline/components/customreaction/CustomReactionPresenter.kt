/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.customreaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import chat.schildi.imagepacks.ImagePackService
import chat.schildi.lib.preferences.ScPrefs
import chat.schildi.lib.preferences.value
import dev.zacsweers.metro.Inject
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.emoji.api.picker.EmojiPickerPresenter
import io.element.android.libraries.emoji.api.recentemojis.GetRecentEmojis
import io.element.android.libraries.matrix.api.room.JoinedRoom
import kotlinx.collections.immutable.toImmutableSet

@Inject
class CustomReactionPresenter(
    emojiPickerPresenterFactory: EmojiPickerPresenter.Factory,
    getRecentEmojis: GetRecentEmojis,
    private val imagePackService: ImagePackService, // SC
    private val room: JoinedRoom, // SC
) : Presenter<CustomReactionState> {
    private val emojiPickerPresenter = emojiPickerPresenterFactory.create(getRecentEmojis, room)
    @Composable
    override fun present(): CustomReactionState {
        var internalTarget by remember { mutableStateOf<InternalTarget>(InternalTarget.None) }
        val emojiPickerState = emojiPickerPresenter.present()

        // SC: eagerly preload image packs so the cache is warm when the picker opens
        val customEmojisEnabled = ScPrefs.ENABLE_CUSTOM_EMOJIS.value()
        LaunchedEffect(customEmojisEnabled) {
            if (customEmojisEnabled) {
                imagePackService.refreshPacks(room)
            }
        }

        fun handleEvent(event: CustomReactionEvent) {
            when (event) {
                is CustomReactionEvent.ShowCustomReactionSheet -> {
                    internalTarget = InternalTarget.Showing(event.event)
                }
                is CustomReactionEvent.DismissCustomReactionSheet -> {
                    internalTarget = InternalTarget.None
                }
            }
        }

        val computedTarget = when (val target = internalTarget) {
            InternalTarget.None -> CustomReactionState.Target.None
            is InternalTarget.Showing -> if (emojiPickerState.isReady) {
                CustomReactionState.Target.Success(
                    event = target.event,
                    emojiPickerState = emojiPickerState,
                )
            } else {
                CustomReactionState.Target.Loading(target.event)
            }
        }

        val eventForSelection = (computedTarget as? CustomReactionState.Target.Success)?.event
        val selectedEmoji = eventForSelection
            ?.reactionsState
            ?.reactions
            ?.mapNotNull { if (it.isHighlighted) it.key else null }
            .orEmpty()
            .toImmutableSet()

        return CustomReactionState(
            target = computedTarget,
            selectedEmoji = selectedEmoji,
            eventSink = ::handleEvent,
        )
    }
}

private sealed interface InternalTarget {
    data object None : InternalTarget
    data class Showing(val event: TimelineItem.Event) : InternalTarget
}
