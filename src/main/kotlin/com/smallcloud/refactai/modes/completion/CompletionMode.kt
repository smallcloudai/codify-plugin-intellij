package com.smallcloud.refactai.modes.completion

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.alsoIfNull
import com.intellij.util.concurrency.AppExecutorUtil
import com.smallcloud.refactai.Resources
import com.smallcloud.refactai.io.ConnectionStatus
import com.smallcloud.refactai.io.streamedInferenceFetch
import com.smallcloud.refactai.modes.EditorTextState
import com.smallcloud.refactai.modes.Mode
import com.smallcloud.refactai.modes.completion.prompt.FilesCollector
import com.smallcloud.refactai.modes.completion.prompt.PromptCooker
import com.smallcloud.refactai.modes.completion.prompt.PromptInfo
import com.smallcloud.refactai.modes.completion.prompt.RequestCreator
import com.smallcloud.refactai.modes.completion.renderer.AsyncCompletionLayout
import com.smallcloud.refactai.modes.completion.structs.Completion
import com.smallcloud.refactai.modes.completion.structs.DocumentEventExtra
import com.smallcloud.refactai.privacy.Privacy
import com.smallcloud.refactai.privacy.PrivacyService
import com.smallcloud.refactai.statistic.CompletionStatistic
import com.smallcloud.refactai.statistic.StatisticService
import com.smallcloud.refactai.statistic.UsageStatistic
import com.smallcloud.refactai.struct.SMCRequest
import com.smallcloud.refactai.utils.getExtension
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import com.smallcloud.refactai.io.InferenceGlobalContext.Companion.instance as InferenceGlobalContext
import com.smallcloud.refactai.statistic.HumanRobotStatistic.Companion.instance as HumanRobotStatistic

