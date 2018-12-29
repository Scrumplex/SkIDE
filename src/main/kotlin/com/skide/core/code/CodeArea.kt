@file:Suppress("unused")

package com.skide.core.code

import com.skide.CoreManager
import com.skide.core.code.autocomplete.AutoCompleteItem
import com.skide.core.code.autocomplete.CompletionType
import com.skide.core.code.autocomplete.addSuggestionToObject
import com.skide.gui.ListViewPopUp
import com.skide.gui.WebViewDebugger
import com.skide.include.DocType
import com.skide.include.OpenFileHolder
import com.skide.utils.extensionToLang
import com.teamdev.jxbrowser.chromium.*
import com.teamdev.jxbrowser.chromium.events.*
import com.teamdev.jxbrowser.chromium.javafx.BrowserView
import javafx.application.Platform
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DataFormat
import javafx.scene.input.KeyCode
import java.io.DataInputStream
import java.io.File
import java.net.URL
import kotlin.system.measureTimeMillis


class CallbackHook(private val rdy: () -> Unit) {
    fun call() = rdy()
}

class EventHandler(private val area: CodeArea) {

    private var firstTextSet = false
    fun copy() {
        Platform.runLater {
            area.copySelectionToClipboard()
        }
    }

    fun cut() {
        Platform.runLater {
            val selection = area.getSelection()
            area.copySelectionToClipboard()
            area.replaceContentInRange(selection.startLineNumber, selection.startColumn, selection.endLineNumber, selection.endColumn, "")
        }
    }

    /**
     * removed ev
     */
    fun eventNotify(name: String) {
        if (name == "onDidChangeCursorPosition") {
            val currentLine = area.getCurrentLine()
            if (area.line != currentLine) {
                if (area.openFileHolder.codeManager.sequenceReplaceHandler.computing || area.getLineContent(currentLine).isBlank())
                    area.openFileHolder.codeManager.sequenceReplaceHandler.cancel()
                area.codeManager.parseStructure(false)
            }
            area.line = currentLine
        }
        if (name == "onDidChangeModelContent") {
            if (firstTextSet)
                area.updateWatcher.update()
            else
                firstTextSet = true
        }
    }

    fun cmdCall(key: String) {
        if (area.editorCommands.containsKey(key))
            area.editorCommands[key]!!.cb()
    }

    fun findReferences(model: Any, position: Any, context: Any): Any {
        val lineNumber = (position as JSObject).getProperty("lineNumber").asNumber().integer
        val column = position.getProperty("column").asNumber().integer
        val word = area.getWordAtPosition(lineNumber, column)
        return area.codeManager.referenceProvider.findReference(model, lineNumber, word, area.getArray())
    }

    fun gotoCall(model: Any, position: Any, token: Any): Any {
        val pos = position as JSObject
        val lineNumber = pos.getProperty("lineNumber").asNumber().integer
        val column = pos.getProperty("column").asNumber().integer

        val result = area.openFileHolder.codeManager.definitonFinder.search(lineNumber, area.getWordAtPosition(lineNumber, column))
        val obj = area.getObject()
        if (!result.success) return obj

        if (result.fName != "") {
            area.openFileHolder.openProject.project.fileManager.projectFiles.values.forEach { f ->
                if (f.name.endsWith(".sk") && f.name == result.fName && token as Boolean)
                    if (area.openFileHolder.openProject.guiHandler.openFiles.containsKey(f)) {
                        val entry = area.openFileHolder.openProject.guiHandler.openFiles[f]
                        if (entry != null) {
                            entry.tabPane.selectionModel.select(entry.tab)
                            entry.area.moveLineToCenter(result.line)
                            entry.area.setSelection(result.line, 1, result.line, entry.area.getColumnLineAmount(result.line))
                        }
                    } else {
                        area.openFileHolder.openProject.eventManager.openFile(f, false) {
                            Platform.runLater {
                                it.area.moveLineToCenter(result.line)
                                it.area.setSelection(result.line, 1, result.line, it.area.getColumnLineAmount(result.line))

                            }
                        }
                    }
            }
            return obj
        }
        obj.setProperty("range", area.createObjectFromMap(hashMapOf(
                Pair("startLineNumber", result.line),
                Pair("endLineNumber", result.line),
                Pair("startColumn", result.column),
                Pair("endColumn", result.column))))
        obj.setProperty("uri", (model as JSObject).getProperty("uri"))
        return obj
    }

