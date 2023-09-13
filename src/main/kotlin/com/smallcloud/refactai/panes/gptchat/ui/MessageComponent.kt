package com.smallcloud.refactai.panes.gptchat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColorUtil.brighter
import com.intellij.ui.ColorUtil.darker
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.smallcloud.refactai.panes.gptchat.structs.ParsedText
import org.jdesktop.swingx.VerticalLayout
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.util.function.Supplier
import javax.accessibility.AccessibleContext
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent
import javax.swing.text.AttributeSet
import javax.swing.text.html.StyleSheet


private class CopyClipboardAction(private val pane: JEditorPane) : DumbAwareAction(AllIcons.Actions.Copy) {
    init {
        templatePresentation.hoveredIcon = AllIcons.General.CopyHovered
    }

    override fun actionPerformed(e: AnActionEvent) {
        val text = pane.getClientProperty("rawText") as String?
        if (text != null) {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(text), null)
        }
    }
}

private val myColor = JBColor(0xEAEEF7, 0x45494A)
private val robotColor = JBColor(0xE0EEF7, 0x2d2f30)

class MessageComponent(var question: List<ParsedText>,
                       val me: Boolean) : JBPanel<MessageComponent>() {

    private val myList = JPanel(VerticalLayout())
    private var rawTexts = emptyList<String?>()

    init {
        isDoubleBuffered = true
        isOpaque = true
        background = if (me) myColor else robotColor
        border = JBUI.Borders.empty(10, 10, 10, 0)

        layout = BorderLayout(JBUI.scale(7), 0)
        val centerPanel = JPanel(VerticalLayout(JBUI.scale(8)))
        setContent(question)
        myList.isOpaque = false
        centerPanel.isOpaque = false
        centerPanel.border = JBUI.Borders.empty()
        add(centerPanel, BorderLayout.CENTER)
        centerPanel.add(myList)
        val actionPanel = JPanel(BorderLayout())
        actionPanel.isOpaque = false
        actionPanel.border = JBUI.Borders.empty()
        add(actionPanel, BorderLayout.EAST)
    }

    private fun setLinkForeground(styleSheet: StyleSheet) {
        val color = JBColor.namedColor("Notification.linkForeground", JBUI.CurrentTheme.Link.Foreground.ENABLED)
        styleSheet.addRule("a {color: " + ColorUtil.toHtmlColor(color) + "}")
    }

    private fun configureHtmlEditorKit(editorPane: JEditorPane) {
        val kit = HTMLEditorKitBuilder().withWordWrapViewFactory().withFontResolver { defaultFont, attributeSet ->
            if ("a".equals(attributeSet.getAttribute(AttributeSet.NameAttribute)?.toString(), ignoreCase = true)) {
                UIUtil.getLabelFont()
            } else defaultFont
        }.build()
        setLinkForeground(kit.styleSheet)
        editorPane.editorKit = kit
    }

    private fun createContentComponent(content: String, isCode: Boolean): Component {
        val wrapper = JPanel(BorderLayout()).also {
            it.border = JBUI.Borders.empty(5)
            it.isOpaque = isCode
            if (isCode) {
                it.background = JBColor.lazy(Supplier {
                    val isDark = ColorUtil.isDark(EditorColorsManager.getInstance().globalScheme.defaultBackground)
                    return@Supplier if (isDark) brighter(background, 2) else darker(this.background, 2)
                })
            }
        }

        val component = JEditorPane()
        component.putClientProperty("isCode", isCode)
        component.isEditable = false
        component.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, java.lang.Boolean.TRUE)
        component.contentType = "text/html"
        component.isOpaque = false
        component.border = null
        configureHtmlEditorKit(component)
        component.putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY,
                StringUtil.unescapeXmlEntities(StringUtil.stripHtml(content, " ")))
        component.text = content
        component.addHyperlinkListener { e ->
            if (HyperlinkEvent.EventType.ACTIVATED == e.eventType) {
                val desktop: Desktop = Desktop.getDesktop()
                try {
                    desktop.browse(e.url.toURI())
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
        if (isCode) {
            wrapper.add(JPanel(BorderLayout()).also {
                it.add(MyActionButton(CopyClipboardAction(component), false, true), BorderLayout.EAST)
                it.isOpaque = false
            }, BorderLayout.NORTH)
        }
        component.layout = FlowLayout()
        component.isEditable = false
        if (component.caret != null) {
            component.caretPosition = 0
        }
        wrapper.add(component)
        return wrapper
    }

    fun setContent(content: List<ParsedText>) {
        rawTexts = content.map { it.rawText }
        question = content
        content.forEachIndexed { index, element ->
            if (myList.components.size <= index) {
                myList.add(createContentComponent(element.htmlText, element.isCode))
                return@forEachIndexed
            }
            val editor = (myList.components[index] as JPanel).components.last() as JEditorPane
            if (element.isCode && !(editor.getClientProperty("isCode") as Boolean)) {
                myList.remove(index)
                myList.add(createContentComponent(element.htmlText, element.isCode), index)
                return@forEachIndexed
            }
            if (editor.text == element.htmlText) {
                return@forEachIndexed
            } else {
                editor.text = element.htmlText
                editor.putClientProperty("rawText", element.rawText)
            }
            editor.updateUI()
        }
        myList.updateUI()
    }
}