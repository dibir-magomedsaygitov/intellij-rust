/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.application.AccessToken
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.storage.HeavyProcessLatch
import org.rust.RsTask
import org.rust.lang.core.macros.errors.ExpansionPipelineError
import org.rust.lang.core.macros.errors.ProcMacroExpansionError
import org.rust.lang.core.macros.errors.canCacheError
import org.rust.lang.core.macros.errors.toExpansionPipelineError
import org.rust.lang.core.macros.proc.ProcMacroApplicationService
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.DEFAULT_RECURSION_LIMIT
import org.rust.lang.core.resolve.ref.RsMacroPathReferenceImpl
import org.rust.lang.core.resolve.ref.RsResolveCache
import org.rust.lang.core.resolve2.RESOLVE_LOG
import org.rust.lang.core.resolve2.isNewResolveEnabled
import org.rust.lang.core.resolve2.updateDefMapForAllCrates
import org.rust.openapiext.*
import org.rust.stdext.*
import org.rust.stdext.RsResult.Err
import org.rust.stdext.RsResult.Ok
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.streams.toList
import kotlin.system.measureNanoTime

abstract class MacroExpansionTaskBase(
    project: Project,
    private val storage: ExpandedMacroStorage,
    private val pool: ExecutorService,
    private val vfsBatchFactory: () -> MacroExpansionVfsBatch,
    private val createExpandedSearchScope: (Int) -> GlobalSearchScope,
    private val stepModificationTracker: SimpleModificationTracker
) : Task.Backgroundable(project, "Expanding Rust macros", /* canBeCancelled = */ false),
    RsTask {
    private val resolveCache = RsResolveCache.getInstance(project)
    private val dumbService = DumbService.getInstance(project)
    private val macroExpansionManager = project.macroExpansionManager
    private val expander = FunctionLikeMacroExpander.new(project)
    private val sync = CountDownLatch(1)
    private val estimateStages = AtomicInteger()
    private val doneStages = AtomicInteger()
    private val currentStep = AtomicInteger(-1)
    private val totalExpanded = AtomicInteger()
    private lateinit var realTaskIndicator: ProgressIndicator
    private lateinit var subTaskIndicator: ProgressIndicator
    private lateinit var expansionSteps: Iterator<List<Extractable>>

    @Volatile
    private var heavyProcessRequested = false

    @Volatile
    private var pendingFiles: List<Extractable> = emptyList()

    override fun run(indicator: ProgressIndicator) {
        checkReadAccessNotAllowed()
        indicator.checkCanceled()
        indicator.isIndeterminate = false
        realTaskIndicator = indicator
        subTaskIndicator = indicator.toThreadSafeProgressIndicator()

        try {
            realTaskIndicator.text = "Preparing resolve data"
            updateDefMapForAllCrates(project, pool, subTaskIndicator)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            // Exceptions in new resolve should not break macro expansion
            RESOLVE_LOG.error(e)
        }

        expansionSteps = getMacrosToExpand(dumbService).iterator()

        indicator.checkCanceled()
        var heavyProcessToken: AccessToken? = null
        try {
            submitExpansionTask()

            MACRO_LOG.debug("Awaiting")
            val duration = measureNanoTime {
                // 50ms - default progress bar update interval. See [ProgressDialog.UPDATE_INTERVAL]
                while (!sync.await(50, TimeUnit.MILLISECONDS)) {
                    indicator.fraction = calcProgress(
                        currentStep.get() + 1,
                        doneStages.get().toDouble() / max(estimateStages.get(), 1)
                    )

                    // If project is disposed, then queue will be disposed too, so we shouldn't await sub task finish
                    // (and sub task may not call `sync.countDown()`, so without this `break` we will be blocked
                    // forever)
                    if (project.isDisposed) {
                        subTaskIndicator.cancel()
                        break
                    }
                    // Enter heavy process mode only if at least one macro is not up-to-date

                    if (heavyProcessToken == null && heavyProcessRequested) {
                        heavyProcessToken = HeavyProcessLatch.INSTANCE.processStarted("Expanding Rust macros")
                    }
                }
            }
            MACRO_LOG.info("Task completed! ${totalExpanded.get()} total calls, millis: " + duration / 1_000_000)
        } finally {
            resolveCache.endExpandingMacros()
            // Return all non expanded files to storage.
            // Otherwise, we will lose them until full re-expand
            movePendingFileToStep(pendingFiles, 0)
            heavyProcessToken?.let {
                it.finish()
                // Restart `DaemonCodeAnalyzer` after releasing `HeavyProcessLatch`. Used instead of
                // `DaemonCodeAnalyzer.restart()` to do restart more gracefully, i.e. don't invalidate
                // highlights if nothing actually changed
                WriteAction.runAndWait<Throwable> { }
            }
            MacroExpansionSharedCache.getInstance().flush()
        }
    }

    private fun calcProgress(step: Int, progress: Double): Double =
        (1 until step).sumByDouble { stepProgress(it) } + stepProgress(step) * progress

    // It's impossible to know total quantity of macros, so we guess these values
    // (obtained empirically on some large projects)
    private fun stepProgress(step: Int): Double = when (step) {
        1 -> 0.3
        2 -> 0.2
        3 -> 0.1
        else -> 0.4 / (DEFAULT_RECURSION_LIMIT - 3)
    }

    private fun submitExpansionTask() {
        checkIsBackgroundThread()
        realTaskIndicator.text2 = "Waiting for index"
        val extractableList = executeUnderProgress(subTaskIndicator) {
            runReadActionInSmartMode(dumbService) {
                var extractableList: List<Extractable>?
                do {
                    extractableList = expansionSteps.nextOrNull()
                    val step = currentStep.incrementAndGet()
                    MACRO_LOG.debug("Expansion step: $step")
                    stepModificationTracker.incModificationCount()
                } while (extractableList != null && extractableList.isEmpty())
                extractableList
            }
        }

        if (extractableList == null) {
            sync.countDown()
            return
        }

        realTaskIndicator.text = "Expanding Rust macros. Step " + (currentStep.get() + 1) + "/$DEFAULT_RECURSION_LIMIT"
        estimateStages.set(0)
        doneStages.set(0)

        val scope = createExpandedSearchScope(currentStep.get())
        val expansionState = MacroExpansionManager.ExpansionState(scope, stepModificationTracker)

        // All subsequent parallelStream tasks are executed on the same [pool]
        supplyAsync(pool) {
            realTaskIndicator.text2 = "Expanding macros"

            val pending = ContainerUtil.newConcurrentSet<Extractable>()
            val stages1 = (extractableList + pendingFiles).chunked(100).parallelStream().unordered().flatMap { extractable ->
                val result = executeUnderProgressWithWriteActionPriorityWithRetries(subTaskIndicator) {
                    // We need smart mode because rebind can be performed
                    runReadActionInSmartMode(dumbService) {
                        macroExpansionManager.withExpansionState(expansionState) {
                            extractable.flatMap {
                                val extracted = it.extract()
                                if (extracted == null) {
                                    pending.add(it)
                                }
                                extracted.orEmpty()
                            }
                        }
                    }
                }
                estimateStages.addAndGet(result.size * Pipeline.STAGES)
                result.stream()
            }.toList()

            movePendingFileToStep(pending, currentStep.get())
            pendingFiles = pending.toList()
            MACRO_LOG.debug("Pending files: ${pendingFiles.size}")

            val stages2 = stages1.parallelStream().unordered().map { stage1 ->
                val result = executeUnderProgressWithWriteActionPriorityWithRetries(subTaskIndicator) {
                    // We need smart mode to resolve macros
                    runReadActionInSmartMode(dumbService) {
                        macroExpansionManager.withExpansionState(expansionState) {
                            stage1.expand(project, expander)
                        }
                    }
                }
                doneStages.addAndGet(if (result is EmptyPipeline) Pipeline.STAGES else 2)

                // Enter heavy process mode only if at least one macros is not up-to-date
                if (result !is EmptyPipeline && result !is RemoveSourceFileIfEmptyPipeline) {
                    heavyProcessRequested = true
                }
                result
            }.filter { it !is EmptyPipeline }.toList()

            realTaskIndicator.text2 = "Writing expansion results"

            if (stages2.isNotEmpty()) {
                // TODO support cancellation here
                //  Cancellation isn't supported because we should provide consistency between the filesystem and
                //  the storage, so if we created some files, we must add them to the storage, or they will be leaked.
                // We can cancel task if the project is disposed b/c after project reopen storage consistency will be
                // re-checked

                val batch = vfsBatchFactory()
                val stages3 = stages2.map { stage2 ->
                    val result = stage2.writeExpansionToFs(batch, currentStep.get())
                    doneStages.incrementAndGet()
                    result
                }
                totalExpanded.addAndGet(stages3.size)

                val future = CompletableFuture<Unit>()
                batch.applyToVfs(true, Runnable {
                    // we're in write action
                    if (project.isDisposed) return@Runnable
                    for (stage3 in stages3) {
                        stage3.save(storage)
                    }
                    future.complete(Unit)
                })

                future
            } else {
                CompletableFuture.completedFuture(Unit)
            }
        }.thenCompose { it }.handle { success: Unit?, t: Throwable? ->
            // This callback will be executed regardless of success or exceptional result
            if (success != null) {
                // Success
                if (isDispatchThread) {
                    pool.execute(::runNextStep)
                } else {
                    runNextStep()
                }
            } else {
                when (val e = (t as? CompletionException)?.cause ?: t) {
                    null -> error("unreachable")
                    is ProcessCanceledException -> Unit // Task canceled
                    is CorruptedExpansionStorageException, is MacroExpansionFileSystem.FSException -> {
                        macroExpansionManager.reexpand()
                        MACRO_LOG.warn(
                            "Macro expansion storage is considered corrupted; full re-expansion is requested",
                            e
                        )
                    }
                    else -> {
                        // Error
                        MACRO_LOG.error("Error during macro expansion", e)
                    }
                }
                sync.countDown()
            }
        }.exceptionally {
            // Handle exceptions that may be thrown by `handle` body
            sync.countDown()
            MACRO_LOG.error("Error during macro expansion", it)
        }
    }

    private fun runNextStep() {
        try {
            submitExpansionTask()
        } catch (e: ProcessCanceledException) {
            sync.countDown()
        }
    }

    private fun movePendingFileToStep(newPendingFiles: Collection<Extractable>, step: Int) {
        val processedFiles = pendingFiles - newPendingFiles

        val duration = measureNanoTime {
            if (processedFiles.isEmpty()) return@measureNanoTime
            // TODO: split [processedFiles] into chunks to reduce UI freezes (if needed)
            WriteAction.runAndWait<Nothing> {
                storage.moveSourceFilesToStep(processedFiles.map { it.sf }, step)
            }
        }
        MACRO_LOG.debug("Moving of pending files to step $step: ${duration / 1000} μs")
    }

    protected abstract fun getMacrosToExpand(dumbService: DumbService): Sequence<List<Extractable>>

    override val waitForSmartMode: Boolean
        get() = true
}

