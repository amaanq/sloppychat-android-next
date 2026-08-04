@file:OptIn(ExperimentalMaterial3Api::class)

package io.element.android.features.roomdetails.impl.ownprofile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.components.async.AsyncActionViewDefaults
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.SaveChangesDialog
import io.element.android.libraries.designsystem.modifiers.clearFocusOnTap
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.ui.components.AvatarActionBottomSheet
import io.element.android.libraries.matrix.ui.components.AvatarPickerState
import io.element.android.libraries.matrix.ui.components.AvatarPickerView
import io.element.android.libraries.permissions.api.PermissionsView
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun ScRoomProfileView(
    state: ScRoomProfileState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val isAvatarActionsSheetVisible = remember { mutableStateOf(false) }

    fun onAvatarClick() {
        focusManager.clearFocus()
        isAvatarActionsSheetVisible.value = true
    }

    BackHandler {
        state.eventSink(ScRoomProfileEvent.OnBackPress)
    }
    Scaffold(
        modifier = modifier.clearFocusOnTap(focusManager),
        topBar = {
            TopAppBar(
                titleStr = stringResource(id = chat.schildi.lib.R.string.sc_room_profile_edit_title),
                navigationIcon = {
                    BackButton(
                        onClick = {
                            state.eventSink(ScRoomProfileEvent.OnBackPress)
                        }
                    )
                },
                actions = {
                    TextButton(
                        text = stringResource(CommonStrings.action_save),
                        enabled = state.saveButtonEnabled,
                        onClick = {
                            focusManager.clearFocus()
                            state.eventSink(ScRoomProfileEvent.Save)
                        },
                    )
                }
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            val avatarPickerState = remember(state.avatarUrl, state.displayName) {
                AvatarPickerState.Selected(
                    avatarData = AvatarData(
                        id = state.ownUserId.value,
                        name = state.displayName,
                        url = state.avatarUrl,
                        size = AvatarSize.EditProfileDetails,
                    ),
                    type = AvatarType.User,
                )
            }
            AvatarPickerView(
                state = avatarPickerState,
                onClick = ::onAvatarClick,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(32.dp))

            TextField(
                label = stringResource(id = chat.schildi.lib.R.string.sc_room_profile_nickname_label),
                value = state.displayName,
                singleLine = true,
                onValueChange = { state.eventSink(ScRoomProfileEvent.UpdateDisplayName(it)) },
            )
        }
    }
    AvatarActionBottomSheet(
        actions = state.avatarActions,
        isVisible = isAvatarActionsSheetVisible.value,
        onDismiss = { isAvatarActionsSheetVisible.value = false },
        onSelectAction = { state.eventSink(ScRoomProfileEvent.HandleAvatarAction(it)) }
    )
    AsyncActionView(
        async = state.saveAction,
        progressDialog = {
            AsyncActionViewDefaults.ProgressDialog()
        },
        confirmationDialog = {
            if (state.saveAction == AsyncAction.ConfirmingCancellation) {
                SaveChangesDialog(
                    onSaveClick = { state.eventSink(ScRoomProfileEvent.Save) },
                    onDiscardClick = { state.eventSink(ScRoomProfileEvent.OnBackPress) },
                    onDismiss = { state.eventSink(ScRoomProfileEvent.CloseDialog) }
                )
            }
        },
        onSuccess = { onDone() },
        errorMessage = { stringResource(CommonStrings.error_unknown) },
        onErrorDismiss = { state.eventSink(ScRoomProfileEvent.CloseDialog) }
    )

    PermissionsView(
        state = state.cameraPermissionState,
    )
}
