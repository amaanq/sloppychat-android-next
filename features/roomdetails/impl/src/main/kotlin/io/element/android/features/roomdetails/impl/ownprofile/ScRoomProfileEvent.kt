package io.element.android.features.roomdetails.impl.ownprofile

import io.element.android.libraries.matrix.ui.media.AvatarAction

sealed interface ScRoomProfileEvent {
    data class HandleAvatarAction(val action: AvatarAction) : ScRoomProfileEvent
    data class UpdateDisplayName(val displayName: String) : ScRoomProfileEvent
    data object OnBackPress : ScRoomProfileEvent
    data object Save : ScRoomProfileEvent
    data object CloseDialog : ScRoomProfileEvent
}
