package dev.tracelife

import android.app.Application
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.AnalysisBatchProcessor
import dev.tracelife.analysis.AnalysisFeedback
import dev.tracelife.analysis.AutomaticOrganizer
import dev.tracelife.analysis.PeriodSynthesisProcessor
import dev.tracelife.analysis.PeriodReductionProcessor
import dev.tracelife.analysis.PeriodReductionReviewProcessor
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.metrics.RecordMetrics
import dev.tracelife.metrics.RecordMetricEvidenceService
import dev.tracelife.reports.PeriodReports
import dev.tracelife.reports.PeriodReductionReports
import dev.tracelife.providers.DeepSeekCredentials
import dev.tracelife.providers.DeepSeekProvider
import dev.tracelife.storage.LifeRepository
import dev.tracelife.templates.BuiltInTemplates
import dev.tracelife.transfer.DataTransfer
import java.util.concurrent.Executors

class TraceLifeApplication : Application() {
    val io = Executors.newSingleThreadExecutor()
    val dataTransfer by lazy { DataTransfer(this, repository) }
    val analysis by lazy { ControlledAnalysis(repository) }
    val analysisProcessor by lazy { AnalysisBatchProcessor(analysis) }
    val analysisFeedback by lazy { AnalysisFeedback(repository) }
    val knowledge by lazy { KnowledgeLibrary(repository) }
    val recordMetrics by lazy { RecordMetrics(repository) }
    val metricEvidence by lazy { RecordMetricEvidenceService(repository) }
    val automaticOrganizer by lazy { AutomaticOrganizer(repository, analysis, analysisProcessor, knowledge) }
    val periodSynthesis by lazy { PeriodSynthesisProcessor(analysis) }
    val periodReduction by lazy { PeriodReductionProcessor(analysis) }
    val periodReview by lazy { PeriodReductionReviewProcessor(analysis) }
    val actions by lazy { ActionExperiments(repository) }
    val periodReports by lazy { PeriodReports(repository) }
    val periodDrafts by lazy { PeriodReductionReports(repository, analysis) }
    val deepSeekCredentials by lazy { DeepSeekCredentials(this) }
    val deepSeekProvider by lazy { DeepSeekProvider(deepSeekCredentials) }
    val repository by lazy {
        LifeRepository(this).also { repository ->
            val installation = getSharedPreferences("installation", MODE_PRIVATE)
            if (!installation.getBoolean("templates_seeded", false)) {
                mapOf(
                    "builtin-daily" to BuiltInTemplates.daily,
                    "builtin-weekly" to BuiltInTemplates.weekly,
                    "builtin-event" to BuiltInTemplates.event,
                ).forEach { (id, draft) ->
                    if (repository.template(id) == null) repository.saveTemplate(draft, id = id)
                }
                check(installation.edit().putBoolean("templates_seeded", true).commit()) {
                    "无法保存初始化状态，请重试"
                }
            }
        }
    }
}
