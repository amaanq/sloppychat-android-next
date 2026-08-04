package io.element.android.features.roomdetails.impl.ownprofile

import android.Manifest
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.ui.media.AvatarAction
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsPresenter
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val EVENT_TYPE_ROOM_MEMBER = "m.room.member"

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Inject
class ScRoomProfilePresenter(
    private val room: JoinedRoom,
    private val matrixClient: MatrixClient,
    private val mediaPickerProvider: PickerProvider,
    private val mediaPreProcessor: MediaPreProcessor,
    private val temporaryUriDeleter: TemporaryUriDeleter,
    permissionsPresenterFactory: PermissionsPresenter.Factory,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
) : Presenter<ScRoomProfileState> {
    private val cameraPermissionPresenter = permissionsPresenterFactory.create(Manifest.permission.CAMERA)
    private var pendingPermissionRequest = false

    @Composable
    override fun present(): ScRoomProfileState {
        val cameraPermissionState = cameraPermissionPresenter.present()

        var initialized by rememberSaveable { mutableStateOf(false) }
        var originalDisplayName by rememberSaveable { mutableStateOf("") }
        var originalAvatarUrl by rememberSaveable { mutableStateOf<String?>(null) }
        var displayNameEdited by rememberSaveable { mutableStateOf("") }
        var avatarUriEdited by rememberSaveable { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            if (!initialized) {
                room.getUpdatedMember(room.sessionId).onSuccess { member ->
                    originalDisplayName = member.displayName.orEmpty()
                    originalAvatarUrl = member.avatarUrl
                    displayNameEdited = member.displayName.orEmpty()
                    avatarUriEdited = member.avatarUrl
                }
                initialized = true
            }
        }

        val saveButtonEnabled by remember(originalDisplayName, originalAvatarUrl) {
            derivedStateOf {
                displayNameEdited.trim() != originalDisplayName.trim() || avatarUriEdited != originalAvatarUrl
            }
        }

        val cameraPhotoPicker = mediaPickerProvider.registerCameraPhotoPicker(
            onResult = { uri ->
                if (uri != null) {
                    temporaryUriDeleter.delete(avatarUriEdited?.takeIf { it != originalAvatarUrl }?.toUri())
                    avatarUriEdited = uri.toString()
                }
            }
        )
        val galleryImagePicker = mediaPickerProvider.registerGalleryImagePicker(
            onResult = { uri ->
                if (uri != null) {
                    temporaryUriDeleter.delete(avatarUriEdited?.takeIf { it != originalAvatarUrl }?.toUri())
                    avatarUriEdited = uri.toString()
                }
            }
        )

        LaunchedEffect(cameraPermissionState.permissionGranted) {
            if (cameraPermissionState.permissionGranted && pendingPermissionRequest) {
                pendingPermissionRequest = false
                cameraPhotoPicker.launch()
            }
        }

        val avatarActions by remember(avatarUriEdited) {
            derivedStateOf {
                listOfNotNull(
                    AvatarAction.TakePhoto,
                    AvatarAction.ChoosePhoto,
                    AvatarAction.Remove.takeIf { avatarUriEdited != null },
                ).toImmutableList()
            }
        }

        val saveAction: MutableState<AsyncAction<Unit>> = remember { mutableStateOf(AsyncAction.Uninitialized) }
        val localCoroutineScope = rememberCoroutineScope()
        fun handleEvent(event: ScRoomProfileEvent) {
            when (event) {
                is ScRoomProfileEvent.Save -> localCoroutineScope.saveChanges(
                    newDisplayName = displayNameEdited,
                    originalDisplayName = originalDisplayName,
                    newAvatarUri = avatarUriEdited,
                    originalAvatarUrl = originalAvatarUrl,
                    action = saveAction,
                )
                is ScRoomProfileEvent.HandleAvatarAction -> {
                    when (event.action) {
                        AvatarAction.ChoosePhoto -> galleryImagePicker.launch()
                        AvatarAction.TakePhoto -> if (cameraPermissionState.permissionGranted) {
                            cameraPhotoPicker.launch()
                        } else {
                            pendingPermissionRequest = true
                            cameraPermissionState.eventSink(PermissionsEvent.RequestPermissions)
                        }
                        AvatarAction.Remove -> {
                            temporaryUriDeleter.delete(avatarUriEdited?.takeIf { it != originalAvatarUrl }?.toUri())
                            avatarUriEdited = null
                        }
                    }
                }
                is ScRoomProfileEvent.UpdateDisplayName -> displayNameEdited = event.displayName
                ScRoomProfileEvent.CloseDialog -> saveAction.value = AsyncAction.Uninitialized
                ScRoomProfileEvent.OnBackPress -> if (saveButtonEnabled.not() || saveAction.value == AsyncAction.ConfirmingCancellation) {
                    saveAction.value = AsyncAction.Success(Unit)
                } else {
                    saveAction.value = AsyncAction.ConfirmingCancellation
                }
            }
        }

        return ScRoomProfileState(
            ownUserId = room.sessionId,
            displayName = displayNameEdited,
            avatarUrl = avatarUriEdited,
            avatarActions = avatarActions,
            saveButtonEnabled = saveButtonEnabled,
            saveAction = saveAction.value,
            cameraPermissionState = cameraPermissionState,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.saveChanges(
        newDisplayName: String,
        originalDisplayName: String,
        newAvatarUri: String?,
        originalAvatarUrl: String?,
        action: MutableState<AsyncAction<Unit>>,
    ) = launch {
        suspend {
            val ownUserId = room.sessionId.value
            val content = room.ownMemberContent().toMutableMap()
            val newDisplayNameTrimmed = newDisplayName.trim()
            if (newDisplayNameTrimmed != originalDisplayName.trim()) {
                if (newDisplayNameTrimmed.isEmpty()) {
                    content.remove("displayname")
                } else {
                    content["displayname"] = JsonPrimitive(newDisplayNameTrimmed)
                }
            }
            if (newAvatarUri != originalAvatarUrl) {
                if (newAvatarUri == null) {
                    content.remove("avatar_url")
                } else {
                    content["avatar_url"] = JsonPrimitive(uploadAvatar(newAvatarUri.toUri()))
                }
            }
            room.sendRawState(EVENT_TYPE_ROOM_MEMBER, ownUserId, JsonObject(content).toString()).getOrThrow()
            Unit
        }.runCatchingUpdatingState(action)
    }

    private suspend fun uploadAvatar(avatarUri: Uri): String {
        val preprocessed = mediaPreProcessor.process(
            uri = avatarUri,
            mimeType = MimeTypes.Jpeg,
            deleteOriginal = false,
            mediaOptimizationConfig = mediaOptimizationConfigProvider.get(),
        ).getOrThrow()
        return matrixClient.uploadMedia(MimeTypes.Jpeg, preprocessed.file.readBytes()).getOrThrow()
    }
}

private fun JsonObject.stateEventContent(): JsonObject = this["content"]?.jsonObject ?: this
