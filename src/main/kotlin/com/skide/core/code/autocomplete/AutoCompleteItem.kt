package com.skide.core.code.autocomplete

import com.skide.core.code.CodeArea
import com.teamdev.jxbrowser.chromium.JSArray
import com.teamdev.jxbrowser.chromium.JSObject

enum class CompletionType(val num:Int) {

    CLASS(6),
    COLOR(15),
    CONSTRUCTOR(3),
    ENUM(12),
    FIELD(4),
    FILE(16),
    FOLDER(18),
    FUNCTION(2),
    INTERFACE(7),
    KEYWORD(13),
    METHOD(1),
    MODULE(8),
    PROPERTY(9),
    REFERENCE(17),
    SNIPPET(14),
    TEXT(0),
    UNIT(10),
    VALUE(11),
    VARIABLE(5)

}
fun addSuggestionToObject(sugg:AutoCompleteItem, target: JSArray, index:Int) {
    target.setProperty(index, sugg.createObject())
}
class AutoCompleteItem(val area:CodeArea, val label:String, val kind:CompletionType, val insertText:String, val detail:String = "", val documentation:String = "", val commandId:String = "") {


    fun createObject(obj:JSObject = area.getObject()): JSObject {

        obj.setProperty("kind", kind.num)
        obj.setProperty("label", label)
        obj.setProperty("insertText", insertText)
        obj.setProperty("detail", detail)
        obj.setProperty("documentation", documentation)

        if(commandId != "") {
           obj.setProperty("command",  area.createObjectFromMap(hashMapOf(Pair("title", ""), Pair("id", commandId))))
        }

        return obj
    }

}