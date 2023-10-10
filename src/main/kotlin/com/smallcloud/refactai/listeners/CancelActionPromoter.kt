package com.smallcloud.refactai.listeners

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor

class CancelActionsPromoter : ActionPromoter {
    private fun getEditor(dataContext: DataContext): Editor? {
        return CommonDataKeys.EDITOR.getData(dataContext)
    }
    override fun promote(actions: MutableList<out AnAction>, context: DataContext): MutableList<AnAction> {
        if (getEditor(context) == null)
            return actions.toMutableList()
        return actions.filterIsInstance<CancelPressedAction>().toMutableList()
    }
}