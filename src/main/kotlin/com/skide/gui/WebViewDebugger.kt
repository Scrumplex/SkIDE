package com.skide.gui

import com.skide.core.code.CodeArea
import com.teamdev.jxbrowser.chromium.Browser
import com.teamdev.jxbrowser.chromium.javafx.BrowserView
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import javafx.stage.StageStyle


class WebViewDebugger(val area: CodeArea) {

    val engine = area.engine
    var initialized = false
    lateinit var stage: Stage

    private fun setupStage() {
        val debugBrowser = BrowserView(Browser())
        val pane = BorderPane()
        pane.center = debugBrowser
        stage = Stage()
        stage.icons.add(Image(javaClass.getResource("/images/icon.png").toExternalForm()))
        stage.title = "Debugger"
        stage.initStyle(StageStyle.UTILITY)
        stage.scene = Scene(pane, 800.0, 600.0)


        debugBrowser.browser.loadURL(engine.remoteDebuggingURL)
    }

    fun start() {
        if (initialized) {
            stage.show()
            return
        }
        initialized = true
        Thread {
            Platform.runLater {
                setupStage()
                stage.show()
            }

        }.start()
    }
}