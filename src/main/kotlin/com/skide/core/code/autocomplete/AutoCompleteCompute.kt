package com.skide.core.code.autocomplete

import com.skide.core.code.CodeManager
import com.skide.gui.GUIManager
import com.skide.gui.controllers.GenerateCommandController
import com.skide.include.*
import com.skide.utils.EditorUtils
import com.teamdev.jxbrowser.chromium.JSArray
import com.teamdev.jxbrowser.chromium.JSObject
import java.io.File
import java.util.*
import kotlin.collections.HashMap
import kotlin.system.measureTimeMillis

class AutoCompleteCompute(val manager: CodeManager, val project: OpenFileHolder) {

    val area = manager.area
    private val addonSupported = project.openProject.addons
    private val keyWordsGen = getKeyWords()
    private val inspectorItems = getSuppressors()
    private val fileBuffer = HashMap<File, HashMap<Int, Pair<String, JSObject>>>()
    private val localCache = HashMap<Int, Pair<String, JSObject>>()
    fun createCommand() {

        val window = GUIManager.getWindow("fxml/GenerateCommand.fxml", "Generate command", true)
        val generate = window.controller as GenerateCommandController
        generate.cancelButton.setOnAction {
            GUIManager.closeGui(window.id)
        }
        generate.createButton.setOnAction {

            val line = area.getCurrentLine()
            area.replaceContentInRange(line, 1, line, area.getColumnLineAmount(line), generate.generateString())
            GUIManager.closeGui(window.id)
        }
    }