class Extractable(val sf: SourceFile, private val workspaceOnly: Boolean, private val calls: List<RsPossibleMacroCall>?) {
    fun extract(): List<Pipeline.Stage1ResolveAndExpand>? {
        return sf.extract(workspaceOnly, calls)
    }
}

object Pipeline {
    const val STAGES: Int = 3

    interface Stage1ResolveAndExpand {
        /** must be a pure function */
        fun expand(project: Project, expander: FunctionLikeMacroExpander): Stage2WriteToFs
    }

    interface Stage2WriteToFs {
        fun writeExpansionToFs(batch: MacroExpansionVfsBatch, stepNumber: Int): Stage3SaveToStorage
    }

    interface Stage3SaveToStorage {
        fun save(storage: ExpandedMacroStorage)
    }
}

object EmptyPipeline : Pipeline.Stage2WriteToFs, Pipeline.Stage3SaveToStorage {
    override fun writeExpansionToFs(batch: MacroExpansionVfsBatch, stepNumber: Int): Pipeline.Stage3SaveToStorage = this
    override fun save(storage: ExpandedMacroStorage) {}
}

object InvalidationPipeline {
    class Stage1(val info: ExpandedMacroInfo) : Pipeline.Stage1ResolveAndExpand {
        override fun expand(project: Project, expander: FunctionLikeMacroExpander): Pipeline.Stage2WriteToFs = Stage2(info)
    }

