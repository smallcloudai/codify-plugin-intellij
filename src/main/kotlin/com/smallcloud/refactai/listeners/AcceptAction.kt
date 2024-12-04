package com.smallcloud.refactai.listeners


import com.intellij.codeInsight.hint.HintManagerImpl.ActionToIgnore
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.smallcloud.refactai.Resources
import com.smallcloud.refactai.codecompletion.InlineCompletionGrayTextElementCustom
import com.smallcloud.refactai.modes.ModeProvider

const val ACTION_ID_ = "TabPressedAction"

class TabPressedAction : EditorAction(InlineCompletionHandler()), ActionToIgnore {
    val ACTION_ID = ACTION_ID_

    init {
        this.templatePresentation.icon = Resources.Icons.LOGO_RED_16x16
    }

    class InlineCompletionHandler : EditorWriteActionHandler() {
        override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) {
            Logger.getInstance("RefactTabPressedAction").debug("executeWriteAction")
            val provider = ModeProvider.getOrCreateModeProvider(editor)
            if (provider.isInCompletionMode()) {
                InlineCompletion.getHandlerOrNull(editor)?.insert()
            } else {
                provider.onTabPressed(editor, caret, dataContext)
            }
        }

        override fun isEnabledForCaret(
            editor: Editor,
            caret: Caret,
            dataContext: DataContext
        ): Boolean {
            val provider = ModeProvider.getOrCreateModeProvider(editor)
            if (provider.isInCompletionMode()) {
                val ctx = InlineCompletionContext.getOrNull(editor) ?: return false
                if (ctx.state.elements.size != 1) return false
                val elem = ctx.state.elements.first()
                if (elem !is InlineCompletionGrayTextElementCustom.Presentable) return false
                return elem.delta == caret.logicalPosition.column
            } else {
                return ModeProvider.getOrCreateModeProvider(editor).modeInActiveState()
            }
        }
    }
}
