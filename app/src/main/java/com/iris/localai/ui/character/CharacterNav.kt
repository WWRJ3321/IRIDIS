package com.iris.localai.ui.character

/**
 * One-shot hand-off from the chat's 角色 menu 「编辑当前角色」 to [CharactersPage]: the page opens
 * straight in the editor for this card and returns to the chat after 保存 / back.
 */
object CharacterNav {
    @Volatile var editId: String? = null

    /** reads and clears [editId]. */
    fun take(): String? = editId.also { editId = null }
}
