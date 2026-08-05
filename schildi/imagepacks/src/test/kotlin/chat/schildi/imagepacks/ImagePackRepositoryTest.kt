package chat.schildi.imagepacks

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val EVENT_TYPE_ROOM_EMOTES = "im.ponies.room_emotes"
private const val EVENT_TYPE_EMOTE_ROOMS = "im.ponies.emote_rooms"

private val A_PACK_STATE_EVENT = """
    {
      "type": "$EVENT_TYPE_ROOM_EMOTES",
      "state_key": "",
      "content": {
        "pack": {"display_name": "personal", "usage": ["sticker"]},
        "images": {"cat": {"url": "mxc://server/cat"}}
      }
    }
""".trimIndent()

private val AN_EMOTE_ROOMS_EVENT = """
    {"rooms": {"${A_ROOM_ID.value}": {"": {}}}}
""".trimIndent()

class ImagePackRepositoryTest {
    private fun aRoom() = FakeBaseRoom(roomId = A_ROOM_ID).apply {
        fetchFullRoomStateLambda = { Result.success(listOf(A_PACK_STATE_EVENT)) }
    }

    @Test
    fun `getAllPacks does not list the same pack twice when the current room is also an emote room`() = runTest {
        val room = aRoom()
        val matrixClient = FakeMatrixClient().apply {
            getAccountDataLambda = { eventType ->
                AN_EMOTE_ROOMS_EVENT.takeIf { eventType == EVENT_TYPE_EMOTE_ROOMS }
            }
            givenGetRoomResult(A_ROOM_ID, aRoom())
        }
        val sut = ImagePackRepository(matrixClient)

        val result = sut.getAllPacks(room)

        assertThat(result.packs).hasSize(1)
        assertThat(result.packs.single().pack.images.keys).containsExactly("cat")
    }
}
