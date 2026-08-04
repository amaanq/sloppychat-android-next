/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.slashcommands.impl

import dev.zacsweers.metro.Inject
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.matrix.api.room.StartDMResult
import io.element.android.libraries.matrix.api.room.StateEventType
import io.element.android.libraries.matrix.api.room.startDM
import io.element.android.libraries.matrix.api.timeline.MsgType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.slashcommands.api.MessagePrefix
import io.element.android.libraries.slashcommands.api.SlashCommand
import io.element.android.libraries.slashcommands.impl.rainbow.RainbowGenerator
import io.element.android.services.toolbox.api.strings.StringProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val EVENT_TYPE_ROOM_MESSAGE = "m.room.message"
private const val EVENT_TYPE_ROOM_MEMBER = "m.room.member"
private const val EVENT_TYPE_SERVER_ACL = "m.room.server_acl"
private const val ACCOUNT_DATA_DIRECT = "m.direct"

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Inject
class CommandExecutor(
    private val matrixClient: MatrixClient,
    private val joinedRoom: JoinedRoom,
    private val rainbowGenerator: RainbowGenerator,
    private val stringProvider: StringProvider,
) {
    suspend fun proceedSendMessage(
        slashCommand: SlashCommand.SlashCommandSendMessage,
        timeline: Timeline,
    ): Result<Unit> {
        return when (slashCommand) {
            is SlashCommand.SendChatEffect -> sendChatEffect()
            is SlashCommand.SendEmote -> sendEmote(slashCommand, timeline)
            is SlashCommand.SendWithPrefix -> sendPrefixedMessage(slashCommand.prefix, slashCommand.message, timeline)
            is SlashCommand.SendPlainText -> sendPlainText(slashCommand, timeline)
            is SlashCommand.SendRainbow -> sendRainbow(slashCommand, timeline)
            is SlashCommand.SendRainbowEmote -> sendRainbowEmote(slashCommand, timeline)
            is SlashCommand.SendSpoiler -> sendSpoiler(slashCommand, timeline)
            is SlashCommand.SendNotice -> sendNotice(slashCommand)
        }
    }

    suspend fun proceedAdmin(
        slashCommand: SlashCommand.SlashCommandAdmin,
    ): Result<Unit> {
        return when (slashCommand) {
            is SlashCommand.BanUser -> banUser(slashCommand)
            is SlashCommand.ChangeAvatar -> changeAvatar()
            is SlashCommand.ChangeAvatarForRoom -> changeAvatarForRoom(slashCommand)
            is SlashCommand.ChangeDisplayName -> changeDisplayName(slashCommand)
            is SlashCommand.ChangeDisplayNameForRoom -> changeDisplayNameForRoom(slashCommand)
            is SlashCommand.ChangeRoomAvatar -> changeRoomAvatar()
            is SlashCommand.ChangeRoomName -> changeRoomName(slashCommand)
            is SlashCommand.ChangeTopic -> changeTopic(slashCommand)
            is SlashCommand.DiscardSession -> discardSession()
            is SlashCommand.IgnoreUser -> ignoreUser(slashCommand)
            is SlashCommand.Invite -> invite(slashCommand)
            is SlashCommand.JoinRoom -> joinRoom(slashCommand)
            is SlashCommand.LeaveRoom -> leaveRoom(joinedRoom)
            is SlashCommand.RemoveUser -> removeUser(slashCommand)
            is SlashCommand.SetUserPowerLevel -> setUserPowerLevel()
            is SlashCommand.UnbanUser -> unbanUser(slashCommand)
            is SlashCommand.UnignoreUser -> unignoreUser(slashCommand)
            is SlashCommand.UpgradeRoom -> upgradeRoom()
            is SlashCommand.ConvertToDm -> convertToDm()
            is SlashCommand.ConvertToRoom -> convertToRoom()
            is SlashCommand.UpdateServerAcl -> updateServerAcl(slashCommand)
        }
    }

    suspend fun startDm(userId: UserId): Result<RoomId> {
        return when (val result = matrixClient.startDM(userId, createIfDmDoesNotExist = true, isEncryped = true)) {
            is StartDMResult.Success -> Result.success(result.roomId)
            is StartDMResult.Failure -> Result.failure(result.throwable)
            StartDMResult.DmDoesNotExist -> Result.failure(IllegalStateException("DM does not exist"))
        }
    }

    private fun upgradeRoom(): Result<Unit> {
        return Result.failure(Exception("Not yet implemented"))
    }

    private suspend fun unignoreUser(slashCommand: SlashCommand.UnignoreUser): Result<Unit> {
        return matrixClient.unignoreUser(slashCommand.userId)
    }

    private suspend fun unbanUser(slashCommand: SlashCommand.UnbanUser): Result<Unit> {
        return joinedRoom.unbanUser(slashCommand.userId, slashCommand.reason)
    }

    private fun setUserPowerLevel(): Result<Unit> {
        return Result.failure(Exception("Not yet implemented"))
    }

    private suspend fun sendSpoiler(slashCommand: SlashCommand.SendSpoiler, timeline: Timeline): Result<Unit> {
        val text = "[${stringProvider.getString(R.string.common_spoiler)}](${slashCommand.message})"
        val formattedText = "<span data-mx-spoiler>${slashCommand.message}</span>"
        return timeline.sendMessage(
            body = text,
            htmlBody = formattedText,
            intentionalMentions = emptyList(),
        )
    }

    private suspend fun sendRainbowEmote(slashCommand: SlashCommand.SendRainbowEmote, timeline: Timeline): Result<Unit> {
        val message = slashCommand.message.toString()
        return timeline.sendMessage(
            body = message,
            htmlBody = rainbowGenerator.generate(message),
            msgType = MsgType.MSG_TYPE_EMOTE,
            intentionalMentions = emptyList(),
        )
    }

    private suspend fun sendRainbow(slashCommand: SlashCommand.SendRainbow, timeline: Timeline): Result<Unit> {
        val message = slashCommand.message.toString()
        return timeline.sendMessage(
            body = message,
            htmlBody = rainbowGenerator.generate(message),
            intentionalMentions = emptyList(),
        )
    }

    private suspend fun sendPlainText(slashCommand: SlashCommand.SendPlainText, timeline: Timeline): Result<Unit> {
        return timeline.sendMessage(
            body = slashCommand.message.toString(),
            htmlBody = null,
            intentionalMentions = emptyList(),
            asPlainText = true,
        )
    }

    private suspend fun sendEmote(slashCommand: SlashCommand.SendEmote, timeline: Timeline): Result<Unit> {
        val message = slashCommand.message.toString()
        return timeline.sendMessage(
            body = message,
            htmlBody = null,
            msgType = MsgType.MSG_TYPE_EMOTE,
            intentionalMentions = emptyList(),
        )
    }

    private fun sendChatEffect(): Result<Unit> {
        return Result.failure(Exception("Not yet implemented"))
    }

    private suspend fun removeUser(slashCommand: SlashCommand.RemoveUser): Result<Unit> {
        return joinedRoom.kickUser(slashCommand.userId, slashCommand.reason)
    }

    private suspend fun leaveRoom(
        room: JoinedRoom,
    ): Result<Unit> {
        return room.leave()
    }

    private suspend fun joinRoom(slashCommand: SlashCommand.JoinRoom): Result<Unit> {
        return matrixClient.joinRoomByIdOrAlias(slashCommand.roomIdOrAlias, emptyList())
            .map {}
    }

    private suspend fun invite(slashCommand: SlashCommand.Invite): Result<Unit> {
        return joinedRoom.inviteUserById(slashCommand.userId)
    }

    private suspend fun ignoreUser(slashCommand: SlashCommand.IgnoreUser): Result<Unit> {
        return matrixClient.ignoreUser(slashCommand.userId)
    }

    private fun discardSession(): Result<Unit> {
        return Result.failure(Exception("Not yet implemented"))
    }

    private suspend fun changeTopic(slashCommand: SlashCommand.ChangeTopic): Result<Unit> {
        return joinedRoom.setTopic(slashCommand.topic)
    }

    private suspend fun changeRoomName(slashCommand: SlashCommand.ChangeRoomName): Result<Unit> {
        return joinedRoom.setName(slashCommand.name)
    }

    private fun changeRoomAvatar(): Result<Unit> {
        return Result.failure(Exception("Not yet implemented"))
    }

    private suspend fun changeDisplayNameForRoom(slashCommand: SlashCommand.ChangeDisplayNameForRoom): Result<Unit> {
        return joinedRoom.setOwnMemberDisplayName(slashCommand.displayName)
    }

    private suspend fun changeDisplayName(slashCommand: SlashCommand.ChangeDisplayName): Result<Unit> {
        return matrixClient.setDisplayName(slashCommand.displayName)
    }

    private fun changeAvatar(): Result<Unit> {
        return Result.failure(Exception("Not yet implemented"))
    }

    private suspend fun changeAvatarForRoom(slashCommand: SlashCommand.ChangeAvatarForRoom): Result<Unit> {
        return runCatchingExceptions {
            val ownUserId = joinedRoom.sessionId.value
            val content = joinedRoom.ownMemberContent()
            val updated = JsonObject(content + ("avatar_url" to JsonPrimitive(slashCommand.url)))
            joinedRoom.sendRawState(EVENT_TYPE_ROOM_MEMBER, ownUserId, updated.toString()).getOrThrow()
        }.map {}
    }

    /**
     * get_state_event_raw serialises the whole event, so the content has to be unwrapped, and
     * sliding sync lazy-loads members so the state store often has no m.room.member for us
     * at all, hence the full state fetch as a fallback.
     */
    private suspend fun JoinedRoom.ownMemberContent(): JsonObject {
        val ownUserId = sessionId.value
        getRawState(EVENT_TYPE_ROOM_MEMBER, ownUserId).getOrNull()?.let {
            return lenientJson.parseToJsonElement(it).jsonObject.stateEventContent()
        }
        val fullState = fetchFullRoomState().getOrThrow()
        for (eventJson in fullState) {
            val parsed = runCatching { lenientJson.parseToJsonElement(eventJson).jsonObject }.getOrNull() ?: continue
            if (parsed["type"]?.jsonPrimitive?.content != EVENT_TYPE_ROOM_MEMBER) continue
            if (parsed["state_key"]?.jsonPrimitive?.content != ownUserId) continue
            parsed["content"]?.jsonObject?.let { return it }
        }
        error("No m.room.member event found for $ownUserId in ${roomId.value}")
    }

    private suspend fun sendNotice(slashCommand: SlashCommand.SendNotice): Result<Unit> {
        val content = buildJsonObject {
            put("msgtype", "m.notice")
            put("body", slashCommand.message)
        }
        return joinedRoom.sendRaw(EVENT_TYPE_ROOM_MESSAGE, content.toString())
    }

    private suspend fun convertToDm(): Result<Unit> {
        return runCatchingExceptions {
            val members = joinedRoom.getMembers(limit = 10).getOrThrow()
            val otherMember = members
                .filter { it.membership == RoomMembershipState.JOIN && it.userId != joinedRoom.sessionId }
                .singleOrNull()
                ?: error(stringProvider.getString(R.string.slash_command_convert_to_dm_ambiguous))
            val roomId = joinedRoom.roomId.value
            val directs = readDirectMap()
            val updated = directs.mapValues { (userId, roomIds) ->
                if (userId == otherMember.userId.value) roomIds else roomIds.withoutRoomId(roomId)
            }.toMutableMap()
            val targetRooms = updated[otherMember.userId.value] ?: JsonArray(emptyList())
            if (targetRooms.none { (it as? JsonPrimitive)?.contentOrNull == roomId }) {
                updated[otherMember.userId.value] = JsonArray(targetRooms + JsonPrimitive(roomId))
            }
            matrixClient.setAccountData(ACCOUNT_DATA_DIRECT, JsonObject(updated).toString()).getOrThrow()
        }
    }

    private suspend fun convertToRoom(): Result<Unit> {
        return runCatchingExceptions {
            val roomId = joinedRoom.roomId.value
            val updated = readDirectMap().mapValues { (_, roomIds) -> roomIds.withoutRoomId(roomId) }
            matrixClient.setAccountData(ACCOUNT_DATA_DIRECT, JsonObject(updated).toString()).getOrThrow()
        }
    }

    private suspend fun readDirectMap(): Map<String, JsonArray> {
        val json = matrixClient.getAccountData(ACCOUNT_DATA_DIRECT) ?: return emptyMap()
        return lenientJson.parseToJsonElement(json).jsonObject
            .mapValues { (_, value) -> value as? JsonArray ?: JsonArray(emptyList()) }
    }

    private suspend fun updateServerAcl(slashCommand: SlashCommand.UpdateServerAcl): Result<Unit> {
        return runCatchingExceptions {
            val canSendState = joinedRoom.roomPermissions()
                .map { it.canUserSendState(joinedRoom.sessionId, StateEventType.RoomServerAcl) }
                .getOrThrow()
            if (!canSendState) {
                error(stringProvider.getString(R.string.slash_command_acl_no_permission))
            }
            val existing = joinedRoom.getRawState(EVENT_TYPE_SERVER_ACL, "").getOrThrow()
                ?.let { lenientJson.parseToJsonElement(it).jsonObject.stateEventContent() }
            val allow = (existing.stringList("allow") + slashCommand.allow)
                .distinct()
                .filterNot { it in slashCommand.removeAllow }
                .sorted()
                .ifEmpty { listOf("*") }
            val deny = (existing.stringList("deny") + slashCommand.deny)
                .distinct()
                .filterNot { it in slashCommand.removeDeny }
                .sorted()
            val content = buildJsonObject {
                putJsonArray("allow") { allow.forEach { add(it) } }
                putJsonArray("deny") { deny.forEach { add(it) } }
                existing?.get("allow_ip_literals")?.let { put("allow_ip_literals", it) }
            }
            joinedRoom.sendRawState(EVENT_TYPE_SERVER_ACL, "", content.toString()).getOrThrow()
        }.map {}
    }

    private suspend fun banUser(slashCommand: SlashCommand.BanUser): Result<Unit> {
        return joinedRoom.banUser(slashCommand.userId, slashCommand.reason)
    }

    private suspend fun sendPrefixedMessage(
        prefix: MessagePrefix,
        message: CharSequence,
        timeline: Timeline,
    ): Result<Unit> {
        val sequence = buildString {
            append(prefix.toMarkdown())
            if (message.isNotEmpty()) {
                append(" ")
                append(message)
            }
        }
        return timeline.sendMessage(
            body = sequence,
            htmlBody = null,
            intentionalMentions = emptyList(),
        )
    }
}

private fun MessagePrefix.toMarkdown() = when (this) {
    MessagePrefix.Shrug -> "¯\\\\_(ツ)\\_/¯"
    MessagePrefix.TableFlip -> "(╯°□°）╯︵ ┻━┻"
    MessagePrefix.Unflip -> "┬──┬ ノ( ゜-゜ノ)"
    MessagePrefix.Lenny -> "( ͡° ͜ʖ ͡°)"
}

private fun JsonArray.withoutRoomId(roomId: String): JsonArray =
    JsonArray(filterNot { (it as? JsonPrimitive)?.contentOrNull == roomId })

private fun JsonObject?.stringList(key: String): List<String> =
    (this?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

private fun JsonObject.stateEventContent(): JsonObject = this["content"]?.jsonObject ?: this
