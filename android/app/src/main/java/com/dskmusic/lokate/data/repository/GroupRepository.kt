package com.dskmusic.lokate.data.repository

import com.dskmusic.lokate.data.remote.ApiService
import com.dskmusic.lokate.data.remote.dto.GroupCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.GroupDto
import com.dskmusic.lokate.data.remote.dto.GroupJoinRequestDto
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.remote.dto.GroupSwitchRequestDto
import com.dskmusic.lokate.data.remote.dto.GroupVisibilityRequestDto
import com.dskmusic.lokate.data.remote.dto.TestNotificationRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class GroupRepository(private val api: ApiService) {

    suspend fun createGroup(name: String): GroupDto = withContext(Dispatchers.IO) {
        api.createGroup(GroupCreateRequestDto(name))
    }

    suspend fun joinGroup(inviteCode: String): GroupDto = withContext(Dispatchers.IO) {
        api.joinGroup(GroupJoinRequestDto(inviteCode))
    }

    /** Solo admin: saltar a otro grupo sin código de invitación. */
    suspend fun switchGroup(groupId: String): GroupDto = withContext(Dispatchers.IO) {
        api.switchGroup(GroupSwitchRequestDto(groupId))
    }

    /** Solo admin: aparecer o no en un grupo. Devuelve los grupos en los que sigue escondido. */
    suspend fun setGroupVisibility(groupId: String, visible: Boolean): List<String> =
        withContext(Dispatchers.IO) {
            api.setGroupVisibility(GroupVisibilityRequestDto(groupId, visible)).hidden_groups
        }

    suspend fun myGroup(): GroupDto = withContext(Dispatchers.IO) { api.myGroup() }

    /** null si el usuario aún no pertenece a ningún grupo (backend responde 400), en vez de lanzar. */
    suspend fun myGroupOrNull(): GroupDto? = withContext(Dispatchers.IO) {
        try {
            api.myGroup()
        } catch (e: HttpException) {
            if (e.code() == 400) null else throw e
        }
    }

    suspend fun members(): List<GroupMemberDto> = withContext(Dispatchers.IO) { api.groupMembers() }

    suspend fun leaveGroup() = withContext(Dispatchers.IO) { api.leaveGroup() }

    /** userIds null = a todo el grupo. */
    suspend fun sendTestNotification(userIds: List<String>? = null) = withContext(Dispatchers.IO) {
        api.sendTestNotification(TestNotificationRequestDto(userIds))
    }
}