    fun showLocalAutoComplete(array: JSArray) {


        val nodes = area.openFileHolder.codeManager.parseResult
        val currentLine = area.getCurrentLine()
        val currentColumn = area.getCurrentColumn()
        val lineContent = area.getLineContent(currentLine)
        val node = EditorUtils.getLineNode(currentLine, nodes) ?: return
        val parent = EditorUtils.getRootOf(node)
        var count = 0
        val currentWord = area.getWordUntilPosition(currentLine, currentColumn)
        val before = area.getContentRange(currentLine, currentLine, 1, currentWord.startColumn)
        val vars = EditorUtils.filterByNodeType(NodeType.SET_VAR, nodes)

        if (before.endsWith(":") && node.nodeType == NodeType.FUNCTION) {
            addonSupported.values.forEach { addon ->
                addon.forEach { item ->
                    if (item.type == DocType.TYPE) {
                        addSuggestionToObject(AutoCompleteItem(area, "${item.name}:${item.type} - ${item.addon.name}", CompletionType.CLASS, item.name), array, count)
                        count++
                    }
                }
            }

            return
        }
        val varsToAdd = HashMap<String, AutoCompleteItem>()
        val used = Vector<String>()

        //Croos file Auto-complete
        if (project.coreManager.configManager.get("cross_auto_complete") == "true") {
            for ((path, internalNodes) in manager.crossNodes) {
                if (path == project.f) continue
                internalNodes.forEach { it ->
                    if (it.nodeType == NodeType.FUNCTION && it.fields.contains("ready")) {
                        val name = it.fields["name"] as String

                        val returnType = it.fields["return"] as String
                        var paramsStr = ""
                        var insertParams = ""
                        (it.fields["params"] as Vector<*>).forEach {
                            it as MethodParameter
                            paramsStr += ",${it.name}:${it.type}"
                            insertParams += ",${it.type}"
                        }
                        if (paramsStr != "") paramsStr = paramsStr.substring(1)
                        if (insertParams != "") insertParams = insertParams.substring(1)
                        val con = "$name($paramsStr)"

                        val insert = "$name($insertParams)"
                        if (fileBuffer.containsKey(path)) {
                            val fEntry = fileBuffer[path]!!
                            if (fEntry.containsKey(it.linenumber) && fEntry[it.linenumber]!!.first == insert) {
                                array.set(count, fEntry[it.linenumber]!!.second)
                            } else {
                                val obj = AutoCompleteItem(area, con, CompletionType.FUNCTION, insert, "$returnType - ${path.name}").createObject()
                                fEntry[it.linenumber] = Pair(insert, obj)

                                array.set(count, obj)
                            }
                        } else {
                            fileBuffer[path] = HashMap()
                            val obj = AutoCompleteItem(area, con, CompletionType.FUNCTION, insert, "$returnType - ${path.name}").createObject()
                            fileBuffer[path]!![it.linenumber] = Pair(insert, obj)
                            array.set(count, obj)
                        }
                        count++
                    } else if (it.nodeType == NodeType.SET_VAR && !it.fields.containsKey("invalid")) {
                        val theVar: AutoCompleteItem? =
                                when {
                                    it.fields.contains("from_option") -> {
                                        val insertText = "{@${it.fields["name"]}}}"
                                        AutoCompleteItem(area, (it.fields["name"] as String), CompletionType.VARIABLE, insertText, "Option - ${path.name}")
                                    }
                                    it.fields["visibility"] == "global" -> {
                                        val insert = "{${it.fields["name"]}}"
                                        AutoCompleteItem(area, it.fields["name"] as String, CompletionType.VARIABLE, insert, "Global Variable - ${path.name}")
                                    }
                                    else -> null
                                }
                        if (theVar != null && !used.contains(theVar.insertText)) {
                            used.add(theVar.insertText)
                            if (fileBuffer.containsKey(path)) {
                                val fEntry = fileBuffer[path]!!
                                if (fEntry.containsKey(it.linenumber) && fEntry[it.linenumber]!!.first == theVar.insertText) {
                                    array.set(count, fEntry[it.linenumber]!!.second)
                                } else {
                                    fEntry[it.linenumber] = Pair(theVar.insertText, theVar.createObject())
                                    array.set(count, fEntry[it.linenumber]!!.second)
                                }
                            } else {
                                fileBuffer[path] = HashMap()
                                fileBuffer[path]!![it.linenumber] = Pair(theVar.insertText, theVar.createObject())
                                array.set(count, fileBuffer[path]!![it.linenumber]!!.second)
                            }
                            count++
                        }
                    }
                }
                EditorUtils.filterByNodeType(NodeType.OPTIONS, internalNodes).forEach {
                    for (child in it.childNodes)
                        if (child.getContent().isNotEmpty() && child.getContent().isNotBlank() && child.nodeType != NodeType.COMMENT) {
                            val name = child.getContent().split(":").first()
                            val word = "{@$name}"
                            if (!used.contains(word)) {
                                used.add(word)
                                val theVar = AutoCompleteItem(area, name, CompletionType.VARIABLE, word, "Option - ${path.name}")
                                if (fileBuffer.containsKey(path)) {
                                    val fEntry = fileBuffer[path]!!
                                    if (fEntry.containsKey(it.linenumber) && fEntry[it.linenumber]!!.first == theVar.insertText) {
                                        array.set(count, fEntry[it.linenumber]!!.second)
                                    } else {
                                        fEntry[it.linenumber] = Pair(theVar.insertText, theVar.createObject())
                                        array.set(count, fEntry[it.linenumber]!!.second)
                                    }
                                } else {
                                    fileBuffer[path] = HashMap()
                                    fileBuffer[path]!![it.linenumber] = Pair(theVar.insertText, theVar.createObject())
                                    array.set(count, fileBuffer[path]!![it.linenumber]!!.second)
                                }
                                count++
                            }
                        }
                }
            }
        }

        //Check parent
        if (parent.nodeType == NodeType.FUNCTION && parent.fields.contains("ready")) {
            val params = parent.fields["params"] as Vector<*>
            params.forEach {
                it as MethodParameter
                val insert = "{_" + it.name + "}"
                if (!varsToAdd.containsKey(insert))
                    varsToAdd[insert] = AutoCompleteItem(area, it.name, CompletionType.VARIABLE, insert, it.type)
            }
        }

        //Loop through this files variable nodes
        vars.forEach {
            if (it.nodeType == NodeType.SET_VAR && !it.fields.containsKey("invalid")) {
                val theVar: AutoCompleteItem? = when {
                    it.fields.contains("from_option") -> {
                        val insertText = "{@${it.fields["name"]}}"
                        AutoCompleteItem(area, (it.fields["name"] as String), CompletionType.VARIABLE, insertText, "Option")
                    }
                    it.fields["visibility"] == "global" -> {
                        val insert = "{${it.fields["name"]}}"
                        AutoCompleteItem(area, it.fields["name"] as String, CompletionType.VARIABLE, insert, "Global variable")

                    }
                    EditorUtils.getRootOf(it) == parent -> {
                        val insert = "{_${it.fields["name"]}}"
                        AutoCompleteItem(area, it.fields["name"] as String, CompletionType.VARIABLE, insert, "Local variable")
                    }
                    else -> null
                }
                if (theVar != null && !used.contains(theVar.insertText)) {

                    if (localCache.containsKey(it.linenumber) && localCache[it.linenumber]!!.first == theVar.insertText) {
                        array.set(count, localCache[it.linenumber]!!.second)
                    } else {
                        localCache[it.linenumber] = Pair(theVar.insertText, theVar.createObject())
                        array.set(count, localCache[it.linenumber]!!.second)
                    }

                    count++
                }
            }
        }

        //Add Local Options
        EditorUtils.filterByNodeType(NodeType.OPTIONS, nodes).forEach {
            for (child in it.childNodes)
                if (child.getContent().isNotEmpty() && child.getContent().isNotBlank() && child.nodeType != NodeType.COMMENT) {
                    val name = child.getContent().split(":").first()
                    val word = "{@$name}"
                    if (!varsToAdd.containsKey(word))
                        varsToAdd[word] = AutoCompleteItem(area, name, CompletionType.VARIABLE, word, "Option")
                }
        }

        //Add this files functions
        nodes.forEach {
            if (it.nodeType == NodeType.FUNCTION && it.fields.contains("ready")) {
                val name = it.fields["name"] as String
                val returnType = it.fields["return"] as String
                var paramsStr = ""
                var insertParams = ""

                (it.fields["params"] as Vector<*>).forEach { param ->
                    param as MethodParameter
                    paramsStr += ",${param.name}:${param.type}"
                    insertParams += ",${param.type}"
                }
                if (paramsStr != "") paramsStr = paramsStr.substring(1)
                if (insertParams != "") insertParams = insertParams.substring(1)
                val con = "$name($paramsStr)"
                val insert = "$name($insertParams)"
                addSuggestionToObject(AutoCompleteItem(area, con, CompletionType.FUNCTION, insert, returnType), array, count)
                count++
            }
        }


        //add generic keywords
        keyWordsGen.forEach {
            addSuggestionToObject(it, array, count)
            count++
        }

        if (parent.nodeType == NodeType.EVENT)
            if (parent.fields["event"] != null) {
                val ev = parent.fields["event"] as AddonItem
                if (ev.eventValues != "") {
                    ev.eventValues.split(",").forEach {
                        val value = it.trim()
                        addSuggestionToObject(AutoCompleteItem(area, value, CompletionType.KEYWORD, value, "event value"), array, count)
                        count++
                    }
                }
            }

        //Add all nodes in one move
        varsToAdd.values.forEach {
            addSuggestionToObject(it, array, count)
            count++
        }
        if (!manager.sequenceReplaceHandler.computing)
            if (!lineContent.contains("if "))
                for (x in 0 until area.addonItems.length()) {
                    val curr = area.addonItems.get(x).asObject()
                    val text = curr.getProperty("insertText").stringValue
                    if (text.endsWith(":") && !text.startsWith("if ")) curr.setProperty("insertText", "if $text")
                    array.set(count, curr)
                    count++
                }
            else
                for (x in 0 until area.addonItems.length()) {
                    array.set(count, area.addonItems.get(x))
                    count++
                }
        varsToAdd.clear()
        used.clear()
    }

