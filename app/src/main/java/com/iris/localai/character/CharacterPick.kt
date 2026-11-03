package com.iris.localai.character

/** 2.3.3: what picking a card (or 「无角色」) in the top-bar 角色 dropdown does. Pure, unit-tested. */
object CharacterPick {
    enum class Action { NONE, NEW_PLAIN, APPLY_IN_PLACE, NEW_WITH_CARD }

    /**
     * [current] = card of the chat on screen, [picked] = chosen card (null = 「无角色」),
     * [hasUserMessages] = the chat already has a user turn (an opening greeting alone does not count).
     */
    fun decide(current: String?, picked: String?, hasUserMessages: Boolean): Action = when {
        picked == current -> Action.NONE
        picked == null -> Action.NEW_PLAIN
        !hasUserMessages -> Action.APPLY_IN_PLACE
        else -> Action.NEW_WITH_CARD
    }

    /** Room (dp) for the model + 角色 pills below which the 角色 pill shows only the avatar. */
    const val COMPACT_BELOW_DP = 250

    /** Avatar-only when a card is active and the pill row is narrower than [COMPACT_BELOW_DP]. */
    fun compact(rowWidthDp: Int, hasCard: Boolean): Boolean = hasCard && rowWidthDp < COMPACT_BELOW_DP
}
