package com.skide.utils.skcompiler

import com.skide.core.management.OpenProject
import com.skide.core.skript.SkriptParser
import com.skide.include.CompileOption
import com.skide.include.CompileOptionType
import com.skide.include.Node
import com.skide.include.NodeType
import com.skide.utils.readFile
import com.skide.utils.writeFile
import java.io.File
import java.util.*


class SkCompiler {

    lateinit var parser: SkriptParser

    private fun isValid(it: Node, opts: CompileOption): Boolean {
        if (it.nodeType == NodeType.COMMENT && opts.remComments) {
            return false
        }
        if (it.nodeType == NodeType.UNDEFINED && opts.remEmptyLines) {
            return false
        }
        return true
    }

    fun compile(openProject: OpenProject, opts: CompileOption, caller: (String) -> Unit) {
        if (!this::parser.isInitialized) parser = SkriptParser(openProject)
        val project = openProject.project
        Thread {
            caller("Starting compile process...")

            val optimised = HashMap<File, Vector<Node>>()

            opts.includedFiles.forEach {
                caller("Parsing file ${it.absolutePath}")
                val result = parser.superParse(readFile(it).second)
                caller("Optimizing file ${it.absolutePath}")
                val filtered = Vector<Node>()
                for (node in result) {
                    if (!isValid(node, opts)) continue


                    val toRemove = Vector<Node>()
                    node.childNodes.forEach { child ->
                        getToRemove(child, opts)
                    }
                    node.childNodes.forEach {
                        if (!isValid(it, opts)) toRemove.add(it)
                    }
                    toRemove.forEach {
                        node.childNodes.remove(it)
                    }
                    filtered.add(node)
                }
                optimised[it] = filtered
            }


            if (opts.method == CompileOptionType.CONCATENATE) {

                var out = ""
                optimised.values.forEach { arr ->

                    for (node in arr) {
                        out += computeString(node)
                    }
                }
                val file = File(opts.outputDir, project.name + ".sk")
                caller("Writing file ${file.absolutePath}")
                if (out.isNotEmpty())
                    writeFile(out.substring(1).toByteArray(), file, false, true)
                else
                    caller("can´t write empty fine.")
                caller("Finished")
            }
            if (opts.method == CompileOptionType.PER_FILE) {

                optimised.forEach { arr ->
                    val file = File(opts.outputDir, arr.key.name)
                    var out = ""
                    arr.value.forEach { out += computeString(it) }
                    caller("Writing file ${file.absolutePath}")
                    writeFile(out.substring(1).toByteArray(), file, false, true)
                }
                caller("Finished")
            }
        }.start()
    }

    fun compile(openProject: OpenProject, opts: CompileOption, caller: (String) -> Unit, returner: (String) -> Unit) {
        if (!this::parser.isInitialized) parser = SkriptParser(openProject)


        Thread {
            caller("Starting compile process...")

            val optimised = HashMap<File, Vector<Node>>()

            opts.includedFiles.forEach {
                caller("Parsing file ${it.absolutePath}")
                val result = parser.superParse(readFile(it).second)
                caller("Optimizing file ${it.absolutePath}")
                val filtered = Vector<Node>()
                for (node in result) {
                    if (!isValid(node, opts)) continue


                    val toRemove = Vector<Node>()
                    node.childNodes.forEach { child ->
                        getToRemove(child, opts)
                    }
                    node.childNodes.forEach {
                        if (!isValid(it, opts)) toRemove.add(it)
                    }
                    toRemove.forEach {
                        node.childNodes.remove(it)
                    }
                    filtered.add(node)
                }
                optimised[it] = filtered
            }


            if (opts.method == CompileOptionType.CONCATENATE) {

                var out = ""
                optimised.values.forEach { arr ->

                    for (node in arr) {
                        out += computeString(node)
                    }
                }
                returner(if (out.isEmpty()) "" else out.substring(1))
                caller("Finished")
            }

        }.start()
    }

    fun compileForServer(openProject: OpenProject, opts: CompileOption, skFolder: File, caller: (String) -> Unit, finished: () -> Unit) {
        if (!this::parser.isInitialized) parser = SkriptParser(openProject)

        if (!skFolder.exists()) {
            skFolder.mkdirs()
        }
        Thread {
            caller("Starting compile process...")

            val optimised = HashMap<File, Vector<Node>>()

            opts.includedFiles.forEach {
                caller("Parsing file ${it.absolutePath}")
                val result = parser.superParse(readFile(it).second)
                caller("Optimizing file ${it.absolutePath}")
                val filtered = Vector<Node>()
                for (node in result) {
                    if (!isValid(node, opts)) continue


                    val toRemove = Vector<Node>()
                    node.childNodes.forEach { child ->
                        getToRemove(child, opts)
                    }
                    node.childNodes.forEach {
                        if (!isValid(it, opts)) toRemove.add(it)
                    }
                    toRemove.forEach {
                        node.childNodes.remove(it)
                    }
                    filtered.add(node)
                }
                optimised[it] = filtered
            }


            if (opts.method == CompileOptionType.CONCATENATE) {

                var out = ""
                optimised.values.forEach { arr ->

                    for (node in arr) {
                        out += computeString(node)
                    }
                }
                val file = File(skFolder, openProject.project.name + ".sk")
                caller("Writing file ${file.absolutePath}")
                writeFile((if (out.isEmpty()) "" else out.substring(1)).toByteArray(), file, false, true)
                caller("Finished")
                finished()
            }
            if (opts.method == CompileOptionType.PER_FILE) {

                optimised.forEach { arr ->
                    val file = File(skFolder, arr.key.name)
                    var out = ""
                    arr.value.forEach { out += computeString(it) }
                    caller("Writing file ${file.absolutePath}")
                    writeFile((if (out.isEmpty()) "" else out.substring(1)).toByteArray(), file, false, true)
                }
                caller("Finished")
                finished()
            }
        }.start()
    }

    private fun computeString(node: Node): String {

        var str = ""
        str += node.raw

        node.childNodes.forEach {
            str += computeString(it)
        }

        return "\n" + str
    }

    private fun getToRemove(node: Node, opts: CompileOption): Boolean {
        val toRemove = Vector<Node>()
        node.childNodes.forEach {
            if (getToRemove(it, opts)) {
                toRemove.addElement(it)
            }
        }
        toRemove.forEach {
            node.childNodes.remove(it)
        }
        if (!isValid(node, opts)) return true

        return false
    }
}