package com.iris.localai.data

/**
 * 3.0.0 Beta6 自定布局：主页（对话页）上可以换位置的按钮。
 *
 * 每排分「左边」「右边」两串按钮，主页就是左边一串靠左、右边一串靠右——没有空格子，
 * 编辑页看到的顺序就是主页的顺序（r2 起；r1 的 6 格模型在主页上会把空格挤掉，挪一格看不出变化）。
 * - 顶栏（[Row.TOP]）：侧栏、模型、角色、新建对话，只能在顶栏里换。
 * - 输入框上排（[Row.UPPER]）与下排（[Row.LOWER]）：智能体、技能、附件、思考、速度、语音，两排之间可互换，
 *   每排最多 [MAX_COMPOSER] 个（再多手机上排不下）。
 * 存成一行文字（AppSettings.homeLayout），空串 = 默认（Beta5 的样子）。纯逻辑，可单测。
 */
data class HomeLayout(val rows: Map<Row, Sides>) {

    enum class Group { TOP, COMPOSER }

    enum class Row(val group: Group) { TOP(Group.TOP), UPPER(Group.COMPOSER), LOWER(Group.COMPOSER) }

    data class Sides(val start: List<HomeItem> = emptyList(), val end: List<HomeItem> = emptyList()) {
        val all: List<HomeItem> get() = start + end
        fun side(end: Boolean) = if (end) this.end else start
    }

    fun start(row: Row): List<HomeItem> = rows[row]?.start.orEmpty()
    fun end(row: Row): List<HomeItem> = rows[row]?.end.orEmpty()
    fun count(row: Row): Int = start(row).size + end(row).size
    fun isEmpty(row: Row): Boolean = count(row) == 0

    /** where [item] is now: row, which side, position on that side. */
    data class Place(val row: Row, val end: Boolean, val pos: Int)

    fun placeOf(item: HomeItem): Place? {
        for ((row, s) in rows) {
            s.start.indexOf(item).takeIf { it >= 0 }?.let { return Place(row, false, it) }
            s.end.indexOf(item).takeIf { it >= 0 }?.let { return Place(row, true, it) }
        }
        return null
    }

    /**
     * 把 [item] 插到 [row] 的左/右边第 [pos] 个位置（其余按钮顺延）。
     * 不同组、目标排已满时原样返回。
     */
    fun move(item: HomeItem, row: Row, end: Boolean, pos: Int): HomeLayout {
        if (row.group != item.group) return this
        val without = rows.mapValues { (_, s) -> Sides(s.start - item, s.end - item) }
        val target = without[row] ?: Sides()
        if (target.all.size >= cap(row)) return this
        val list = target.side(end).toMutableList()
        list.add(pos.coerceIn(0, list.size), item)
        val next = without.toMutableMap()
        next[row] = if (end) target.copy(end = list) else target.copy(start = list)
        val out = HomeLayout(next)
        return if (out == this) this else out
    }

    val isDefault: Boolean get() = Row.entries.all { start(it) == DEFAULT.start(it) && end(it) == DEFAULT.end(it) }

    fun encode(): String = if (isDefault) "" else PREFIX + Row.entries.joinToString("|") { r ->
        "${r.name}:" + start(r).joinToString(".") { it.name } + "/" + end(r).joinToString(".") { it.name }
    }

    companion object {
        const val MAX_COMPOSER = 4
        private const val PREFIX = "v2|"

        fun cap(row: Row): Int = if (row.group == Group.TOP) HomeItem.entries.size else MAX_COMPOSER

        val DEFAULT = HomeLayout(mapOf(
            Row.TOP to Sides(listOf(HomeItem.MENU, HomeItem.MODEL, HomeItem.CHARACTER), listOf(HomeItem.NEW_CHAT)),
            Row.UPPER to Sides(listOf(HomeItem.AGENT, HomeItem.SKILLS), listOf(HomeItem.ATTACH)),
            Row.LOWER to Sides(listOf(HomeItem.THINK, HomeItem.SPEED), listOf(HomeItem.VOICE)),
        ))

        fun rowsOf(group: Group): List<Row> = Row.entries.filter { it.group == group }

        /**
         * 读回存的布局。旧格式（r1）或坏掉的串 → 默认；缺的按钮放回默认位置（排满就放同组别的排）；
         * 每个按钮只出现一次，不在错的组里，排不超上限。
         */
        fun parse(text: String?): HomeLayout {
            if (text.isNullOrBlank() || !text.startsWith(PREFIX)) return DEFAULT
            val seen = HashSet<HomeItem>()
            val rows = HashMap<Row, Sides>()
            for (part in text.removePrefix(PREFIX).split('|')) {
                val colon = part.indexOf(':'); if (colon <= 0) continue
                val row = Row.entries.firstOrNull { it.name == part.substring(0, colon) } ?: continue
                if (row in rows) continue
                val halves = part.substring(colon + 1).split('/')
                fun items(s: String?): MutableList<HomeItem> = s.orEmpty().split('.').mapNotNull { n ->
                    HomeItem.entries.firstOrNull { it.name == n }?.takeIf { it.group == row.group && seen.add(it) }
                }.toMutableList()
                val st = items(halves.getOrNull(0)); val en = items(halves.getOrNull(1))
                while (st.size + en.size > cap(row)) { val drop = if (en.isNotEmpty()) en.removeAt(en.lastIndex) else st.removeAt(st.lastIndex); seen.remove(drop) }
                rows[row] = Sides(st, en)
            }
            var out = HomeLayout(Row.entries.associateWith { rows[it] ?: Sides() })
            for (item in HomeItem.entries) {
                if (item in seen) continue
                val def = DEFAULT.placeOf(item)!!
                val tries = listOf(def.row) + rowsOf(item.group).filter { it != def.row }
                val row = tries.firstOrNull { out.count(it) < cap(it) } ?: return DEFAULT
                val side = if (row == def.row) def.end else false
                out = out.move(item, row, side, if (row == def.row) def.pos else Int.MAX_VALUE)
            }
            return out
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
