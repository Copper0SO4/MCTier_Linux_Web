package top.pmh13.mctier.data

fun lobbyMutesWithoutHost(muted: Set<String>, hostId: String?): Set<String> =
    if (hostId == null) muted else muted - hostId

fun isMutedByLobbyHost(playerId: String, hostId: String?, muted: Set<String>): Boolean =
    playerId != hostId && playerId in muted