    class Stage2(val info: ExpandedMacroInfo) : Pipeline.Stage2WriteToFs {
        override fun writeExpansionToFs(batch: MacroExpansionVfsBatch, stepNumber: Int): Pipeline.Stage3SaveToStorage {
            val expansionResult = info.expansionResult
            if (expansionResult is Ok && expansionResult.ok.isValid) {
                batch.deleteFile(expansionResult.ok)
            }
            return Stage3(info)
        }
    }

    class Stage3(val info: ExpandedMacroInfo) : Pipeline.Stage3SaveToStorage {
        override fun save(storage: ExpandedMacroStorage) {
            checkWriteAccessAllowed()
            storage.removeInvalidInfo(info, true)
        }
    }
}

class RemoveSourceFileIfEmptyPipeline(private val sf: SourceFile) : Pipeline.Stage1ResolveAndExpand,
                                                                    Pipeline.Stage2WriteToFs,
                                                                    Pipeline.Stage3SaveToStorage {

    override fun expand(project: Project, expander: FunctionLikeMacroExpander): Pipeline.Stage2WriteToFs = this
    override fun writeExpansionToFs(batch: MacroExpansionVfsBatch, stepNumber: Int): Pipeline.Stage3SaveToStorage = this
    override fun save(storage: ExpandedMacroStorage) {
        checkWriteAccessAllowed()
        storage.removeSourceFileIfEmpty(sf)
    }
}