    fun autoCompleteRequest(doc: Any, pos: Any, token: Any, context: Any): JSObject {
        val array = area.getArray()
        println(measureTimeMillis {
            if (area.coreManager.configManager.get("auto_complete") == "true")
                if (area.getCurrentColumn() < 3 && !area.getLineContent(area.getCurrentLine()).startsWith("\t"))
                    area.openFileHolder.codeManager.autoComplete.showGlobalAutoComplete(array)
                else
                    area.openFileHolder.codeManager.autoComplete.showLocalAutoComplete(array)
        })


        return array
    }

    fun contextMenuEmit(ev: JSObject) {
        if (((ev.getProperty("event") as JSObject).getProperty("leftButton").booleanValue) &&
                !((ev.getProperty("event") as JSObject).getProperty("rightButton").booleanValue)) return

        val selection = area.getSelection()
        if (selection.startColumn == selection.endColumn && selection.startLineNumber == selection.endLineNumber &&
                area.editorActions.containsKey("skunityReport")) {
            area.removeAction("skunityReport");
        } else if (area.coreManager.skUnity.loggedIn && !area.editorActions.containsKey("skunityReport")) {
            area.addSkUnityReportAction()
        }
    }

    fun actionFire(id: String, ev: Any) {
        if (area.editorActions.containsKey(id)) area.editorActions[id]!!.cb()
    }

    fun commandFire(id: String): JSObject {
        if (area.editorActions.containsKey(id)) area.editorActions[id]!!.cb()
        return area.getObject()
    }
}

class EditorActionBinder(val id: String, val cb: () -> Unit) {
    lateinit var instance: Any
}

class EditorCommandBinder(val id: String, val cb: () -> Unit) {
    lateinit var instance: JSObject
    fun activate() = instance.getProperty("set").asFunction().invoke(instance, true)
    fun deactivate() = instance.getProperty("set").asFunction().invoke(instance, false)
}

class CodeArea(val coreManager: CoreManager, val file: File, val rdy: (CodeArea) -> Unit) {

    var line = 1
    lateinit var view: BrowserView
    lateinit var engine: Browser
    lateinit var editor: JSObject
    lateinit var selection: JSObject
    lateinit var openFileHolder: OpenFileHolder
    lateinit var codeManager: CodeManager
    val editorActions = HashMap<String, EditorActionBinder>()
    val editorCommands = HashMap<String, EditorCommandBinder>()
    val eventHandler = EventHandler(this)
    lateinit var debugger: WebViewDebugger
    var findWidgetVisible = false
    val updateWatcher = ChangeWatcher(1000) {
        Platform.runLater {
            codeManager.parseResult = codeManager.parseStructure(true)
        }
    }

    lateinit var addonItems: JSArray
    lateinit var addonEvents: JSArray
    fun getArray() = engine.executeJavaScriptAndReturnValue("getArr();").asArray()
    fun getObject() = engine.executeJavaScriptAndReturnValue("getObj();").asObject()
    fun getFunction() = engine.executeJavaScriptAndReturnValue("getFunc();").asFunction()
    fun getWindow() = engine.executeJavaScriptAndReturnValue("window").asObject()
    fun getModel() = engine.executeJavaScriptAndReturnValue("editor.getModel()").asObject()
    private fun startEditor(options: JSValue) {
        editor = getWindow().getProperty("startEditor").asFunction().invoke(getWindow(), options).asObject()
    }

