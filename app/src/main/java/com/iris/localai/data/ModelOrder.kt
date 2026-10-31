package com.iris.localai.data

/** 2.2.2: models in the user's saved order; ones never ordered keep their name order at the end. */
object ModelOrder {
    fun <T : Any> apply(list: List<T>, order: List<String>, key: (T) -> String): List<T> {
        if (order.isEmpty()) return list
        val rank = order.withIndex().associate { it.value to it.index }
        return list.withIndex().sortedWith(compareBy({ rank[key(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    fun apply(list: List<LocalModel>, order: List<String>): List<LocalModel> = apply(list, order) { it.fileName }
}
