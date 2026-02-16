/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.emoji.impl.picker

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import chat.schildi.imagepacks.ImagePackService
import chat.schildi.lib.preferences.ScPrefs
import chat.schildi.lib.preferences.ScPreferencesStore
import chat.schildi.lib.preferences.settingState
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import io.element.android.emojibasebindings.Emoji
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.SearchBarResultState
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.emoji.api.picker.EmojiPickerPresenter
import io.element.android.libraries.emoji.api.picker.CustomEmoji
import io.element.android.libraries.emoji.api.picker.EmojiPickerState
import io.element.android.libraries.emoji.api.recentemojis.GetRecentEmojis
import io.element.android.libraries.emoji.impl.EmojibaseProvider
import io.element.android.libraries.emoji.impl.R
import io.element.android.libraries.matrix.api.room.BaseRoom
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@AssistedInject
class DefaultEmojiPickerPresenter(
    private val emojibaseProvider: EmojibaseProvider,
    @Assisted private val getRecentEmojis: GetRecentEmojis,
    @Assisted private val room: BaseRoom?, // SC
    private val imagePackService: ImagePackService, // SC
    private val scPreferencesStore: ScPreferencesStore, // SC
    private val coroutineDispatchers: CoroutineDispatchers,
) : EmojiPickerPresenter {
    @AssistedFactory
    @ContributesBinding(SessionScope::class)
    interface Factory : EmojiPickerPresenter.Factory {
        override fun create(getRecentEmojis: GetRecentEmojis, room: BaseRoom?): DefaultEmojiPickerPresenter
    }

    @Composable
    override fun present(): EmojiPickerState {
        val queryState = rememberTextFieldState()
        var isSearchActive by remember { mutableStateOf(false) }
        var emojiResults by remember { mutableStateOf<SearchBarResultState<ImmutableList<Emoji>>>(SearchBarResultState.Initial) }

        val data by produceState(EmojiPickerData.Empty) {
            val storeDeferred = async { emojibaseProvider.getStore() }
            val recentsDeferred = async { getRecentEmojis().getOrNull() ?: persistentListOf() }
            val store = storeDeferred.await()
            val recentEmojiUnicodes = recentsDeferred.await()
            value = withContext(coroutineDispatchers.computation) {
                val baseCategories = store.categories.map { (category, emojis) ->
                    EmojiCategory(
                        titleId = category.title,
                        icon = IconSource.Vector(category.icon),
                        emojis = emojis,
                    )
                }
                val recentEmojis = recentEmojiUnicodes
                    .mapNotNull { unicode -> store.allEmojis.find { it.unicode == unicode } }
                    .toImmutableList()
                val categories = if (recentEmojis.isEmpty()) {
                    baseCategories.toImmutableList()
                } else {
                    val recentCategory = EmojiCategory(
                        titleId = R.string.emoji_picker_category_recent,
                        icon = IconSource.Resource(io.element.android.compound.R.drawable.ic_compound_history),
                        emojis = recentEmojis,
                    )
                    (listOf(recentCategory) + baseCategories).toImmutableList()
                }
                EmojiPickerData(
                    categories = categories,
                    allEmojis = store.allEmojis,
                )
            }
        }

        // SC: load custom emoji packs for this room (plus account-level packs).
        val customEmojisEnabled by scPreferencesStore.settingState(ScPrefs.ENABLE_CUSTOM_EMOJIS)
        var customEmojiPacks by remember { mutableStateOf<ImmutableList<CustomEmojiCategory>>(persistentListOf()) }
        LaunchedEffect(room, customEmojisEnabled) {
            customEmojiPacks = if (customEmojisEnabled) {
                withContext(coroutineDispatchers.computation) {
                    imagePackService.getAllEmoticons(room).map { (resolved, images) ->
                        CustomEmojiCategory(
                            packName = resolved.pack.displayName ?: "Custom",
                            avatarUrl = resolved.pack.avatarUrl,
                            emojis = images.map { image ->
                                CustomEmoji(
                                    shortcode = image.shortcode,
                                    url = image.url,
                                    body = image.body,
                                )
                            }.toImmutableList(),
                        )
                    }.toImmutableList()
                }
            } else {
                persistentListOf()
            }
        }

        val searchQuery = queryState.text.toString()
        var customEmojiSearchResults by remember { mutableStateOf<ImmutableList<CustomEmoji>>(persistentListOf()) }
        // SC: rerun when packs finish loading too — otherwise typing before the pack
        // flow emits leaves the custom-emoji results empty until the user edits the query.
        LaunchedEffect(searchQuery, data, customEmojiPacks) {
            if (searchQuery.isEmpty() || data.allEmojis.isEmpty()) {
                customEmojiSearchResults = persistentListOf()
                emojiResults = SearchBarResultState.Initial
                return@LaunchedEffect
            }
            delay(100.milliseconds)
            val lowercaseQuery = searchQuery.lowercase()
            val results = withContext(coroutineDispatchers.computation) {
                data.allEmojis
                    .asSequence()
                    .filter { emoji ->
                        emoji.tags.orEmpty().any { it.contains(lowercaseQuery) } ||
                            emoji.shortcodes.any { it.contains(lowercaseQuery) }
                    }
                    .take(60)
                    .toImmutableList()
            }
            // SC: dedupe by URL — packs often share the same image across shortcodes and
            // packs, and the LazyGrid rendering these would crash on duplicate keys.
            customEmojiSearchResults = withContext(coroutineDispatchers.computation) {
                customEmojiPacks.flatMap { category ->
                    category.emojis.filter { emoji ->
                        emoji.shortcode.lowercase().contains(lowercaseQuery) ||
                            emoji.body?.lowercase()?.contains(lowercaseQuery) == true
                    }
                }.distinctBy { it.url }.take(60).toImmutableList()
            }
            emojiResults = SearchBarResultState.Results(results)
        }

        val isInPreview = LocalInspectionMode.current
        fun handleEvent(event: EmojiPickerEvent) {
            when (event) {
                is EmojiPickerEvent.ToggleSearchActive -> if (!isInPreview) {
                    isSearchActive = event.isActive
                }
            }
        }

        return DefaultEmojiPickerState(
            categories = data.categories,
            searchQuery = queryState,
            isSearchActive = isSearchActive,
            searchResults = emojiResults,
            customEmojiPacks = customEmojiPacks, // SC
            customEmojiSearchResults = customEmojiSearchResults, // SC
            eventSink = ::handleEvent,
        )
    }
}

private data class EmojiPickerData(
    val categories: ImmutableList<EmojiCategory>,
    val allEmojis: ImmutableList<Emoji>,
) {
    companion object {
        val Empty = EmojiPickerData(persistentListOf(), persistentListOf())
    }
}
