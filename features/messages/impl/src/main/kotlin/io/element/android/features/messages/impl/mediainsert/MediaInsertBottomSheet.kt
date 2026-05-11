package io.element.android.features.messages.impl.mediainsert

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.schildi.lib.R
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.emojibasebindings.Emoji
import io.element.android.features.messages.impl.sticker.Sticker
import io.element.android.features.messages.impl.sticker.StickerPickerContent
import io.element.android.features.messages.impl.sticker.StickerPickerState
import io.element.android.features.messages.impl.timeline.components.customreaction.picker.CustomEmoji
import io.element.android.features.messages.impl.timeline.components.customreaction.picker.EmojiPicker
import io.element.android.features.messages.impl.timeline.components.customreaction.picker.EmojiPickerState
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.launch

/**
 * SC: combined emoji + sticker picker invoked from the composer's smiley icon.
 *
 * Rendered inline below the composer (NOT a ModalBottomSheet) so that the real
 * composer text box stays visible above the picker — picking emoji updates the
 * actual input the user sees, no preview shim. The picker takes vertical space
 * in the column it's placed in, naturally pushing the composer up.
 *
 *  - Emoji tab: tap inserts at cursor, panel stays open (so the user can pick
 *    several emoji in a row). Custom emoji insert text is `:shortcode:`, with
 *    the shortcode → MXC mapping registered via the InsertCustomEmoji event so
 *    send-time HTML substitution turns it into an `<img>`.
 *  - Sticker tab: tap sends as a standalone message and closes the panel
 *    (preserves the existing standalone-sticker behaviour).
 *
 * Initial tab is whichever the user last used in this session; defaults to Emoji.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInsertPanel(
    emojiPickerState: EmojiPickerState,
    stickerPickerState: StickerPickerState,
    onSelectEmoji: (Emoji) -> Unit,
    onSelectCustomEmoji: (CustomEmoji) -> Unit,
    onSelectFreeformText: (String) -> Unit,
    onSendSticker: (Sticker) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(MediaInsertTab.Emoji) }
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { MediaInsertTab.entries.size },
    )

    // Keep tab indicator in sync with pager swipes.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            MediaInsertTab.entries.getOrNull(page)?.let { selectedTab = it }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Match the surrounding composer surface so the panel reads as one
            // continuous bottom region instead of floating over the timeline.
            .background(ElementTheme.colors.bgCanvasDefault),
    ) {
        SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            MediaInsertTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab.ordinal == index,
                    onClick = {
                        selectedTab = tab
                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                    },
                    icon = {
                        Icon(
                            imageVector = when (tab) {
                                MediaInsertTab.Emoji -> CompoundIcons.ReactionAdd()
                                MediaInsertTab.Stickers -> CompoundIcons.Sticker()
                            },
                            contentDescription = stringResource(tab.titleRes),
                        )
                    },
                    text = { Text(stringResource(tab.titleRes)) },
                )
            }
        }

        // Cap height so the picker doesn't shove the composer off-screen, and so
        // the two tabs render at the same height (sticker tab caps internally).
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val pagerHeight = (screenHeight * 0.45f).coerceIn(250.dp, 500.dp)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(pagerHeight),
        ) { page ->
            when (MediaInsertTab.entries[page]) {
                MediaInsertTab.Emoji -> {
                    EmojiPicker(
                        onSelectEmoji = onSelectEmoji,
                        // Freeform-tab text path: insert the user's typed text as-is.
                        // (The freeform tab in this picker is debatable in compose
                        // mode since the composer itself is a freeform input, but
                        // routing it as plain insert is the least-surprising thing.)
                        onSelectCustomEmoji = { freeform ->
                            onSelectFreeformText(freeform)
                        },
                        // Identity-aware: grid taps come through here with the full
                        // (shortcode, url) so duplicate-MXC packs don't lose identity.
                        onSelectCustomEmojiByIdentity = onSelectCustomEmoji,
                        state = emojiPickerState,
                        selectedEmojis = persistentSetOf(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                MediaInsertTab.Stickers -> {
                    StickerPickerContent(
                        state = stickerPickerState,
                        onStickerClick = { sticker ->
                            onSendSticker(sticker)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

private enum class MediaInsertTab(val titleRes: Int) {
    Emoji(R.string.sc_media_insert_tab_emoji),
    Stickers(R.string.sc_media_insert_tab_stickers),
}
