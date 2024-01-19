package com.github.dineug.erdeditorintellijplugin.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAndWriteAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.io.IOException
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class ErdEditor(
        private val project: Project,
        private val file: VirtualFile,
        private val docToEditorsMap: HashMap<VirtualFile, HashSet<ErdEditor>>
) : UserDataHolderBase(),
        FileEditor,
        EditorColorsListener,
        DumbAware {

    private var isDisposed: Boolean = false
    private val logger = thisLogger()

    override fun getFile() = file

    lateinit var webviewPanel: WebviewPanel
    private val jcefUnsupported by lazy { JCEFUnsupportedViewPanel() }
    private val toolbarAndWebView: JPanel
    private val bridge = WebviewBridge()
    private val payload = MutableStateFlow<String?>(null)

    private val coroutineScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + CoroutineName("${this::class.java.simpleName}:${file.name}"))

    init {
        val busConnection = ApplicationManager.getApplication().messageBus.connect(this)

        initViewIfSupported().also {
            toolbarAndWebView = object : JPanel(BorderLayout()) {
                init {
                    when {
                        this@ErdEditor::webviewPanel.isInitialized -> {
                            add(webviewPanel.component, BorderLayout.CENTER)
                        }

                        else -> add(jcefUnsupported, BorderLayout.CENTER)
                    }
                }
            }
        }
    }

    private fun initViewIfSupported() {
        if (WebviewPanel.isSupported) {
            bridge.subscribe(coroutineScope) { action ->
                when (action) {
                    is VscodeBridgeAction.VscodeInitial -> {
                        val value = file.inputStream.reader().readText()
                        webviewPanel.dispatch(
                            WebviewBridgeAction.WebviewInitialValue(
                                WebviewInitialValuePayload(value)
                            )
                        )
                    }

                    is VscodeBridgeAction.VscodeSaveValue -> {
                        payload.value = action.payload.value
                    }

                    is VscodeBridgeAction.VscodeSaveReplication -> {
                        webviewPanel.dispatchBroadcast(
                            WebviewBridgeAction.WebviewReplication(
                                WebviewReplicationPayload(
                                    action.payload.actions
                                )
                            )
                        )
                    }

                    is VscodeBridgeAction.VscodeImportFile -> {
                        /**
                         * TODO
                         * java.lang.Throwable: AWT events are not allowed inside write action: java.awt.event.WindowEvent[WINDOW_OPENED,opposite=null,oldState=0,newState=0] on filedlg0
                         */
//                        val type = action.payload.type
//                        val extensions = action.payload.accept.split(",")
//                            .map { it.substringAfterLast(".", "") }
//                            .toTypedArray()
//                        val descriptor = FileSaverDescriptor(
//                            "Import $type To",
//                            "Choose the $type destination",
//                            *extensions
//                        )
//                        val fc = FileChooserFactory.getInstance().createFileChooser(descriptor, null, null)
//
//                        readAndWriteAction {
//                            writeAction {
//                                fc.choose(null).also { files ->
//                                    val file = files.first()
//                                    val value = file.inputStream.reader().readText()
//                                    webviewPanel.dispatch(
//                                        WebviewBridgeAction.WebviewImportFile(
//                                            WebviewImportFilePayload(type, value)
//                                        )
//                                    )
//                                }
//                            }
//                        }
                    }

                    is VscodeBridgeAction.VscodeExportFile -> {
                        val byteArray = Base64.getDecoder().decode(action.payload.value)
                        val extension = action.payload.fileName.substringAfterLast(".", "")
                        val descriptor = FileSaverDescriptor(
                            "Export $extension To",
                            "Choose the $extension destination",
                            extension
                        )
                        FileChooserFactory.getInstance()
                            .createSaveFileDialog(descriptor, null)
                            .save(file.parent, action.payload.fileName)?.also { destination ->
                                readAndWriteAction {
                                    writeAction {
                                        val file = destination.getVirtualFile(true)!!
                                        try {
                                            file.getOutputStream(file).use { stream ->
                                                with(stream) {
                                                    write(byteArray)
                                                }
                                            }
                                        } catch (e: IOException) {
                                            // TODO: notifyAboutWriteError
                                        } catch (e: IllegalArgumentException) {
                                            // TODO: notifyAboutWriteError
                                        }
                                    }
                                }
                            }
                    }

                    is VscodeBridgeAction.VscodeSaveTheme -> {}
                }
            }

            webviewPanel = WebviewPanel(
                this,
                coroutineScope,
                bridge,
                file,
                docToEditorsMap
            )
            launchSaveJob()
        }
    }

    private fun launchSaveJob() = coroutineScope.launch {
        payload
            .debounce(150.milliseconds)
            .filterNotNull()
            .collectLatest { value ->
                if (isDisposed) {
                    return@collectLatest
                }

                if (!file.isWritable) {
                    return@collectLatest
                }

                readAndWriteAction {
                    writeAction {
                        try {
                            file.getOutputStream(file).use { stream ->
                                with(stream) {
                                    write(value.toByteArray())
                                }
                            }
                        } catch (e: IOException) {
                            // TODO: notifyAboutWriteError
                        } catch (e: IllegalArgumentException) {
                            // TODO: notifyAboutWriteError
                        }
                    }
                }
            }
    }

    override fun globalSchemeChange(scheme: EditorColorsScheme?) {
        if (this::webviewPanel.isInitialized) {
            logger.debug("globalSchemeChange")
//            viewController.changeTheme(uiThemeFromConfig().key)
        }
    }

    override fun getComponent(): JComponent = toolbarAndWebView

    override fun getPreferredFocusedComponent() = toolbarAndWebView

    override fun getName() = "ERD Editor"

    override fun setState(state: FileEditorState) {
    }

    override fun isModified(): Boolean {
        return false;
    }

    override fun isValid(): Boolean {
        return true;
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
    }

    override fun dispose() {
        isDisposed = true
        docToEditorsMap[file]?.remove(this)
    }
}