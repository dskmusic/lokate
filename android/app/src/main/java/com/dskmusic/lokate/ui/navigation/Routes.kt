package com.dskmusic.lokate.ui.navigation

object Routes {
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val GROUP = "group"
    const val MAP = "map"
    const val PEOPLE = "people"
    const val ZONES = "zones"
    const val ZONE_EDIT = "zone_edit"
    const val ZONE_EDIT_WITH_ID = "zone_edit/{zoneId}"
    const val HISTORY = "history?userId={userId}&displayName={displayName}"
    const val SETTINGS = "settings"
    const val OFFLINE_MAPS = "offline_maps"
    const val ADMIN = "admin"
    const val MEMBER_DETAIL = "member/{userId}"
    const val EMERGENCY_MESSAGE =
        "emergency_message?sender={sender}&text={text}&attachmentUrl={attachmentUrl}&attachmentKind={attachmentKind}"

    fun history(userId: String? = null, displayName: String? = null): String {
        val encodedName = displayName?.let { java.net.URLEncoder.encode(it, "UTF-8") }
        return "history?userId=${userId.orEmpty()}&displayName=${encodedName.orEmpty()}"
    }

    fun memberDetail(userId: String): String = "member/$userId"

    fun emergencyMessage(sender: String, text: String, attachmentUrl: String?, attachmentKind: String?): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "emergency_message?sender=${enc(sender)}&text=${enc(text)}" +
            "&attachmentUrl=${enc(attachmentUrl.orEmpty())}&attachmentKind=${enc(attachmentKind.orEmpty())}"
    }
}
