package com.smallcloud.refactai.panes.gptchat

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import com.obiscr.chatgpt.ui.HistoryComponent
import com.smallcloud.refactai.listeners.LastEditorGetterListener
import com.smallcloud.refactai.panes.RefactAIToolboxPaneFactory.Companion.chat
import com.smallcloud.refactai.panes.gptchat.structs.HistoryEntry
import com.smallcloud.refactai.panes.gptchat.ui.CustomSearchTextArea
import com.smallcloud.refactai.panes.gptchat.ui.MessageComponent
import icons.CollaborationToolsIcons
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextArea
import com.smallcloud.refactai.account.AccountManager.Companion.instance as AccountManager


class ChatGPTPane : JPanel(), Disposable {
    private val sendAction = object : DumbAwareAction(CollaborationToolsIcons.Send) {
        init {
            templatePresentation.hoveredIcon = CollaborationToolsIcons.SendHovered
            isEnabledInModalContext = AccountManager.isLoggedIn
        }
        override fun actionPerformed(e: AnActionEvent) {
            doActionPerformed()
        }
        fun setEnabled(enabled: Boolean) {
            isEnabledInModalContext = enabled
        }
    }
    private val stopAction = object : DumbAwareAction(AllIcons.Ide.Notification.Close) {
        init {
            templatePresentation.hoveredIcon = AllIcons.Ide.Notification.CloseHover
            isEnabledInModalContext = AccountManager.isLoggedIn
        }
        override fun actionPerformed(e: AnActionEvent) {
            listener.cancelOrClose()
        }
    }

    var searchTextArea: CustomSearchTextArea = CustomSearchTextArea(JBTextArea().apply {
        rows = 3
    }).also {
        it.setExtraActions(sendAction)
        Disposer.register(this, it)
    }


    private val listener = ChatGPTProvider()
    val state = State()
    private var contentPanel = HistoryComponent(state)
    private var progressBar: JProgressBar? = null
    private val splitter: OnePixelSplitter = OnePixelSplitter(true,.9f)

    enum class SendingState {
        READY,
        PENDING,
    }
    private var _sendingState = SendingState.READY
    var sendingState
        get() = _sendingState
        set(value) {
            _sendingState = value
            when (value) {
                SendingState.READY -> {
                    ApplicationManager.getApplication().invokeLater {
                        searchTextArea.setExtraActions(sendAction)
                        searchTextArea.updateUI()
                        aroundRequest(false)
                    }
                }
                SendingState.PENDING -> {
                    ApplicationManager.getApplication().invokeLater {
                        searchTextArea.setExtraActions(stopAction)
                        searchTextArea.updateUI()
                        aroundRequest(true)
                    }
                }
            }
        }

    init {
        searchTextArea.isEnabled = true
        splitter.dividerWidth = 2;
        searchTextArea.textArea.addKeyListener(object: KeyListener {
            override fun keyTyped(e: KeyEvent?) {}
            override fun keyReleased(e: KeyEvent?) {}
            override fun keyPressed(e: KeyEvent?) {
                if (e != null) {
                    if (e.keyCode == KeyEvent.VK_ENTER && !e.isControlDown && !e.isShiftDown) {
                        e.consume()
                        doActionPerformed()
                    }
                }
            }
        })

        val top = JPanel(BorderLayout())
        progressBar = JProgressBar()
        top.add(progressBar, BorderLayout.NORTH)
        top.add(searchTextArea, BorderLayout.CENTER)
        top.border = UIUtil.getTextFieldBorder()

        splitter.firstComponent = contentPanel
        splitter.secondComponent = top
        layout = BorderLayout()
        add(splitter, BorderLayout.CENTER)
    }

    fun getComponentForFocus(): JTextArea {
        return searchTextArea.textArea
    }

    private fun doActionPerformed() {
        var selectedText: String? = null
        if (searchTextArea.needToInline && LastEditorGetterListener.LAST_EDITOR != null) {
            selectedText = LastEditorGetterListener.LAST_EDITOR!!.selectionModel.selectedText
        }
        listener.doActionPerformed(this@ChatGPTPane, selectedText=selectedText,
                editor=LastEditorGetterListener.LAST_EDITOR)
        searchTextArea.disableAttachFileCBAndModelSelector()
    }

    private fun aroundRequest(status: Boolean) {
        progressBar!!.isIndeterminate = status
        sendAction.setEnabled(!status)
    }

    fun send(msg: String, selectedText: String) {
        contentPanel.clearHistory()
        listener.doActionPerformed(this, msg, selectedText)
    }

    fun getFullHistory(): MutableList<HistoryEntry> {
        return contentPanel.getFullHistory()
    }

    fun add(messageComponent: MessageComponent) {
        val removedTip = contentPanel.add(messageComponent)
        if (removedTip) {
            chat?.renameTab(this, messageComponent.question[0].rawText)
        }
    }
    fun scrollToBottom() {
        contentPanel.scrollToBottom()
    }
    fun cancelRequest() {
        return listener.cancelOrClose()
    }

    override fun dispose() {
        Disposer.dispose(searchTextArea)
    }
}