    private fun getKeyWords(): Vector<AutoCompleteItem> {
        val vector = Vector<AutoCompleteItem>()
        arrayOf("set", "if", "stop", "loop", "return", "function", "options", "true", "false", "else", "else if", "trigger", "on", "while", "is").forEach {
            vector.add(AutoCompleteItem(area, it, CompletionType.KEYWORD, it, "Generic Keyword"))
        }
        return vector
    }

    private fun getSuppressors(): Vector<AutoCompleteItem> {
        val vector = Vector<AutoCompleteItem>()
        vector.add(AutoCompleteItem(area, "@skide:ignore-case", CompletionType.SNIPPET, "#@skide:ignore-case", "SkIDE Inspections", "Variables can ignore cases, when thats enabled, this will tell the error checker to ignore that"))
        vector.add(AutoCompleteItem(area, "@skide:ignore-missing-functions", CompletionType.SNIPPET, "#@skide:ignore-missing-functions", "SkIDE Inspections", "Ignores missing functions"))
        vector.add(AutoCompleteItem(area, "@skide:ignore-all", CompletionType.SNIPPET, "#@skide:ignore-all", "SkIDE Inspections", "Disables all inspections"))
        return vector
    }

    fun showGlobalAutoComplete(array: JSArray) {

        var count = 0
        array.set(count, AutoCompleteItem(area, "function", CompletionType.FUNCTION, "function () :: :", "Generates a function", "This will create a function").createObject())
        count++
        array.set(count, AutoCompleteItem(area, "Command", CompletionType.CONSTRUCTOR, "", "Generates a Command", "This will open a Window to create a ", commandId = "create_command").createObject())
        count++
        val hasOn = area.getLineContent(area.getCurrentLine()).startsWith("on")

        for (x in 0 until area.addonEvents.length()) {
            val current = area.addonEvents[x]
            if (!hasOn) {
                val curr = current.asObject().getProperty("insertText").stringValue
                if (!curr.startsWith("on", true)) current.asObject().setProperty("insertText", "on$curr")
            }
            array.set(count, current)
            count++
        }
        inspectorItems.forEach {
            addSuggestionToObject(it, array, count)
            count++
        }
    }
}