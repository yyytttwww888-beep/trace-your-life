package dev.tracelife.ui

import android.content.Intent
import android.os.Bundle
import dev.tracelife.knowledge.DossierSection
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeView
import dev.tracelife.knowledge.TopicDossier
import java.time.LocalDate

class TopicActivity : ScreenActivity() {
    private var topic = ""
    private var refresh = false
    override fun restoreScreenState(savedInstanceState: Bundle?) {
        topic = savedInstanceState?.getString("topic") ?: intent.getStringExtra(EXTRA_TOPIC).orEmpty()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("topic", topic); super.onSaveInstanceState(outState) }
    override fun onPause() { refresh = true; super.onPause() }
    override fun onResume() { super.onResume(); if (refresh && !busy) { refresh = false; showHome() } }
    override fun showHome() {
        beginScreen("topic", topic.ifBlank { "主题概况" })
        runIo({ TopicDossier(app.repository).open(topic) }) { dossier ->
            note("${dossier.records} 条经历 · ${dossier.experiences.total} 条经验线索")
            note("这里汇集已经保存的理解与尝试，每项都能回到当时的依据。")
            primaryButton("查找这个主题的内容", "searchTopic") {
                startActivity(Intent(this, DiscoveryActivity::class.java).putExtra(DiscoveryActivity.EXTRA_TOPIC, topic))
            }
            showKnowledge("目前留下的理解", dossier.understandings, "understandings", "还没有当前有效的理解。可以先回看已有经历，再分析值得留下的内容。")
            showKnowledge("需要关注", dossier.concerns, "concerns", "暂时没有保存过需要关注的问题；这不代表没有问题。")
            section("正在尝试 · ${dossier.actions.total}")
            if (dossier.actions.items.isEmpty()) note("还没有从这个主题的知识发起行动。打开一条理解，可以据此尝试一个具体做法。")
            dossier.actions.items.forEach { action ->
                val plan = action.value.draft
                listRow(root, "${plan.title}\n${plan.behavior}\n${if (action.originState != dev.tracelife.actions.ActionOriginState.CURRENT) "来源理解待核对 · " else ""}${if (action.due) "到期可回看" else "计划回看 ${plan.reviewOn}"}", "topicAction:${action.value.id}") {
                    startActivity(Intent(this, ActionsActivity::class.java).putExtra(ActionsActivity.EXTRA_ACTION_ID, action.value.id))
                }
            }
            if (dossier.actions.total > dossier.actions.items.size) button("查看全部行动", "allTopicActions") { startActivity(Intent(this, ActionsActivity::class.java)) }
            section("经验的变化 · ${dossier.experiences.total}")
            dossier.experiences.items.forEach { experience ->
                val current = experience.current.knowledge
                val summary = current?.value?.claim?.text ?: "原知识已删除，自己的说明仍保留"
                listRow(root, "${experience.thread.title}\n${experience.thread.version} 次积累 · ${if (current?.evidenceState == KnowledgeEvidenceState.CURRENT) "查看形成过程" else "采用的理解待更新"}\n${summary.take(160)}", "topicExperience:${experience.thread.id}") {
                    startActivity(Intent(this, ExperienceActivity::class.java).putExtra(ExperienceActivity.EXTRA_THREAD_ID, experience.thread.id))
                }
            }
            if (dossier.experiences.total == 0) note("还没有连接成线索的经验。新旧理解可以在知识详情中串起来。")
            if (dossier.experiences.total > dossier.experiences.items.size) button("全部经验线索", "allTopicExperiences") {
                startActivity(Intent(this, ExperienceActivity::class.java).putExtra(ExperienceActivity.EXTRA_TOPIC, topic))
            }
            showKnowledge("最近 30 天新保存", dossier.recent, "recent", "最近 30 天还没有新保存的有效理解。")
            if (dossier.pending.total > 0) showKnowledge("等待更新", dossier.pending, "pending", "")
            button("全部知识与原始经历", "allTopicKnowledge") {
                startActivity(Intent(this, KnowledgeActivity::class.java).putExtra(KnowledgeActivity.EXTRA_TOPIC, topic))
            }
            button("分析最近一周全部记录", "reviewTopicWeek") {
                startActivity(Intent(this, ReflectionActivity::class.java)
                    .putExtra(ReflectionActivity.EXTRA_FROM, LocalDate.now().minusDays(6).toString())
                    .putExtra(ReflectionActivity.EXTRA_THROUGH, LocalDate.now().toString()))
            }
            Ui.disclosure(root, "范围与缺口", "topicCoverage") { details ->
                Ui.note(details, "当前主题由已保存的标签定位；未分类或使用其他标签的经历不会被算作这个主题。最近保存按保存时间排列，不代表事情发生时间。")
                Ui.note(details, "本主题 ${dossier.unknownDates} 条记录的发生日期不确定。没有用条目数量计算成长或问题频率；每节先展示有限内容，完整条目可从目录查阅。")
            }
        }
    }
    private fun showKnowledge(title: String, values: DossierSection<KnowledgeView>, prefix: String, empty: String) {
        section("$title · ${values.total}")
        if (values.items.isEmpty()) note(empty)
        values.items.forEach { view ->
            val value = view.value
            listRow(root, "${value.claim.text}\n${value.scope.from} — ${value.scope.through}${if (view.evidenceState != KnowledgeEvidenceState.CURRENT) " · 待更新" else ""}", "$prefix:${value.id}") {
                startActivity(Intent(this, KnowledgeActivity::class.java).putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID, value.id)
                    .putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_REVISION, value.revision))
            }
        }
        if (values.total > values.items.size) button("在主题目录继续查看", "more:$prefix") {
            startActivity(Intent(this, KnowledgeActivity::class.java).putExtra(KnowledgeActivity.EXTRA_TOPIC, topic))
        }
    }
    companion object { const val EXTRA_TOPIC = "topic" }
}
