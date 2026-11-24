package com.iris.localai.data

/**
 * 3.0.0 Beta6 自定布局：主页（对话页）上可以换位置的按钮，以及放它们的格子。
 *
 * 三排格子，每排 [SLOTS] 格：前 [SIDE] 格靠左排，后 [SIDE] 格靠右排。
 * - 顶栏（[Row.TOP]）：侧栏、模型、角色、新建对话，只能在顶栏里换位置。
 * - 输入框上排（[Row.UPPER]）与下排（[Row.LOWER]）：智能体、技能、附件「+」、思考、速度、语音，
 *   两排之间可以互换。
 * 默认就是 Beta5 的样子。存成一行文字（AppSettings.homeLayout），空串 = 默认。纯逻辑，可单测。
 */
data class HomeLayout(val slots: Map<HomeItem, Slot>) {

    enum class Group { TOP, COMPOSER }

    enum class Row(val group: Group) { TOP(Group.TOP), UPPER(Group.COMPOSER), LOWER(Group.COMPOSER) }

    data class Slot(val row: Row, val index: Int) {
        val end: Boolean get() = index >= SIDE
    }

    /** item at a slot, or null. */
    fun at(row: Row, index: Int): HomeItem? = slots.entries.firstOrNull { it.value.row == row && it.value.index == index }?.key

    fun slotOf(item: HomeItem): Slot = slots[item] ?: DEFAULT_SLOTS.getValue(item)

    /** items placed on the left / right side of [row], left to right. */
    fun start(row: Row): List<HomeItem> = side(row, false)
    fun end(row: Row): List<HomeItem> = side(row, true)
    fun isEmpty(row: Row): Boolean = slots.values.none { it.row == row }

    private fun side(row: Row, end: Boolean): List<HomeItem> =
        slots.entries.filter { it.value.row == row && it.value.end == end }.sortedBy { it.value.index }.map { it.key }

    /**
     * 把 [item] 放到 [target]：格子里已有别的按钮就互换位置。目标排不属于同一组时不动。
     */
    fun move(item: HomeItem, target: Slot): HomeLayout {
        if (target.row.group != item.group || target.index !in 0 until SLOTS) return this
        val from = slotOf(item)
        if (from == target) return this
        val other = at(target.row, target.index)
        val next = slots.toMutableMap()
        next[item] = target
        if (other != null) next[other] = from
        return HomeLayout(next)
    }

    val isDefault: Boolean get() = slots == DEFAULT_SLOTS

    fun encode(): String = if (isDefault) "" else
        HomeItem.entries.joinToString(",") { val s = slotOf(it); "${it.name}@${s.row.name}:${s.index}" }

    companion object {
        const val SLOTS = 6
        const val SIDE = 3

        val DEFAULT_SLOTS: Map<HomeItem, Slot> = mapOf(
            HomeItem.MENU to Slot(Row.TOP, 0),
            HomeItem.MODEL to Slot(Row.TOP, 1),
            HomeItem.CHARACTER to Slot(Row.TOP, 2),
            HomeItem.NEW_CHAT to Slot(Row.TOP, 5),
            HomeItem.AGENT to Slot(Row.UPPER, 0),
            HomeItem.SKILLS to Slot(Row.UPPER, 1),
            HomeItem.ATTACH to Slot(Row.UPPER, 5),
            HomeItem.THINK to Slot(Row.LOWER, 0),
            HomeItem.SPEED to Slot(Row.LOWER, 1),
            HomeItem.VOICE to Slot(Row.LOWER, 5),
        )

        val DEFAULT = HomeLayout(DEFAULT_SLOTS)

        fun rowsOf(group: Group): List<Row> = Row.entries.filter { it.group == group }

        /**
         * 读回存的布局。坏掉或缺的项：放回默认格子；默认格子被占了，就放进同组第一个空格。
         * 一个格子只放一个按钮，一个按钮只出现一次。
         */
        fun parse(text: String?): HomeLayout {
            if (text.isNullOrBlank()) return DEFAULT
            val placed = LinkedHashMap<HomeItem, Slot>()
            for (part in text.split(',')) {
                val at = part.indexOf('@'); val colon = part.lastIndexOf(':')
                if (at <= 0 || colon <= at) continue
                val item = HomeItem.entries.firstOrNull { it.name == part.substring(0, at).trim() } ?: continue
                val row = Row.entries.firstOrNull { it.name == part.substring(at + 1, colon).trim() } ?: continue
                val index = part.substring(colon + 1).trim().toIntOrNull() ?: continue
                if (item in placed || row.group != item.group || index !in 0 until SLOTS) continue
                val slot = Slot(row, index)
                if (slot in placed.values) continue
                placed[item] = slot
            }
            for (item in HomeItem.entries) {
                if (item in placed) continue
                val def = DEFAULT_SLOTS.getValue(item)
                val slot = if (def !in placed.values) def else
                    rowsOf(item.group).flatMap { r -> (0 until SLOTS).map { Slot(r, it) } }.firstOrNull { it !in placed.values }
                        ?: return DEFAULT
                placed[item] = slot
            }
            return HomeLayout(placed)
        }
    }
}

/** 主页上能换位置的按钮。 */
enum class HomeItem(val group: HomeLayout.Group) {
    MENU(HomeLayout.Group.TOP),
    MODEL(HomeLayout.Group.TOP),
    CHARACTER(HomeLayout.Group.TOP),
    NEW_CHAT(HomeLayout.Group.TOP),
    AGENT(HomeLayout.Group.COMPOSER),
    SKILLS(HomeLayout.Group.COMPOSER),
    ATTACH(HomeLayout.Group.COMPOSER),
    THINK(HomeLayout.Group.COMPOSER),
    SPEED(HomeLayout.Group.COMPOSER),
    VOICE(HomeLayout.Group.COMPOSER),
}
