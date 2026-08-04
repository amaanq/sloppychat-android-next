package io.element.android.features.roomdetails.impl.ownprofile

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.test.AN_AVATAR_URL
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomMember
import io.element.android.libraries.matrix.ui.media.AvatarAction
import io.element.android.libraries.mediapickers.test.FakePickerProvider
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenterFactory
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.fake.FakeTemporaryUriDeleter
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

private const val A_PICKED_URI = "content://picked"
private const val AN_UPLOADED_MXC = "mxc://server/uploaded"
private const val A_MEMBER_CONTENT = """{"membership":"join","displayname":"Nick","avatar_url":"$AN_AVATAR_URL"}"""

@ExperimentalCoroutinesApi
class ScRoomProfilePresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    private lateinit var fakePickerProvider: FakePickerProvider
    private lateinit var fakeMediaPreProcessor: FakeMediaPreProcessor

    private val avatarUri: Uri = mockk()
    private val pickedUri: Uri = mockk()

    @Before
    fun setup() {
        fakePickerProvider = FakePickerProvider()
        fakeMediaPreProcessor = FakeMediaPreProcessor()
        mockkStatic(Uri::class)

        every { Uri.parse(AN_AVATAR_URL) } returns avatarUri
        every { avatarUri.toString() } returns AN_AVATAR_URL
        every { Uri.parse(A_PICKED_URI) } returns pickedUri
        every { pickedUri.toString() } returns A_PICKED_URI
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun aRoom(
        sendRawState: (String, String, String) -> Result<String> = { _, _, _ -> Result.success("\$event") },
    ): FakeJoinedRoom {
        val baseRoom = FakeBaseRoom(
            getUpdatedMemberResult = {
                Result.success(aRoomMember(userId = A_SESSION_ID, displayName = "Nick", avatarUrl = AN_AVATAR_URL))
            },
        ).apply {
            getRawStateLambda = { _, _ -> Result.success(A_MEMBER_CONTENT) }
            sendRawStateLambda = sendRawState
        }
        return FakeJoinedRoom(baseRoom = baseRoom)
    }

    private fun createPresenter(
        room: FakeJoinedRoom = aRoom(),
        matrixClient: FakeMatrixClient = FakeMatrixClient(),
        permissionsPresenter: PermissionsPresenter = FakePermissionsPresenter(),
        temporaryUriDeleter: TemporaryUriDeleter = FakeTemporaryUriDeleter(lambdaRecorder<Uri?, Unit> {}),
    ): ScRoomProfilePresenter {
        return ScRoomProfilePresenter(
            room = room,
            matrixClient = matrixClient,
            mediaPickerProvider = fakePickerProvider,
            mediaPreProcessor = fakeMediaPreProcessor,
            temporaryUriDeleter = temporaryUriDeleter,
            permissionsPresenterFactory = FakePermissionsPresenterFactory(permissionsPresenter),
            mediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(),
        )
    }

    @Test
    fun `present - save with a new nickname patches the member event and keeps the other fields`() = runTest {
        var capturedContent: String? = null
        var capturedStateKey: String? = null
        val room = aRoom(
            sendRawState = { _, stateKey, content ->
                capturedStateKey = stateKey
                capturedContent = content
                Result.success("\$event")
            },
        )
        val presenter = createPresenter(room = room)
        presenter.test {
            val loadedState = consumeItemsUntilPredicate { it.displayName == "Nick" }.last()
            loadedState.eventSink(ScRoomProfileEvent.UpdateDisplayName("New Nick"))
            val editedState = consumeItemsUntilPredicate { it.displayName == "New Nick" }.last()
            assertThat(editedState.saveButtonEnabled).isTrue()
            editedState.eventSink(ScRoomProfileEvent.Save)
            consumeItemsUntilPredicate { it.saveAction.isSuccess() }
            assertThat(capturedStateKey).isEqualTo(A_SESSION_ID.value)
            assertThat(capturedContent).isEqualTo(
                """{"membership":"join","displayname":"New Nick","avatar_url":"$AN_AVATAR_URL"}"""
            )
        }
    }

    @Test
    fun `present - save after removing the avatar drops avatar_url`() = runTest {
        var capturedContent: String? = null
        val room = aRoom(
            sendRawState = { _, _, content ->
                capturedContent = content
                Result.success("\$event")
            },
        )
        val presenter = createPresenter(room = room)
        presenter.test {
            val loadedState = consumeItemsUntilPredicate { it.displayName == "Nick" }.last()
            loadedState.eventSink(ScRoomProfileEvent.HandleAvatarAction(AvatarAction.Remove))
            val editedState = consumeItemsUntilPredicate { it.avatarUrl == null }.last()
            assertThat(editedState.saveButtonEnabled).isTrue()
            editedState.eventSink(ScRoomProfileEvent.Save)
            consumeItemsUntilPredicate { it.saveAction.isSuccess() }
            assertThat(capturedContent).isEqualTo(
                """{"membership":"join","displayname":"Nick"}"""
            )
        }
    }

    @Test
    fun `present - save with a picked avatar uploads it and writes the mxc url`() = runTest {
        val tmpFile = File.createTempFile("avatar", ".jpg").apply {
            writeBytes(ByteArray(2))
            deleteOnExit()
        }
        fakeMediaPreProcessor.givenResult(
            Result.success(
                MediaUploadInfo.AnyFile(
                    tmpFile,
                    FileInfo(
                        mimetype = MimeTypes.Jpeg,
                        size = 2L,
                        thumbnailInfo = null,
                        thumbnailSource = null,
                    ),
                )
            )
        )
        fakePickerProvider.givenResult(pickedUri)
        var capturedContent: String? = null
        val room = aRoom(
            sendRawState = { _, _, content ->
                capturedContent = content
                Result.success("\$event")
            },
        )
        val matrixClient = FakeMatrixClient().apply {
            givenUploadMediaResult(Result.success(AN_UPLOADED_MXC))
        }
        val presenter = createPresenter(room = room, matrixClient = matrixClient)
        presenter.test {
            val loadedState = consumeItemsUntilPredicate { it.displayName == "Nick" }.last()
            loadedState.eventSink(ScRoomProfileEvent.HandleAvatarAction(AvatarAction.ChoosePhoto))
            val editedState = consumeItemsUntilPredicate { it.avatarUrl == A_PICKED_URI }.last()
            assertThat(editedState.saveButtonEnabled).isTrue()
            editedState.eventSink(ScRoomProfileEvent.Save)
            consumeItemsUntilPredicate { it.saveAction.isSuccess() }
            assertThat(capturedContent).isEqualTo(
                """{"membership":"join","displayname":"Nick","avatar_url":"$AN_UPLOADED_MXC"}"""
            )
        }
    }
}
