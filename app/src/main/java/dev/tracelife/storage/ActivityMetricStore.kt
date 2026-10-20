package dev.tracelife.storage

import android.database.sqlite.SQLiteDatabase
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.templates.ActivityLogTemplate

internal data class ActivityMetricInput(
    val recordId: String,
    val revision: Int,
    val supported: Boolean,
    val domain: String,
    val minutes: String,
    val practices: String,
    val hasOutcome: Boolean,
)

/** Reads bounded scalar answers and outcome presence, never the prose or outcome body. */
internal object ActivityMetricStore {
    fun read(db: SQLiteDatabase, revisions: Map<String, Int>): List<ActivityMetricInput> {
        val templates = mutableMapOf<Pair<String, Int>, Pair<Boolean, Boolean>>()
        return revisions.mapNotNull { (id, revision) ->
            val args = arrayOf(id, revision.toString())
            val template = db.rawQuery("SELECT template_id, template_version FROM record_revisions WHERE record_id = ? AND revision = ?", args).use {
                check(it.moveToFirst())
                if (it.isNull(0)) null else it.getString(0) to it.getInt(1)
            } ?: return@mapNotNull null
            val (candidate, supported) = templates.getOrPut(template) {
                val questions = db.rawQuery("SELECT question_id, prompt, hint, intent FROM template_questions WHERE template_id = ? AND version = ? ORDER BY ordinal",
                    arrayOf(template.first, template.second.toString())).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(TemplateQuestion(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))) }
                }
                questions.any { it.id.startsWith("activity.v1.") } to ActivityLogTemplate.supports(questions)
            }
            if (!candidate) return@mapNotNull null
            val scalar = mutableMapOf<String, String>()
            var hasOutcome = false
            db.rawQuery("SELECT question_id, CASE WHEN question_id = ? THEN '' WHEN length(answer) > 64 THEN 'invalid_oversized_scalar' ELSE answer END, length(trim(answer)) FROM record_answers WHERE record_id = ? AND revision = ? AND question_id IN (?, ?, ?, ?)",
                arrayOf(ActivityLogTemplate.OUTCOME, id, revision.toString(), ActivityLogTemplate.DOMAIN,
                    ActivityLogTemplate.MINUTES, ActivityLogTemplate.PRACTICES, ActivityLogTemplate.OUTCOME)).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    if (key == ActivityLogTemplate.OUTCOME) hasOutcome = cursor.getInt(2) > 0
                    else scalar[key] = cursor.getString(1)
                }
            }
            ActivityMetricInput(id, revision, supported, scalar[ActivityLogTemplate.DOMAIN].orEmpty(),
                scalar[ActivityLogTemplate.MINUTES].orEmpty(), scalar[ActivityLogTemplate.PRACTICES].orEmpty(), hasOutcome)
        }
    }
}
