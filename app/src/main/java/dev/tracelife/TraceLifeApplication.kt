package dev.tracelife

import android.app.Application
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.AnalysisBatchProcessor
import dev.tracelife.analysis.AnalysisFeedback
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
