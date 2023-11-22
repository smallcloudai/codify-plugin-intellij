package com.smallcloud.refactai.listeners

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.smallcloud.refactai.modes.ModeProvider.Companion.getOrCreateModeProvider
import com.smallcloud.refactai.privacy.ActionUnderPrivacy
import com.smallcloud.refactai.struct.LongthinkFunctionEntry

class AIToolboxInvokeAction: ActionUnderPrivacy() {

    private fun getEditor(e: AnActionEvent): Editor? {
        return CommonDataKeys.EDITOR.getData(e.dataContext)
                ?: e.presentation.getClientProperty(Key(CommonDataKeys.EDITOR.name))
    }
    override fun actionPerformed(e: AnActionEvent) {
        val editor: Editor = getEditor(e) ?: return
        doActionPerformed(editor)
    }

    fun doActionPerformed(editor: Editor, entryFromContext: LongthinkFunctionEntry? = null) {
        if (!editor.document.isWritable) return
        if (getOrCreateModeProvider(editor).getDiffMode().isInRenderState() ||
                getOrCreateModeProvider(editor).getHighlightMode().isInRenderState())
            return

        val entry = entryFromContext?.copy()
        if (entryFromContext != null && entry != null) {
            entry.intent = entryFromContext.modelFixedIntent
        }

        if (getOrCreateModeProvider(editor).getDiffMode().isInActiveState() ||
                editor.selectionModel.selectionStart != editor.selectionModel.selectionEnd) {
            getOrCreateModeProvider(editor).getDiffMode().actionPerformed(editor, entryFromContext=entry)
        } else {
            getOrCreateModeProvider(editor).getHighlightMode().actionPerformed(editor, entryFromContext=entry)
        }
    }

    override fun setup(e: AnActionEvent) {
        e.presentation.isEnabled = getEditor(e) != null
        isEnabledInModalContext = getEditor(e) != null
    }
}