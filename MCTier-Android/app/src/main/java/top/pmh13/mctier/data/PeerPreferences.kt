package top.pmh13.mctier.data

import kotlinx.serialization.Serializable

@Serializable
data class PeerPreference(val pinned: Boolean = false, val muted: Boolean = false, val markedUnread: Boolean = false)

fun updatePeerPreference(preferences: Map<String, PeerPreference>, id: String, value: PeerPreference): Map<String, PeerPreference> {
    require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid peer identity" }
    return if (value == PeerPreference()) preferences - id else preferences + (id to value)
}

fun <T> sortPrivatePeers(peers: List<T>, preferences: Map<String, PeerPreference>, id: (T) -> String): List<T> =
    peers.sortedByDescending { preferences[id(it)]?.pinned == true }

fun notificationUnreadCount(unread: Map<String, String>, preferences: Map<String, PeerPreference>, visibleIds: List<String>? = null): Int =
    unread.values.count { !it.startsWith("private:") || preferences[it.removePrefix("private:")]?.muted != true } +
        preferences.count { (id, p) -> (visibleIds == null || id in visibleIds) && p.markedUnread && !p.muted && "private:$id" !in unread.values }