    fun copySelectionToClipboard() {

        val sel = getSelection()
        val cb = Clipboard.getSystemClipboard()
        val content = ClipboardContent()
        val str = getContentRange(sel.startLineNumber, sel.endLineNumber, sel.startColumn, sel.endColumn)
        if (str.isEmpty()) return
        content.putString(str)
        cb.setContent(content)
    }

    fun focusEditor() {
        Platform.runLater {
            if (!view.isFocused) view.requestFocus()
            editor.getProperty("focus").asFunction().invoke(editor)
        }
    }

    fun updateAddonCache() {
        var count = 0
        addonItems = getArray()
        openFileHolder.openProject.addons.values.forEach { addon ->
            addon.forEach { item ->
                if (item.type != DocType.EVENT) {
                    val name = "${item.name}:${item.type} - ${item.addon.name}"
                    var adder = (if (item.pattern == "") item.name.toLowerCase() else item.pattern).replace("\n", "")
                    //       if (item.type == DocType.CONDITION) if (!lineContent.contains("if ")) adder = "if $adder"
                    if (item.type == DocType.CONDITION) adder += ":"
                    addSuggestionToObject(AutoCompleteItem(this, name, CompletionType.SNIPPET, adder, commandId = "general_auto_complete_finish"), addonItems, count)
                    count++
                }
            }
        }
    }

    fun updateEventsCache() {
        var count = 0
        addonEvents = getArray()
        openFileHolder.openProject.addons.values.forEach {
            it.forEach { ev ->
                if (ev.type == DocType.EVENT) {
                    addonEvents.set(count, AutoCompleteItem(this, "EVENT: ${ev.name} (${ev.addon.name})", CompletionType.METHOD, {
                        var text = ev.pattern
                        if (text.isEmpty()) text = ev.name
                        //    text = text.replace("[on]", if (hasOn) "" else "on").replace("\n", "")
                        text = text.replace("[on]", "").replace("\n", "")

                        "$text:"
                    }.invoke(), commandId = "general_auto_complete_finish").createObject())
                    count++
                }
            }
        }
    }

    fun pasteSelectionFromClipboard() {

        val selection = getSelection()
        val cb = Clipboard.getSystemClipboard()
        if (cb.hasContent(DataFormat.PLAIN_TEXT)) {
            val text = cb.string
            replaceContentInRange(selection.startLineNumber, selection.startColumn, selection.endLineNumber, selection.endColumn, text)

        }
    }
    private fun getMimeType(path: String): String {
        if (path.endsWith(".html")) {
            return "text/html"
        }
        if (path.endsWith(".css")) {
            return "text/css"
        }
        return if (path.endsWith(".js")) {
            "text/javascript"
        } else "text/html"
    }
    init {

        Thread {
            engine = Browser()
            view = BrowserView(engine)
            val browserContext = engine.context
            val protocolService = browserContext.protocolService
            protocolService.setProtocolHandler("jar", ProtocolHandler { request ->
                try {
                    val response = URLResponse()
                    val path = URL(request.url)
                    val inputStream = path.openStream()
                    val stream = DataInputStream(inputStream)
                    val data = ByteArray(stream.available())
                    stream.readFully(data)
                    response.data = data
                    val mimeType = getMimeType(path.toString())
                    response.headers.setHeader("Content-Type", mimeType)
                    return@ProtocolHandler response
                } catch (ignored: Exception) {
                }

                null
            })
            view.setOnKeyPressed { ev ->

                if (ev.code == KeyCode.ESCAPE) {
                    if (openFileHolder.codeManager.isSetup && openFileHolder.codeManager.sequenceReplaceHandler.computing)
                        openFileHolder.codeManager.sequenceReplaceHandler.cancel()
                }

            }
            val me = this
            engine.addLoadListener(object : LoadAdapter() {
                override fun onStartLoadingFrame(event: StartLoadingEvent?) {

                }

                override fun onProvisionalLoadingFrame(event: ProvisionalLoadingEvent?) {

                }

                override fun onFinishLoadingFrame(event: FinishLoadingEvent?) {
                    if (event!!.isMainFrame) {
                        val win = getWindow()
                        val cbHook = CallbackHook {
                            val settings = engine.executeJavaScriptAndReturnValue("getDefaultOptions();").asObject()
                            settings.setProperty("fontSize", coreManager.configManager.get("font_size"))
                            settings.setProperty("language", extensionToLang(file.name.split(".").last()))
                            if (coreManager.configManager.get("theme") == "Dark")
                                settings.setProperty("theme", "skript-dark")
                            else
                                settings.setProperty("theme", "skript-light")
                            startEditor(settings)
                            selection = engine.executeJavaScriptAndReturnValue("selection").asObject()
                            rdy(me)
                            prepareEditorActions()
                            debugger = WebViewDebugger(me)
                            if (coreManager.configManager.get("webview_debug") == "true") debugger.start()
                            updateAddonCache()
                            updateEventsCache()
                        }
                        win.setProperty("skide", eventHandler)
                        win.setProperty("cbh", cbHook)
                        Thread {
                            Thread.sleep(260)
                            Platform.runLater {
                                engine.executeJavaScript("cbhReady();")
                            }
                        }.start()
                    }
                }

                override fun onFailLoadingFrame(event: FailLoadingEvent?) {
                }

                override fun onDocumentLoadedInFrame(event: FrameLoadEvent?) {
                }

                override fun onDocumentLoadedInMainFrame(event: LoadEvent?) {
                }
            })
            engine.loadURL(this.javaClass.getResource("/www/index.html").toString())
        }.start()

    }

