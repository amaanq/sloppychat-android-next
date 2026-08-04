package io.element.android.features.roomdetails.impl

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import chat.schildi.lib.preferences.ScPrefs
import chat.schildi.lib.preferences.value
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.preferences.PreferenceSwitch
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text

@Composable
internal fun ScRoomProfileItem(
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(id = chat.schildi.lib.R.string.sc_room_profile_item)) },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.UserProfile())),
        onClick = onClick,
    )
}

@Composable
internal fun LowPriorityItem(
    isLowPriority: Boolean,
    onLowPriorityChanges: (Boolean) -> Unit,
) {
    if (ScPrefs.BURY_LOW_PRIORITY.value()) {
        PreferenceSwitch(
            icon = Icons.Default.Archive,
            title = stringResource(id = chat.schildi.lib.R.string.sc_action_low_priority),
            isChecked = isLowPriority,
            onCheckedChange = onLowPriorityChanges
        )
    }
}
