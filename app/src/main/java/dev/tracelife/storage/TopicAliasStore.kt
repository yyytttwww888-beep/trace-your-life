package dev.tracelife.storage

import android.database.sqlite.SQLiteDatabase
import dev.tracelife.knowledge.TopicAliasTemplate

internal data class TopicAliasInput(val id: String, val revision: Int, val canonical: String, val aliasesText: String, val archived: Boolean,
    val problem: String? = null)
internal data class TopicAliasInputPage(val items: List<TopicAliasInput>, val total: Int, val nextOffset: Int?)

/** Indexed template/answer reads, never an all-record-body scan or a name-based heuristic. */
internal object TopicAliasStore {
    fun page(db: SQLiteDatabase, offset: Int, limit: Int, includeArchived: Boolean): TopicAliasInputPage {
        require(offset >= 0 && limit in 1..100)
        val (identity, identityArgs) = configuration("r")
        val where = "r.revision=h.current_revision AND $identity" + if (includeArchived) "" else " AND r.archived=0"
        val join = "FROM records h JOIN record_revisions r ON r.record_id=h.id WHERE $where"
        val total = db.rawQuery("SELECT COUNT(*) $join", identityArgs.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
        require(total <= 10_000) { "主题叫法目录超过 10000 项，须先清理，未返回不完整目录" }
        val canonical = "(SELECT a.answer FROM record_answers a WHERE a.record_id=r.record_id AND a.revision=r.revision AND a.question_id=?)"
        val aliases = "(SELECT a.answer FROM record_answers a WHERE a.record_id=r.record_id AND a.revision=r.revision AND a.question_id=?)"
        val args = listOf(TopicAliasTemplate.CANONICAL, TopicAliasTemplate.ALIASES) + identityArgs + listOf(limit.toString(), offset.toString())
        val items = db.rawQuery("SELECT h.id,r.revision,substr($canonical,1,81),substr($aliases,1,2049),r.archived $join ORDER BY r.updated_at DESC,h.id LIMIT ? OFFSET ?", args.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val canonicalValue = cursor.getString(2).orEmpty()
                    val aliasesValue = cursor.getString(3).orEmpty()
                    val oversized = canonicalValue.codePointCount(0, canonicalValue.length) > 80 || aliasesValue.codePointCount(0, aliasesValue.length) > 2048
                    add(TopicAliasInput(cursor.getString(0), cursor.getInt(1), canonicalValue, aliasesValue, cursor.getInt(4) == 1,
                        if (oversized) "内容超过叫法限制，请修复配置；原文和历史仍保留。" else null))
                }
            }
        }
        return TopicAliasInputPage(items, total, (offset + items.size).takeIf { it < total })
    }

    fun excludeConfiguration(revisionAlias: String): Pair<String, List<String>> {
        val (condition, args) = configuration(revisionAlias)
        return "NOT ($condition)" to args
    }

    /** Scope invalidation may ignore only identities whose entire retained history is configuration. */
    fun isEntirelyConfiguration(db: SQLiteDatabase, recordId: String): Boolean {
        val (condition, args) = configuration("configuration_revision")
        return db.rawQuery("""SELECT 1 FROM records WHERE id=? AND NOT EXISTS (
            SELECT 1 FROM record_revisions configuration_revision
            WHERE configuration_revision.record_id=? AND NOT ($condition))""",
            (listOf(recordId, recordId) + args).toTypedArray()).use { it.moveToFirst() }
    }

    /** Scope invalidation may ignore only identities whose entire retained history is configuration. */
    fun entirelyConfiguration(recordIdExpression: String): Pair<String, List<String>> {
        require(recordIdExpression == "source_scope_changes.record_id")
        val (condition, args) = configuration("configuration_revision")
        return """EXISTS (SELECT 1 FROM records configuration_head WHERE configuration_head.id=$recordIdExpression)
            AND NOT EXISTS (SELECT 1 FROM record_revisions configuration_revision
                WHERE configuration_revision.record_id=$recordIdExpression AND NOT ($condition))""" to args
    }

    private fun configuration(revisionAlias: String): Pair<String, List<String>> {
        require(revisionAlias in setOf("r", "configuration_revision"))
        val parent = "q.template_id=$revisionAlias.template_id AND q.version=$revisionAlias.template_version"
        val clauses = mutableListOf("$revisionAlias.template_id IS NOT NULL",
            "(SELECT COUNT(*) FROM template_questions q WHERE $parent)=${TopicAliasTemplate.draft.questions.size}")
        val args = mutableListOf<String>()
        TopicAliasTemplate.draft.questions.forEachIndexed { index, question ->
            clauses += "EXISTS (SELECT 1 FROM template_questions q WHERE $parent AND q.ordinal=$index AND q.question_id=? AND q.prompt=? AND q.hint=? AND q.intent=?)"
            args += listOf(question.id, question.prompt, question.hint, question.intent)
        }
        return clauses.joinToString(" AND ") to args
    }
}
