package com.smallcloud.refactai.code_lens

import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.codeVision.CodeVisionInitializer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.smallcloud.refactai.lsp.LSPProcessHolderChangedNotifier

class CodeLensInvalidatorService(project: Project): Disposable {
    private var ids: List<String> = emptyList()
    override fun dispose() {}
    fun setCodeLensIds(ids: List<String>) {
        this.ids = ids
    }

    init {
        project.messageBus.connect(this).subscribe(LSPProcessHolderChangedNotifier.TOPIC, object : LSPProcessHolderChangedNotifier {
            override fun lspIsActive(isActive: Boolean) {
                invokeLater {
                    project.service<CodeVisionInitializer>().getCodeVisionHost()
                        .invalidateProvider(CodeVisionHost.LensInvalidateSignal(null, ids))
                }
            }
        })
    }
}