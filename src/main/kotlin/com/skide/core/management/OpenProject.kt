package com.skide.core.management

import com.skide.CoreManager
import com.skide.core.skript.SkriptParser
import com.skide.gui.project.OpenProjectGuiManager
import com.skide.include.*
import com.skide.utils.*
import com.skide.utils.skcompiler.SkCompiler
import javafx.application.Platform
import javafx.scene.control.Button
import java.io.File
import java.rmi.Remote
import java.util.*
import kotlin.collections.HashMap

class RunningObjectGuiBinder(val reloadBtn: Button, val stopBtn: Button, val srv: RunningServerManager, var runner: Any)

class OpenProject(val project: Project, val coreManager: CoreManager) {

    private var crossNodeUpdaterBusy = false
    private val privatecrossNodes = HashMap<File, Vector<Node>>()
    val crossNodes:HashMap<File, Vector<Node>>
    get() = if(crossNodeUpdaterBusy) HashMap() else privatecrossNodes
    val guiHandler = OpenProjectGuiManager(this, coreManager)
    val eventManager = guiHandler.startGui()
    val addons = HashMap<String, Vector<AddonItem>>()
    val compiler = SkCompiler()
    val deployer = RemoteDeployer(this)
    val runConfs = HashMap<Server, RunningObjectGuiBinder>()
    init {
        updateAddons()
        if (coreManager.configManager.get("cross_auto_complete") == "true") {
            updateCrossNodes()
        }
    }
    private fun addFileToCrossFileComplete(f: File, nodes: Vector<Node>) {
        val toAdd = Vector<Node>()
        EditorUtils.filterByNodeType(NodeType.FUNCTION, nodes).forEach {
            toAdd.add(it)
        }
        EditorUtils.filterByNodeType(NodeType.SET_VAR, nodes).forEach {
            if(it.fields["visibility"] == "global") toAdd.add(it)
        }
        privatecrossNodes[f] = toAdd
    }
    fun updateCrossNodes() {
        crossNodeUpdaterBusy = true
        val openTabs = HashMap<OpenFileHolder, String>()
        guiHandler.openFiles.forEach {
            openTabs[it.value] = it.value.area.text
        }
        Thread{
            val parser = SkriptParser()

            for (projectFile in project.fileManager.projectFiles) {
                if (guiHandler.openFiles.containsKey(projectFile.value)) {
                    val handler = guiHandler.openFiles[projectFile.value]
                    addFileToCrossFileComplete(projectFile.value, parser.superParse(openTabs[handler]!!))
                } else {
                    addFileToCrossFileComplete(projectFile.value, parser.superParse(readFile(projectFile.value).second))
                }
            }
            crossNodeUpdaterBusy = false
        }.start()
    }
    fun updateAddons() {

        addons.clear()

        project.fileManager.addons.forEach {
            val addonName = it.key
            val version = Version(adjustVersion(it.value))
            coreManager.resourceManager.loadAddon(addonName)
            addons[addonName] = Vector()
            coreManager.resourceManager.addons[addonName]!!.versions.forEach { currAddonVersion ->
                val addonVersion = Version(adjustVersion(currAddonVersion.key))
                val result = version.compareTo(addonVersion)

                if (result < 0) {
                } else {
                    addons[addonName]!! += currAddonVersion.value
                }

            }
        }
        addons["Skript"] = Vector()
        addons["Skript"]!! += coreManager.resourceManager.skriptDocList
    }