    fun addSkUnityReportAction() {
        addAction("skunityReport", "Ask on skUnity") {
            val selection = getSelection()
            val content = getContentRange(selection.startLineNumber, selection.endLineNumber,
                    selection.startColumn, selection.endColumn)
            coreManager.skUnity.initer(content)
        }
    }

    private fun prepareEditorActions() {
        if (coreManager.skUnity.loggedIn) {
            addSkUnityReportAction()
        } else {
            coreManager.skUnity.addListener {
                addSkUnityReportAction()
            }
        }
        addAction("compile", "Export/Minify") {
            val openProject = openFileHolder.openProject
            val map = HashMap<String, () -> Unit>()
            for ((name, opt) in openProject.project.fileManager.compileOptions) {
                map[name] = {
                    openProject.guiHandler.openFiles.forEach { it.value.manager.saveCode() }
                    openProject.compiler.compile(openProject, opt,
                            openProject.guiHandler.lowerTabPaneEventManager.setupBuildLogTabForInput())
                }
            }
            ListViewPopUp("Compile/Minify", map)
        }
        addAction("run", "Run this File") {
            val map = HashMap<String, () -> Unit>()
            coreManager.serverManager.servers.forEach {
                map[it.value.configuration.name] = {
                    openFileHolder.openProject.run(it.value, openFileHolder)
                }
            }
            ListViewPopUp("Run this file", map)
        }
        addAction("upload", "Upload this file") {
            val map = HashMap<String, () -> Unit>()
            openFileHolder.openProject.project.fileManager.hosts.forEach {
                map[it.name] = {
                    openFileHolder.openProject.deployer.deploy(text, openFileHolder.f.name, it)
                }
            }
            ListViewPopUp("Upload this file", map)
        }


        if (!openFileHolder.isExternal) {
            addAction("runc", "Run Configuration") {
                val map = HashMap<String, () -> Unit>()

                for ((name, opt) in openFileHolder.openProject.project.fileManager.compileOptions) {
                    map[name] = {
                        val map2 = HashMap<String, () -> Unit>()
                        coreManager.serverManager.servers.forEach {
                            map2[it.value.configuration.name] = {
                                openFileHolder.openProject.guiHandler.openFiles.forEach { code -> code.value.manager.saveCode() }
                                openFileHolder.openProject.run(it.value, opt)
                            }
                        }
                        ListViewPopUp(name, map2)
                    }
                }
                ListViewPopUp("Run Configuration", map)
            }
        }


        addAction("general_auto_complete_finish") {
            openFileHolder.codeManager.sequenceReplaceHandler.compute(getCurrentLine(), getLineContent(getCurrentLine()))
        }
        addAction("create_command") {
            openFileHolder.codeManager.autoComplete.createCommand()
        }

        addCommand("sequence_replacer", 2) {
            openFileHolder.codeManager.sequenceReplaceHandler.fire()
        }

    }