object ExpansionPipeline {
    class Stage1(
        val call: RsPossibleMacroCall,
        val info: ExpandedMacroInfo
    ) : Pipeline.Stage1ResolveAndExpand {
        override fun expand(project: Project, expander: FunctionLikeMacroExpander): Pipeline.Stage2WriteToFs {
            checkReadAccessAllowed() // Needed to access PSI (including resolve & expansion)
            checkIsSmartMode(project) // Needed to resolve macros
            if (!call.isValid) {
                return InvalidationPipeline.Stage2(info)
            }
            val callHash = call.bodyHash
            val oldExpansionResult = info.expansionResult

            if (oldExpansionResult is Ok && !oldExpansionResult.ok.isValid) throw CorruptedExpansionStorageException()

            if (!call.isEnabledByCfg) return nextStageFail(callHash, null, ExpansionPipelineError.CfgDisabled)
            if (call.shouldSkipMacroExpansion) {
                val error = if (ProcMacroApplicationService.isEnabled()) {
                    ExpansionPipelineError.Skipped
                } else {
                    ExpansionPipelineError.ExpansionError(ProcMacroExpansionError.ProcMacroExpansionIsDisabled)
                }
                return nextStageFail(callHash, null, error)
            }

            val def = RsMacroPathReferenceImpl.resolveInBatchMode { call.resolveToMacroWithoutPsiWithErr() }
                .unwrapOrElse { return nextStageFail(callHash, null, it.toExpansionPipelineError()) }

            val defHash = def.bodyHash

            if (info.isUpToDate(call, def)) {
                val shouldNotReuseCached = oldExpansionResult is Err
                    && oldExpansionResult.err is ExpansionPipelineError.ExpansionError
                    && !oldExpansionResult.err.e.canCacheError()
                    && project.isNewResolveEnabled
                if (!shouldNotReuseCached) {
                    return EmptyPipeline // old expansion is up-to-date
                }
            }

            val callData = RsMacroCallData.fromPsi(call)
                ?: return nextStageFail(callHash, defHash, ExpansionPipelineError.MacroCallSyntax)
            val mixHash = def.mixHash(RsMacroCallDataWithHash(callData, call.bodyHash))
                ?: return nextStageFail(callHash, defHash, ExpansionPipelineError.MacroDefSyntax)

            val expansionResult = if (project.isNewResolveEnabled) {
                // If the new name resolution engine is used, the macro should be already expanded (during the name
                // resolution phase) and cached in `MacroExpansionSharedCache`. Usage of `getExpansionIfCached` helps
                // retrieving the value that was likely used during name resolution
                MacroExpansionSharedCache.getInstance().getExpansionIfCached(mixHash)
            } else {
                null
            } ?: MacroExpansionSharedCache.getInstance().cachedExpand(expander, def.data, callData, mixHash)

            val expansion = when (expansionResult) {
                is Err -> {
                    MACRO_LOG.debug(
                        "Failed to expand a macro. " +
                            "Path: `${call.path?.referenceName.orEmpty()}`, " +
                            "body: `${call.macroBody}`, " +
                            "error: `${expansionResult.err}`"
                    )
                    return nextStageFail(callHash, defHash, ExpansionPipelineError.ExpansionError(expansionResult.err))
                }
                is Ok -> expansionResult.ok
            }

            val expansionBytes = expansion.text.toByteArray()
            val ranges = expansion.ranges

            val expansionBytesHash = VfsInternals.calculateContentHash(expansionBytes).getLeading64bits()

            if (oldExpansionResult is Ok) {
                val oldExpansionBytes = try {
                    oldExpansionResult.ok.contentsToByteArray()
                } catch (e: IOException) {
                    throw CorruptedExpansionStorageException(e)
                }
                if (expansionBytes.contentEquals(oldExpansionBytes)) {
                    // Expansion text isn't changed, but [callHash] or [defHash] or [ranges]
                    // are changed and should be updated
                    return Stage2OkRangesOnly(
                        info,
                        callHash,
                        defHash,
                        mixHash,
                        oldExpansionResult.ok,
                        ranges,
                        expansionBytesHash
                    )
                }
            }

            return Stage2Ok(info, callHash, defHash, mixHash, expansionBytes, ranges, expansionBytesHash)
        }

        private fun nextStageFail(
            callHash: HashCode?,
            defHash: HashCode?,
            error: ExpansionPipelineError
        ): Pipeline.Stage2WriteToFs {
            return if (info.expansionResult.err() == error) {
                EmptyPipeline
            } else {
                Stage2Fail(info, callHash, defHash, error)
            }
        }

        override fun toString(): String =
            "ExpansionPipeline.Stage1(call=${call.path?.referenceName.orEmpty()}!(${call.macroBody}))"
    }