    fun run(server: Server, configuration: CompileOption) {

        if (!runConfs.containsKey(server) || !runConfs[server]!!.srv.server.running) {
            runConfs.remove(server)
            val runningServer = coreManager.serverManager.getServerForRun(server) { srv ->
                compiler.compileForServer(project, configuration, File(File(File(server.configuration.folder, "plugins"), "Skript"), "scripts"), {}, {

                    if (configuration.method == CompileOptionType.PER_FILE) {
                        configuration.includedFiles.forEach {
                            srv.sendCommand("sk reload ${it.name}")
                        }
                    } else {
                        srv.sendCommand("sk reload ${project.name}.sk")

                    }
                })
            }
            val guiReturn = guiHandler.lowerTabPaneEventManager.getServerTab(runningServer)
            guiReturn.second.setOnAction {
                runningServer.sendCommand("rl")
            }
            guiReturn.third.setOnAction {
                runningServer.sendCommand("stop")
            }
            runConfs[server] = RunningObjectGuiBinder(guiReturn.second, guiReturn.third, runningServer, configuration)
            return
        }

        if (runConfs.containsKey(server) && runConfs[server]!!.runner === configuration) {
            compiler.compileForServer(project, configuration, File(File(File(server.configuration.folder, "plugins"), "Skript"), "scripts"), {}, {

                if (configuration.method == CompileOptionType.PER_FILE) {
                    configuration.includedFiles.forEach {
                        runConfs[server]!!.srv.sendCommand("sk reload ${it.name}")
                    }
                } else {
                    runConfs[server]!!.srv.sendCommand("sk reload ${project.name}.sk")

                }
            })
        } else {
            runConfs[server]!!.runner = configuration
            compiler.compileForServer(project, configuration, File(File(File(server.configuration.folder, "plugins"), "Skript"), "scripts"), {}, {
                if (configuration.method == CompileOptionType.PER_FILE) {
                    configuration.includedFiles.forEach {
                        runConfs[server]!!.srv.sendCommand("sk reload ${it.name}")
                    }
                } else {
                    runConfs[server]!!.srv.sendCommand("sk reload ${project.name}.sk")

                }
            })
        }
    }

    fun run(server: Server, file: OpenFileHolder) {

        if (!runConfs.containsKey(server) || !runConfs[server]!!.srv.server.running) {
            runConfs.remove(server)
            val runningServer = coreManager.serverManager.getServerForRun(server) {
                Platform.runLater {
                    it.setSkriptFile(file.name, file.area.text)
                }
            }
            val guiReturn = guiHandler.lowerTabPaneEventManager.getServerTab(runningServer)
            guiReturn.second.setOnAction {
                runningServer.sendCommand("rl")
            }
            guiReturn.third.setOnAction {
                runningServer.sendCommand("stop")
            }
            runConfs[server] = RunningObjectGuiBinder(guiReturn.second, guiReturn.third, runningServer, file)
            return
        }

        if (runConfs.containsKey(server) && runConfs[server]!!.runner === file) {
            runConfs[server]!!.srv.setSkriptFile(file.name, file.area.text)
        } else {
            runConfs[server]!!.runner = file
            runConfs[server]!!.srv.setSkriptFile(file.name, file.area.text)
        }

    }


    fun renameProject(name: String) {
        coreManager.configManager.alterProject(project.id, PointerHolder(project.id, name, project.folder.absolutePath))
        project.name = name
        guiHandler.window.stage.title = name
        project.fileManager.rewriteConfig()

    }

    fun changeSkriptVersion(version: String) {
        project.skriptVersion = version
        project.fileManager.rewriteConfig()
    }

    fun createNewFile(orig: String) {
        val name = if (!orig.contains(".")) "$orig.sk" else orig
        project.fileManager.addFile(name)
        eventManager.updateProjectFilesTreeView()
        eventManager.openFile(project.fileManager.projectFiles[name]!!)
    }

    fun reName(oldName: String, newName: String, path: String) {

        project.fileManager.reNameFile(oldName, newName)

        val toReplace = Vector<OpenFileHolder>()
        for ((file, holder) in guiHandler.openFiles) {
            if (file.absolutePath == path) {
                toReplace.addElement(holder)
            }
        }
        toReplace.forEach {
            guiHandler.openFiles.remove(it.f)

            it.tab.text = newName
            val holder = OpenFileHolder(this, project.fileManager.projectFiles[newName]!!, it.name, it.tab, it.tabPane, it.borderPane, it.area, coreManager, it.codeManager)
            guiHandler.openFiles.put(project.fileManager.projectFiles[newName]!!, holder)
            eventManager.registerEventsForNewFile(holder)
        }
        toReplace.clear()
        System.gc()
        eventManager.updateProjectFilesTreeView()
    }

    fun delete(f: File) {

        val toReplace = Vector<File>()
        for ((file, _) in guiHandler.openFiles) {
            if (file.absolutePath == f.absolutePath)
                toReplace.addElement(file)
        }
        toReplace.forEach {
            val value = guiHandler.openFiles.remove(it)
            value?.tabPane?.tabs?.remove(value.tab)
        }
        if (guiHandler.openFiles.size == 0) {
            guiHandler.openProject.eventManager.setupPreview()
        }
        project.fileManager.deleteFile(f.name)
        eventManager.updateProjectFilesTreeView()
        System.gc()
    }

}