    fun activateCommand(key: String) {
        if (!editorCommands.containsKey(key)) return
        editorCommands[key]!!.activate()
    }

    fun deactivateCommand(key: String) {
        if (!editorCommands.containsKey(key)) return
        editorCommands[key]!!.deactivate()
    }

    fun addCommand(key: String, keyId: Int, cb: () -> Unit) {
        if (editorCommands.containsKey(key)) return
        val window = getWindow()
        val cont = window.getProperty("addCondition").asFunction().invoke(window, key, keyId)
        val command = EditorCommandBinder(key, cb)
        command.instance = cont as JSObject
        editorCommands[key] = command
    }

    fun createObjectFromMap(fields: Map<String, Any>): JSValue {
        val obj = getObject()
        for ((key, value) in fields) obj.setProperty(key, value)
        return obj
    }

    fun triggerAction(id: String) {
        editor.getProperty("trigger").asFunction().invoke(editor, "_", id)
    }

    fun addAction(id: String, label: String, cb: () -> Unit) {
        if (editorActions.containsKey(id)) return
        val action = EditorActionBinder(id, cb)
        val window = getWindow()
        action.instance = window.getProperty("addAction").asFunction().invoke(window, id, label)
        editorActions[id] = action
    }

    fun addActionCopyPaste(id: String, label: String, cb: () -> Unit) {
        if (editorActions.containsKey(id)) return
        val action = EditorActionBinder(id, cb)
        val window = getWindow()
        action.instance = window.getProperty("addActionCopyPaste").asFunction().invoke(window, id, label)
        editorActions[id] = action
    }

    fun addAction(id: String, cb: () -> Unit) {
        if (editorActions.containsKey(id)) return
        val action = EditorActionBinder(id, cb)
        val window = getWindow()
        action.instance = window.getProperty("addCommand").asFunction().invoke(window, id)
        editorActions[id] = action
    }

    fun removeAction(id: String) {
        if (!editorActions.containsKey(id)) return
        val action = editorActions[id]
        if (action != null) {

            (action.instance as JSObject).getProperty("dispose").asFunction().invoke(action.instance as JSObject)
            editorActions.remove(id)
        }
    }

    data class Selection(val endColumn: Int, val endLineNumber: Int, val positionColumn: Int,
                         val positionLineNumber: Int, val selectionStartColumn: Int, val selectionStartLineNumber: Int,
                         val startColumn: Int, val startLineNumber: Int)

    fun getSelection(): Selection {
        val result = editor.getProperty("getSelection").asFunction().invoke(editor).asObject()
        return Selection(
                result.getProperty("endColumn").asNumber().integer,
                result.getProperty("endLineNumber").asNumber().integer,
                result.getProperty("positionColumn").asNumber().integer,
                result.getProperty("positionLineNumber").asNumber().integer,
                result.getProperty("selectionStartColumn").asNumber().integer,
                result.getProperty("selectionStartLineNumber").asNumber().integer,
                result.getProperty("startColumn").asNumber().integer,
                result.getProperty("startLineNumber").asNumber().integer)
    }

