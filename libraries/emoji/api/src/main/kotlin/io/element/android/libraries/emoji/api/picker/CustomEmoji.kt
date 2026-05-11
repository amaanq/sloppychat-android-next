/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.emoji.api.picker

import androidx.compose.runtime.Immutable

/**
 * SC: a single custom emoji from an `im.ponies` image pack.
 *
 * Lives in the api module because callers outside the picker need the whole
 * (shortcode, url) pair — packs routinely reuse one MXC URL across shortcodes,
 * so the URL alone does not identify the emoji the user tapped.
 */
@Immutable
data class CustomEmoji(
    val shortcode: String,
    val url: String,
    val body: String?,
)