    class Stage2Ok(
        private val info: ExpandedMacroInfo,
        private val callHash: HashCode?,
        private val defHash: HashCode?,
        private val mixHash: HashCode,
        private val expansionBytes: ByteArray,
        private val ranges: RangeMap,
        private val expansionBytesHash: Long
    ) : Pipeline.Stage2WriteToFs {
        override fun writeExpansionToFs(batch: MacroExpansionVfsBatch, stepNumber: Int): Pipeline.Stage3SaveToStorage {
            val oldExpansionResult = info.expansionResult
            val file = if (oldExpansionResult is Ok) {
                if (!oldExpansionResult.ok.isValid) throw CorruptedExpansionStorageException()
                batch.writeFile(oldExpansionResult.ok, expansionBytes)
            } else {
                batch.createFileWithContent(expansionBytes, stepNumber)
            }
            return Stage3(info, callHash, defHash, Ok(Stage3Ok(file, ranges, expansionBytesHash, mixHash)))
        }
    }

    class Stage2OkRangesOnly(
        private val info: ExpandedMacroInfo,
        private val callHash: HashCode?,
        private val defHash: HashCode?,
        private val mixHash: HashCode,
        private val oldExpansionFile: VirtualFile,
        private val ranges: RangeMap,
        private val expansionBytesHash: Long
    ) : Pipeline.Stage2WriteToFs {
        override fun writeExpansionToFs(batch: MacroExpansionVfsBatch, stepNumber: Int): Pipeline.Stage3SaveToStorage {
            val file = batch.resolve(oldExpansionFile)
            return Stage3(info, callHash, defHash, Ok(Stage3Ok(file, ranges, expansionBytesHash, mixHash)))
        }
    }

    class Stage2Fail(
        private val info: ExpandedMacroInfo,
        private val callHash: HashCode?,
        private val defHash: HashCode?,
        private val error: ExpansionPipelineError,
    ) : Pipeline.Stage2WriteToFs {
        override fun writeExpansionToFs(batch: MacroExpansionVfsBatch, stepNumber: Int): Pipeline.Stage3SaveToStorage {
            val oldExpansionResult = info.expansionResult
            if (oldExpansionResult is Ok && oldExpansionResult.ok.isValid) {
                batch.deleteFile(oldExpansionResult.ok)
            }
            return Stage3(info, callHash, defHash, Err(error))
        }
    }

    class Stage3(
        private val info: ExpandedMacroInfo,
        private val callHash: HashCode?,
        private val defHash: HashCode?,
        private val result: RsResult<Stage3Ok<MacroExpansionVfsBatch.Path>, ExpansionPipelineError>
    ) : Pipeline.Stage3SaveToStorage {
        override fun save(storage: ExpandedMacroStorage) {
            checkWriteAccessAllowed()
            val result2 = result.map {
                val file = it.expansionFile.toVirtualFile()
                    ?: error("(${it.expansionFile}).toVirtualFile() must not be null")
                // Optimization: skip charset guessing in `VirtualFileImpl.contentToByteArray()`
                file.setCharset(Charsets.UTF_8, null, false)
                Stage3Ok(file, it.ranges, it.expansionBytesHash, it.mixHash)
            }
            storage.addExpandedMacro(info, callHash, defHash, result2)
            // If a document exists for expansion file (e.g. when AST tree is loaded), the changes in
            // a virtual file will not be committed to the PSI immediately. We have to commit it manually
            // to see the changes (or somehow wait for DocumentCommitThread, but it isn't possible for now)
            if (result2 is Ok) {
                val doc = FileDocumentManager.getInstance().getCachedDocument(result2.ok.expansionFile)
                if (doc != null) {
                    PsiDocumentManager.getInstance(storage.project).commitDocument(doc)
                }
            }
        }
    }
}

data class Stage3Ok<T>(
    val expansionFile: T,
    val ranges: RangeMap,
    val expansionBytesHash: Long,
    val mixHash: HashCode,
)

private class CorruptedExpansionStorageException : RuntimeException {
    constructor() : super()
    constructor(cause: Exception) : super(cause)
}