class CompletionMode(
    override var needToRender: Boolean = true
) : Mode, CaretListener {
    private val scope: String = "completion"
    private val app = ApplicationManager.getApplication()
    private val scheduler = AppExecutorUtil.createBoundedScheduledExecutorService("SMCCompletionScheduler", 1)
    private var processTask: Future<*>? = null
    private var completionLayout: AsyncCompletionLayout? = null
    private val logger = Logger.getInstance("StreamedCompletionMode")
    private var lastStatistic: CompletionStatistic? = null
    private var hasOneLineCompletionBefore: Boolean = false
    private var completionInProgress: Boolean = false


    override fun beforeDocumentChangeNonBulk(event: DocumentEventExtra) {
        event.editor.caretModel.removeCaretListener(this)
        event.editor.caretModel.addCaretListener(this)
        cancelOrClose()
    }

    override fun onTextChange(event: DocumentEventExtra) {
        lastStatistic?.let {
            if (completionLayout != null) {
                it.addStatistic("cancel", "document_changed",
                        fileExtension = getActiveFile(event.editor.document)?.let {filename ->
                            getExtension(filename)
                        })
                StatisticService.instance.addCompletionStatistic(it)
                lastStatistic = null
            }
        }
        val fileName = getActiveFile(event.editor.document) ?: return
        if (PrivacyService.instance.getPrivacy(FileDocumentManager.getInstance().getFile(event.editor.document))
            == Privacy.DISABLED && !InferenceGlobalContext.isSelfHosted) return
        if (InferenceGlobalContext.status == ConnectionStatus.DISCONNECTED) return
        var maybeState: EditorTextState? = null
        val debounceMs: Long
        val editor = event.editor
        lastStatistic = CompletionStatistic()
        if (!event.force) {
            val docEvent = event.event ?: return
            if (docEvent.offset + docEvent.newLength > editor.document.text.length) return
            if (docEvent.newLength + docEvent.oldLength <= 0) return
            maybeState = EditorTextState(
                editor,
                editor.document.modificationStamp,
                docEvent.offset + docEvent.newLength + event.offsetCorrection
            )
            val completionData = CompletionCache.getCompletion(maybeState.text, maybeState.offset)
            if (completionData != null) {
                processTask = scheduler.submit {
                    renderCompletion(editor, maybeState!!, completionData, animation = false)
                    lastStatistic?.addStatistic("cacheRendered")
                }
                return
            }

            if (shouldIgnoreChange(docEvent, editor, maybeState.offset)) {
                return
            }

            debounceMs = CompletionTracker.calcDebounceTime(editor)
            CompletionTracker.updateLastCompletionRequestTime(editor)
            logger.debug("Debounce time: $debounceMs")
        } else {
            app.invokeAndWait {
                maybeState = EditorTextState(
                    editor,
                    editor.document.modificationStamp,
                    editor.caretModel.offset
                )
            }
            debounceMs = 0
        }

        val state = maybeState ?: return
        if (!state.isValid()) return
        state.getRidOfLeftSpacesInplace()

        var promptInfo: List<PromptInfo> = listOf()
        if (InferenceGlobalContext.useMultipleFilesCompletion) {
            editor.project?.let {
                app.invokeAndWait {
                    promptInfo = PromptCooker.cook(
                        state,
                        FilesCollector.getInstance(it).collect(),
                        mostImportantFilesMaxCount = if (event.force) 25 else 6,
                        lessImportantFilesMaxCount = if (event.force) 10 else 2,
                        maxFileSize = if (event.force) 2_000_000 else 200_000
                    )
                }
            }
        }
        val stat = UsageStatistic(scope, extension = getExtension(fileName))
        val request = RequestCreator.create(
            fileName, state.text, state.offset, state.offset,
            stat, "Infill", "infill", promptInfo,
            stream = true, model = InferenceGlobalContext.model ?: Resources.defaultModel
        ) ?: return

        processTask = scheduler.schedule({
            process(request, state, event.force)
        }, debounceMs, TimeUnit.MILLISECONDS)
    }

    private fun renderCompletion(
        editor: Editor,
        state: EditorTextState,
        completionData: Completion,
        animation: Boolean
    ) {
        var modificationStamp: Long = state.modificationStamp
        var offset: Int = state.offset
        app.invokeAndWait {
            modificationStamp = editor.document.modificationStamp
            offset = editor.caretModel.offset
        }
        val invalidStamp = state.modificationStamp != modificationStamp
        val invalidOffset = state.offset != offset
        if (invalidStamp || invalidOffset) {
            logger.info("Completion is dropped: invalidStamp || invalidOffset")
            logger.info(
                "state_offset: ${state.offset}," +
                        " state_modificationStamp: ${state.modificationStamp}"
            )
            logger.info(
                "editor_offset: $offset, editor_modificationStamp: $modificationStamp"
            )
            return
        }
        if (processTask == null) {
            logger.info("Completion is dropped: there is no active processTask is left")
            return
        }
        logger.info(
            "Completion rendering: offset: ${state.offset}," +
                    " modificationStamp: ${state.modificationStamp}"
        )
        logger.info("Completion data: ${completionData.completion}")
        try {
            completionLayout?.also {
                it.update(completionData, needToRender, animation)
            }.alsoIfNull {
                completionLayout = AsyncCompletionLayout(editor).also {
                    it.update(completionData, needToRender, animation)
                }
            }
        } catch (ex: Exception) {
            logger.warn("Exception while rendering completion", ex)
            logger.debug("Exception while rendering completion cancelOrClose request")
            cancelOrClose()
            lastStatistic?.let {
                lastStatistic = null
            }
        }
    }

    override fun hide() {
        if (!isInActiveState()) return
        scheduler.submit {
            try {
                processTask?.get()
            } catch (_: CancellationException) {
            } finally {
                completionLayout?.hide()
            }
        }
    }

    override fun show() {
        if (isInActiveState()) return
        scheduler.submit {
            try {
                processTask?.get()
            } catch (_: CancellationException) {
            } finally {
                completionLayout?.show()
            }
        }
    }

    private fun process(
        request: SMCRequest,
        editorState: EditorTextState,
        force: Boolean,
    ) {
        val completionState = CompletionState(editorState, force = force)
        if (!force && !completionState.readyForCompletion) return
        if (!force && !completionState.multiline && hasOneLineCompletionBefore) {
            hasOneLineCompletionBefore = false
            return
        }
        if (force) {
            request.body.maxTokens = 512
        }
        request.body.stopTokens = completionState.stopTokens
        InferenceGlobalContext.status = ConnectionStatus.PENDING
        completionInProgress = true
        lastStatistic = CompletionStatistic()
        streamedInferenceFetch(request, dataReceiveEnded = {
            InferenceGlobalContext.status = ConnectionStatus.CONNECTED
            InferenceGlobalContext.lastErrorMsg = null
            lastStatistic?.addStatistic("requestRendered")
        }) { prediction ->
            if (!completionInProgress) {
                return@streamedInferenceFetch
            }

            if (prediction.status == null) {
                InferenceGlobalContext.status = ConnectionStatus.ERROR
                InferenceGlobalContext.lastErrorMsg = "Parameters are not correct"
                return@streamedInferenceFetch
            }

            val headMidTail =
                prediction.choices.firstOrNull()?.filesHeadMidTail?.get(request.body.cursorFile)
                    ?: return@streamedInferenceFetch

            editorState.restoreInplace()
            val completionData = completionState.makeCompletion(
                headMidTail.head,
                headMidTail.mid,
                headMidTail.tail,
                prediction.choices.first().finishReason
            )
            if (!completionData.isMakeSense()) return@streamedInferenceFetch
            if (!editorState.currentLineIsEmptySymbols() && headMidTail.head != editorState.offset) return@streamedInferenceFetch
            synchronized(this) {
                CompletionCache.addCompletion(completionData)
                renderCompletion(
                    editorState.editor, editorState, completionData, true
                )
            }
        }?.also {
            var requestFuture: Future<*>? = null
            try {
                requestFuture = it.get()
                requestFuture.get()
                logger.debug("Completion request finished")
            } catch (e: InterruptedException) {
                handleInterruptedException(requestFuture, editorState.editor)
            } catch (e: InterruptedIOException) {
                handleInterruptedException(requestFuture, editorState.editor)
            } catch (e: ExecutionException) {
                cancelOrClose()
                requestFuture?.cancel(true)
                catchNetExceptions(e.cause)
                lastStatistic?.let {
                    lastStatistic = null
                }
            } catch (e: Exception) {
                InferenceGlobalContext.status = ConnectionStatus.ERROR
                InferenceGlobalContext.lastErrorMsg = e.message
                cancelOrClose()
                lastStatistic?.let {
                    lastStatistic = null
                }
                logger.warn("Exception while completion request processing", e)
            }
        }
    }

    private fun handleInterruptedException(requestFuture: Future<*>?, editor: Editor) {
        InferenceGlobalContext.status = ConnectionStatus.CONNECTED
        requestFuture?.cancel(true)
        lastStatistic?.let {
            lastStatistic?.addStatistic("cancel", "request_abort",
                    fileExtension = getActiveFile(editor.document)?.let {filename ->
                        getExtension(filename)
                    })
            StatisticService.instance.addCompletionStatistic(lastStatistic!!)
            lastStatistic = null
        }
        cancelOrClose()
        logger.debug("lastReqJob abort")
    }

    private fun catchNetExceptions(e: Throwable?) {
        InferenceGlobalContext.status = ConnectionStatus.ERROR
        InferenceGlobalContext.lastErrorMsg = e?.message
        logger.warn("Exception while completion request processing", e)
    }

    override fun onTabPressed(editor: Editor, caret: Caret?, dataContext: DataContext) {
        lastStatistic?.let {
            if (completionLayout != null) {
                it.addStatistic("accept", "tab",
                        fileExtension = getActiveFile(editor.document)?.let {filename ->
                            getExtension(filename)
                        })
                StatisticService.instance.addCompletionStatistic(it)
                lastStatistic = null
            }
        }

        completionLayout?.apply {
            applyPreview(caret ?: editor.caretModel.currentCaret)
            lastCompletionData?.let {
                val nextLine = it.endIndex >= it.originalText.length || it.originalText[it.endIndex] == '\n'
                hasOneLineCompletionBefore = !it.multiline && nextLine
                HumanRobotStatistic.pushStat(editor, it.originalText, it.startIndex, it.endIndex, it.completion)
            }
            dispose()
        }
        completionLayout = null
    }

    override fun onEscPressed(editor: Editor, caret: Caret?, dataContext: DataContext) {
        lastStatistic?.let {
            if (completionLayout != null) {
                it.addStatistic("cancel", "esc",
                        fileExtension = getActiveFile(editor.document)?.let {filename ->
                            getExtension(filename)
                        })
                StatisticService.instance.addCompletionStatistic(it)
                lastStatistic = null
            }
        }
        cancelOrClose()
    }

    override fun onCaretChange(event: CaretEvent) {
    }

    override fun caretPositionChanged(event: CaretEvent) {
        lastStatistic?.let {
            fun isWriting(): Boolean {
                return event.newPosition.line == event.oldPosition.line &&
                        event.newPosition.column == (event.oldPosition.column + 1)
            }
            if (completionLayout != null && !isWriting()) {
                it.addStatistic("cancel", "caret_changed",
                        fileExtension = getActiveFile(event.editor.document)?.let {filename ->
                    getExtension(filename)
                })
                StatisticService.instance.addCompletionStatistic(it)
                lastStatistic = null
            }
        }
        cancelOrClose()
    }

    override fun isInActiveState(): Boolean = completionLayout != null && completionLayout!!.rendered && needToRender

    override fun cleanup(editor: Editor) {
        lastStatistic?.let {
            if (completionLayout != null) {
                it.addStatistic("cancel", "mode_changed",
                        fileExtension = getActiveFile(editor.document)?.let {filename ->
                            getExtension(filename)
                        })
                StatisticService.instance.addCompletionStatistic(it)
                lastStatistic = null
            }
        }
        cancelOrClose()
    }

    private fun shouldIgnoreChange(event: DocumentEvent?, editor: Editor, offset: Int): Boolean {
        if (event == null) return false
        val document = event.document

        if (editor.editorKind != EditorKind.MAIN_EDITOR && !app.isUnitTestMode) {
            return true
        }
        if (!EditorModificationUtil.checkModificationAllowed(editor)
            || document.getRangeGuard(offset, offset) != null
        ) {
            document.fireReadOnlyModificationAttempt()
            return true
        }
        return false
    }

    private fun getActiveFile(document: Document): String? {
        val file = FileDocumentManager.getInstance().getFile(document)
        return file?.presentableName
    }

    private fun cancelOrClose() {
        lastStatistic = null
        try {
            processTask?.cancel(true)
            processTask?.get()
        } catch (_: CancellationException) {
        } finally {
            if (InferenceGlobalContext.status != ConnectionStatus.DISCONNECTED) {
                InferenceGlobalContext.status = ConnectionStatus.CONNECTED
            }
            completionInProgress = false
            processTask = null
            completionLayout?.dispose()
            completionLayout = null
        }
    }
}
