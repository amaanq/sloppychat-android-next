/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.emoji.impl.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import chat.schildi.lib.preferences.ScPrefs
import chat.schildi.lib.preferences.value
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.emojibasebindings.Emoji
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.text.toSp
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.SearchBar
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.emoji.impl.R
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerView(
    state: DefaultEmojiPickerState,
    onSelectEmoji: (Emoji) -> Unit,
    onSelectCustomEmoji: (String) -> Unit, // SC
    selectedEmojis: ImmutableSet<String>,
    modifier: Modifier = Modifier,
    contentDescription: @Composable (emoji: Emoji, isSelected: Boolean) -> String = { emoji, _ -> emoji.unicode },
) {
    val coroutineScope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        pageCount = state.scEmojiPickerSize().let {{ it }},
        initialPage = if (ScPrefs.PREFER_FREEFORM_REACTIONS.value()) state.pageFreeformReactionIndex() else 0,
    )

    var skinPickerEmoji by remember { mutableStateOf<Emoji?>(null) }

    // SC
    val customEmojiGridState = rememberLazyGridState()

    // SC: item index of each pack's header inside the combined custom emoji grid
    val packHeaderIndices = remember(state.customEmojiPacks) {
        buildList {
            var idx = 0
            for (pack in state.customEmojiPacks) {
                add(idx)
                idx += 1 + pack.emojis.size
            }
        }
    }

    Column(modifier) {
        ScEmojiPickerSearchBar(
            modifier = Modifier.padding(bottom = 10.dp),
            queryState = state.searchQuery,
            resultState = state.searchResults,
            active = state.isSearchActive,
            onActiveChange = { state.eventSink(EmojiPickerEvent.ToggleSearchActive(it)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            placeHolderTitle = stringResource(R.string.emoji_picker_search_placeholder),
        ) { emojis ->
            EmojiResults(
                emojis = emojis,
                customEmojis = state.customEmojiSearchResults, // SC
                isEmojiSelected = { selectedEmojis.contains(it.unicode) },
                onSelectEmoji = onSelectEmoji,
                onSelectCustomEmoji = onSelectCustomEmoji, // SC
                onLongPress = { skinPickerEmoji = it },
                skinPickerEmoji = skinPickerEmoji,
                onDismissSkinPicker = { skinPickerEmoji = null },
                selectedEmojis = selectedEmojis,
                contentDescription = contentDescription,
            )
        }

        if (!state.isSearchActive) {
            // SC: map pager page index to tab index — the combined custom page has no tab.
            val selectedTabIndex = run {
                val page = pagerState.currentPage
                when {
                    page < state.categories.size -> page
                    page == state.customPackPageIndex() -> (state.categories.size - 1).coerceAtLeast(0)
                    else -> page - state.customPageCount()
                }
            }
            val isCustomPackSelected = pagerState.currentPage == state.customPackPageIndex()

            SecondaryTabRow(
                selectedTabIndex = selectedTabIndex,
                indicator = {
                    if (!isCustomPackSelected) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(selectedTabIndex)
                        )
                    }
                },
            ) {
                state.categories.forEachIndexed { index, category ->
                    Tab(
                        icon = {
                            when (category.icon) {
                                is IconSource.Resource -> Icon(
                                    resourceId = category.icon.id,
                                    contentDescription = stringResource(id = category.titleId)
                                )
                                is IconSource.Vector -> Icon(
                                    imageVector = category.icon.vector,
                                    contentDescription = stringResource(id = category.titleId)
                                )
                            }
                        },
                        selected = pagerState.currentPage.removeScPickerOffset() == index,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(index.addScPickerOffset()) }
                        }
                    )
                }
                ScEmojiPickerNonCustomTabsEnd(state, pagerState) { state.eventSink(EmojiPickerEvent.ToggleSearchActive(true)) }
            }

            // SC: pack switcher between the tabs and the grid, scrolls the combined grid to a section
            if (state.customEmojiPacks.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(state.customEmojiPacks.toList()) { packIndex, pack ->
                        val iconUrl = pack.avatarUrl ?: pack.emojis.firstOrNull()?.url
                        val isSelected = isCustomPackSelected &&
                            packHeaderIndices.getOrNull(packIndex)?.let { headerIdx ->
                                val nextHeaderIdx = packHeaderIndices.getOrNull(packIndex + 1) ?: Int.MAX_VALUE
                                customEmojiGridState.firstVisibleItemIndex in headerIdx until nextHeaderIdx
                            } == true
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .then(
                                    if (isSelected) {
                                        Modifier.background(ElementTheme.colors.bgSubtlePrimary)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(state.customPackPageIndex())
                                        packHeaderIndices.getOrNull(packIndex)?.let {
                                            customEmojiGridState.animateScrollToItem(it)
                                        }
                                    }
                                }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (iconUrl != null) {
                                AsyncImage(
                                    model = MediaRequestData(MediaSource(iconUrl), MediaRequestData.Kind.Content),
                                    contentDescription = pack.packName,
                                    modifier = Modifier.size(24.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Text(
                                    text = pack.packName.take(2),
                                    style = ElementTheme.typography.fontBodySmRegular,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = ElementTheme.colors.borderDisabled)
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
            ) { scIndex ->
                val index = scIndex.removeScPickerOffset()
                if (scEmojiPickerPage(
                        state,
                        scIndex,
                        pagerState.currentPage,
                        onSelectCustomEmoji,
                        customEmojiGridState,
                ) {
                    state.eventSink(EmojiPickerEvent.ToggleSearchActive(true))
                    pagerState.requestScrollToPage(0)
                }) {
                    return@HorizontalPager
                }

                val emojis = state.categories[index].emojis
                EmojiResults(
                    emojis = emojis,
                    isEmojiSelected = { selectedEmojis.contains(it.unicode) },
                    onSelectEmoji = onSelectEmoji,
                    onLongPress = { skinPickerEmoji = it },
                    skinPickerEmoji = skinPickerEmoji,
                    onDismissSkinPicker = { skinPickerEmoji = null },
                    selectedEmojis = selectedEmojis,
                    contentDescription = contentDescription,
                )
            }
        }
    }
}

@Composable
private fun EmojiResults(
    emojis: ImmutableList<Emoji>,
    isEmojiSelected: (Emoji) -> Boolean,
    onSelectEmoji: (Emoji) -> Unit,
    onLongPress: (Emoji) -> Unit,
    customEmojis: ImmutableList<CustomEmoji> = persistentListOf(), // SC
    onSelectCustomEmoji: (String) -> Unit = {}, // SC
    skinPickerEmoji: Emoji?,
    onDismissSkinPicker: () -> Unit,
    selectedEmojis: ImmutableSet<String>,
    contentDescription: @Composable (emoji: Emoji, isSelected: Boolean) -> String,
) {
    LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        columns = GridCells.Adaptive(minSize = 48.dp),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(emojis, key = { it.unicode }) { item ->
            EmojiItem(
                modifier = Modifier.aspectRatio(1f),
                item = item,
                isSelected = isEmojiSelected(item),
                onSelectEmoji = onSelectEmoji,
                onLongPress = onLongPress,
                skinPickerEmoji = skinPickerEmoji,
                onDismissSkinPicker = onDismissSkinPicker,
                emojiSize = 32.dp.toSp(),
                selectedSkinUnicodes = selectedEmojis,
                hasSelectedSkin = item.skins?.any { skin -> skin.unicode in selectedEmojis } == true,
                contentDescription = contentDescription,
            )
        }
        // SC: custom emoji matches are appended to the unicode search results
        items(customEmojis, key = { "custom_${it.shortcode}_${it.url}" }) { emoji ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clickable { onSelectCustomEmoji(emoji.url) },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = MediaRequestData(MediaSource(emoji.url), MediaRequestData.Kind.Content),
                    contentDescription = emoji.body ?: ":${emoji.shortcode}:",
                    modifier = Modifier.size(32.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun EmojiPickerViewPreview(@PreviewParameter(DefaultEmojiPickerStateProvider::class) state: DefaultEmojiPickerState) = ElementPreview {
    EmojiPickerView(
        state = state,
        onSelectEmoji = {},
        onSelectCustomEmoji = {}, // SC
        selectedEmojis = persistentSetOf("😀", "😄", "😃"),
        modifier = Modifier.fillMaxWidth(),
    )
}