    fun getLineCount() = getModel().getProperty("getLineCount").asFunction().invoke(getModel()).asNumber().integer
    fun getColumnLineAmount(line: Int) = getModel().getProperty("getLineMaxColumn").asFunction().invoke(getModel(), line).asNumber().integer

    fun getWordAtPosition(line: Int = getCurrentLine(), column: Int = getCurrentColumn()): String {
        val model = getModel()
        return model.getProperty("getWordAtPosition").asFunction().invoke(model, createObjectFromMap(hashMapOf(Pair("lineNumber", line),
                Pair("column", column)))).asObject().getProperty("word").stringValue

    }


    data class WordAtPos(val word: String, val endColumn: Int, val startColumn: Int)

    fun getWordUntilPosition(line: Int = getCurrentLine(), pos: Int = getCurrentColumn()): WordAtPos {
        val model = getModel()
        val result = getModel().getProperty("getWordUntilPosition").asFunction().invoke(model, createObjectFromMap(hashMapOf(Pair("column", pos), Pair("lineNumber", line)))).asObject()


        return WordAtPos(result.getProperty("word").stringValue, result.getProperty("endColumn").asNumber().integer, result.getProperty("startColumn").asNumber().integer)
    }


    fun moveLineToCenter(line: Int) = editor.getProperty("revealLineInCenter").asFunction().invoke(editor, line)

    fun setPosition(line: Int, column: Int) = editor.getProperty("setPosition").asFunction().invoke(editor, createObjectFromMap(hashMapOf(Pair("column", column), Pair("lineNumber", line))))


    fun updateOptions(fields: Map<String, Any>) = editor.getProperty("updateOptions").asFunction().invoke(editor, createObjectFromMap(fields))

    fun getCurrentLine() = engine.executeJavaScriptAndReturnValue("editor.getPosition().lineNumber").asNumber().integer
    fun getCurrentColumn() = engine.executeJavaScriptAndReturnValue("editor.getPosition().column").asNumber().integer
    fun setCursorPosition(line: Int, column: Int) = setPosition(line, column)

    fun getContentRange(startLine: Int, endLine: Int, startColumn: Int, endColumn: Int): String {
        val model = getModel()
        return model.getProperty("getValueInRange").asFunction().invoke(model, createObjectFromMap(hashMapOf(Pair("endColumn", endColumn), Pair("endLineNumber", endLine),
                Pair("startColumn", startColumn), Pair("startLineNumber", startLine)))).stringValue

    }

    fun replaceContentInRange(startLine: Int, startCol: Int, endLine: Int, endColumn: Int, replace: String) {
        val selections = editor.getProperty("getSelections").asFunction().invoke(editor).asObject()
        val replaceObject = createObjectFromMap(hashMapOf(Pair("range",
                createObjectFromMap(hashMapOf(Pair("startLineNumber", startLine), Pair("startColumn", startCol), Pair("endLineNumber", endLine), Pair("endColumn", endColumn)))),
                Pair("text", replace)))
        val arr = getArray()
        arr.set(0, replaceObject)
        val model = getModel()
        model.getProperty("pushEditOperations").asFunction().invoke(model, selections, arr, getFunction())
    }

    fun replaceLine(number: Int, text: String) {
        replaceContentInRange(number, 1, number, getColumnLineAmount(number), text)
    }

    fun setSelection(startLine: Int, startCol: Int, endLine: Int, endColumn: Int) {
        editor.getProperty("setSelection").asFunction().invoke(editor, createObjectFromMap(hashMapOf(Pair("endColumn", endColumn), Pair("endLineNumber", endLine), Pair("startColumn", startCol), Pair("startLineNumber", startLine))))
    }

    fun getLineContent(line: Int): String {
        val model = getModel()
        return model.getProperty("getLineContent").asFunction().invoke(model, line).stringValue
    }

    var text: String
        set(value) {
            editor.getProperty("setValue").asFunction().invoke(editor, value)
        }
        get() {
            return editor.getProperty("getValue").asFunction().invoke(editor).stringValue
        }
}