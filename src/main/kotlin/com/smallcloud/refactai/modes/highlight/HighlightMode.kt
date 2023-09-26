package com.smallcloud.refactai.modes.highlight

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.smallcloud.refactai.Resources
import com.smallcloud.refactai.io.ConnectionStatus
import com.smallcloud.refactai.modes.Mode
import com.smallcloud.refactai.modes.ModeProvider
import com.smallcloud.refactai.modes.ModeType
import com.smallcloud.refactai.modes.completion.prompt.RequestCreator
import com.smallcloud.refactai.modes.completion.structs.DocumentEventExtra
import com.smallcloud.refactai.statistic.UsageStatistic
import com.smallcloud.refactai.struct.LongthinkFunctionEntry
import com.smallcloud.refactai.struct.SMCRequest
import com.smallcloud.refactai.utils.getExtension
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import com.smallcloud.refactai.io.InferenceGlobalContext.Companion.instance as InferenceGlobalContext

class HighlightMode(
    override var needToRender: Boolean = true
) : Mode {
//    private var layout: HighlightLayout? = null
    private val scope: String = "highlight"
    private val scheduler = AppExecutorUtil.createBoundedScheduledExecutorService("SMCHighlightScheduler", 3)
    private val app = ApplicationManager.getApplication()
    private var processTask: Future<*>? = null
    private var renderTask: Future<*>? = null
    private var goToDiffTask: Future<*>? = null
    private val logger = Logger.getInstance(HighlightMode::class.java)
    private var needAnimation = false

    private fun isProgress(): Boolean {
        return needAnimation
    }

    private fun finishAnimation() {
        needAnimation = false
//        renderTask?.get()
    }

    private fun cancel(editor: Editor?) {
        try {
            processTask?.cancel(true)
            processTask?.get()
        } catch (_: CancellationException) {
        } finally {
            if (InferenceGlobalContext.status != ConnectionStatus.DISCONNECTED &&
                InferenceGlobalContext.status != ConnectionStatus.ERROR
            ) {
                InferenceGlobalContext.status = ConnectionStatus.CONNECTED
            }
            finishAnimation()
//            layout?.dispose()
//            layout = null
            if (editor != null && !Thread.currentThread().stackTrace.any { it.methodName == "switchMode" }) {
                ModeProvider.getOrCreateModeProvider(editor).switchMode()
            }
        }
    }

    override fun beforeDocumentChangeNonBulk(event: DocumentEventExtra) {
        if (event.editor.getUserData(Resources.ExtraUserDataKeys.addedFromHL) == true) {
            event.editor.putUserData(Resources.ExtraUserDataKeys.addedFromHL, false)
            return
        }
        app.invokeAndWait { cancel(event.editor) }
        ModeProvider.getOrCreateModeProvider(event.editor).switchMode()
    }

    override fun onTextChange(event: DocumentEventExtra) {
    }

    override fun onTabPressed(editor: Editor, caret: Caret?, dataContext: DataContext) {}

    override fun onEscPressed(editor: Editor, caret: Caret?, dataContext: DataContext) {
        cancel(editor)
        ModeProvider.getOrCreateModeProvider(editor).switchMode()
    }

    override fun onCaretChange(event: CaretEvent) {
//        goToDiffTask?.cancel(false)
//        val offsets = event.caret?.let { layout?.getHighlightsOffsets(it.offset) }
//        val entry = layout?.function
//        if (offsets != null && entry != null) {
//            goToDiffTask = scheduler.schedule({
//                // cleanup must be called from render thread; scheduler creates worker thread only
//                app.invokeAndWait {
//                    cleanup(event.editor)
//                    ModeProvider.getOrCreateModeProvider(event.editor)
//                        .getDiffMode().actionPerformed(
//                            event.editor, HighlightContext(entry, offsets[0], offsets[1])
//                        )
//                }
//            }, 300, TimeUnit.MILLISECONDS)
//        }

    }

    fun isInRenderState(): Boolean {
        return true
//        return (layout != null && !layout!!.rendered) ||
//                (renderTask != null && !renderTask!!.isDone && !renderTask!!.isCancelled) || isProgress()
    }

    override fun isInActiveState(): Boolean {
        return true
//        return isInRenderState() ||
//                (processTask != null && !processTask!!.isDone && !processTask!!.isCancelled) ||
//                layout != null
    }

    override fun show() {
        TODO("Not yet implemented")
    }

    override fun hide() {
        TODO("Not yet implemented")
    }

    override fun cleanup(editor: Editor) {
        cancel(editor)
    }

    fun actionPerformed(editor: Editor, entryFromContext: LongthinkFunctionEntry? = null) {
//        if (layout != null) {
//            layout?.dispose()
//            layout = null
//        }
        if (InferenceGlobalContext.status == ConnectionStatus.DISCONNECTED) return

        val entry: LongthinkFunctionEntry = entryFromContext ?: return
        val fileName = getActiveFile(editor.document) ?: return
        val startSelectionOffset = editor.selectionModel.selectionStart
        val endSelectionOffset = editor.selectionModel.selectionEnd
        val funcName = if (entry.functionHighlight.isNullOrEmpty()) entry.functionName else
            entry.functionHighlight ?: return
        val stat = UsageStatistic(scope, entry.functionName, extension = getExtension(fileName))
        val request = RequestCreator.create(
            fileName, editor.document.text,
            startSelectionOffset, endSelectionOffset,
            stat, entry.intent, funcName, listOf(),
            model = InferenceGlobalContext.longthinkModel ?: entry.model
                ?: InferenceGlobalContext.model ?: Resources.defaultModel,
            stream = false
        ) ?: return
        ModeProvider.getOrCreateModeProvider(editor).switchMode(ModeType.Highlight)

        needAnimation = true
        val startPosition = editor.offsetToLogicalPosition(startSelectionOffset)
        renderTask = scheduler.submit {
            waitingHighlight(editor, startPosition, this::isProgress)
        }
        processTask = scheduler.submit {
            process(request, entry, editor)
        }
    }

    fun process(
        request: SMCRequest,
        entry: LongthinkFunctionEntry,
        editor: Editor
    ) {
//        request.body.stopTokens = listOf()
//        request.body.maxTokens = 0
//
//        InferenceGlobalContext.status = ConnectionStatus.PENDING
//        inferenceFetch(request) { prediction ->
//            if (prediction.status == null) {
//                InferenceGlobalContext.status = ConnectionStatus.ERROR
//                InferenceGlobalContext.lastErrorMsg = "Parameters are not correct"
//                return@inferenceFetch
//            }
//
//            val predictedText = prediction.choices.firstOrNull()?.files?.get(request.body.cursorFile)
//            val finishReason = prediction.choices.firstOrNull()?.finishReason
//            if (predictedText == null || finishReason == null) {
//                InferenceGlobalContext.status = ConnectionStatus.CONNECTED
//                InferenceGlobalContext.lastErrorMsg = "Request was succeeded but there is no predicted data"
//                return@inferenceFetch
//            } else {
//                InferenceGlobalContext.status = ConnectionStatus.CONNECTED
//                InferenceGlobalContext.lastErrorMsg = null
//            }
//
//            layout = HighlightLayout(editor, entry, request, prediction)
//            finishAnimation()
//            app.invokeAndWait {
//                layout!!.render()
//            }
//
//            if (layout!!.isEmpty()) {
//                ModeProvider.getOrCreateModeProvider(editor).switchMode()
//            }
//        }?.also {
//            var requestFuture: Future<*>? = null
//            try {
//                requestFuture = it.get()
//                requestFuture.get()
//                logger.debug("Diff request finished")
//            } catch (_: InterruptedException) {
//                requestFuture?.cancel(true)
//                cancel(editor)
//                ModeProvider.getOrCreateModeProvider(editor).switchMode()
//            } catch (e: ExecutionException) {
//                catchNetExceptions(e.cause)
//                ModeProvider.getOrCreateModeProvider(editor).switchMode()
//            } catch (e: Exception) {
//                InferenceGlobalContext.status = ConnectionStatus.ERROR
//                InferenceGlobalContext.lastErrorMsg = e.message
//                logger.warn("Exception while highlight request processing", e)
//                ModeProvider.getOrCreateModeProvider(editor).switchMode()
//            }
//        }
    }

    private fun catchNetExceptions(e: Throwable?) {
        InferenceGlobalContext.status = ConnectionStatus.ERROR
        InferenceGlobalContext.lastErrorMsg = e?.message
        logger.warn("Exception while highlight request processing", e)
    }

    private fun getActiveFile(document: Document): String? {
        if (!app.isDispatchThread) return null
        val file = FileDocumentManager.getInstance().getFile(document)
        return file?.presentableName
    }

}