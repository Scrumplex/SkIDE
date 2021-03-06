package com.skide.include

import com.skide.utils.EventRequirement
import java.util.*
import kotlin.collections.HashMap

enum class DocType {
    EVENT,
    CONDITION,
    EFFECTS,
    EXPRESSION,
    TYPE
}

class Addon(val id: Long, val name: String, val author: String, val versions: HashMap<String, Vector<AddonItem>> = HashMap(), var loaded: Boolean = false) {
    override fun toString() = name
}

data class AddonItem(val id: Int,
                     val name: String,
                     val type: DocType,
                     val addon: Addon,
                     val pattern: String = "",
                     val description: String = "",
                     val eventValues: String = "",
                     val returnType: String = "",
                     val plugins: Vector<String> = Vector()) {

    lateinit var requirements: EventRequirement
}
