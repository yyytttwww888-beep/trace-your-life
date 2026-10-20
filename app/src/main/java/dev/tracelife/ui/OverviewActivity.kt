package dev.tracelife.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import dev.tracelife.MainActivity
import dev.tracelife.overview.PersonalDirection
import dev.tracelife.overview.PersonalOverview
import dev.tracelife.overview.PersonalOverviewSnapshot
import dev.tracelife.profile.ProfileDimension
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The person-facing entry keeps original self-description separate from saved interpretations. */
class OverviewActivity : ScreenActivity() {
    private var editing = false
    private var recordId: String? = null
    private var recordRevision: Int? = null
    private var creationId = UUID.randomUUID().toString()
    private var stage = ""; private var goals = ""; private var attention = ""; private var signals = ""
    private var statedOn = LocalDate.now().toString(); private var reviewOn = ""
    private var zoneId = ZoneId.systemDefault().id
    private val inputs = mutableMapOf<String, EditText>()
    private var refresh = false
    private var resumed = false

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        editing = savedInstanceState.getBoolean("editing")
        recordId = savedInstanceState.getString("directionRecord")
        recordRevision = savedInstanceState.getInt("directionRevision").takeIf { it > 0 }
        creationId = savedInstanceState.getString("creationId") ?: creationId
        stage = savedInstanceState.getString("stage").orEmpty(); goals = savedInstanceState.getString("goals").orEmpty()
        attention = savedInstanceState.getString("attention").orEmpty(); signals = savedInstanceState.getString("signals").orEmpty()
        statedOn = savedInstanceState.getString("statedOn") ?: statedOn
        reviewOn = savedInstanceState.getString("reviewOn").orEmpty(); zoneId = savedInstanceState.getString("zoneId") ?: zoneId
    }

    override fun onSaveInstanceState(outState: Bundle) {
        capture()
        outState.putBoolean("editing", editing); outState.putString("directionRecord", recordId)
        outState.putInt("directionRevision", recordRevision ?: 0); outState.putString("creationId", creationId)
        outState.putString("stage", stage); outState.putString("goals", goals); outState.putString("attention", attention)
        outState.putString("signals", signals); outState.putString("statedOn", statedOn); outState.putString("reviewOn", reviewOn)
        outState.putString("zoneId", zoneId)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() { resumed = false; refresh = true; super.onPause() }
    override fun onResume() { super.onResume(); resumed = true; refreshIfNeeded() }
    private fun refreshIfNeeded() {
        if (resumed && refresh && !busy && !editing) { refresh = false; showHome() }
    }
    override fun showError(failure: Throwable) {
        if (resumed && refresh && !editing) refreshIfNeeded() else super.showError(failure)
    }
    override fun navigateBack() {
        if (busy) return
        if (editing) confirm("放弃这次修改？", "尚未保存的内容将不会保留。", "放弃修改") { editing = false; showHome() }
        else super.navigateBack()
    }

    override fun showHome() {
        if (editing) { showEditor(); return }
        inputs.clear()
        beginScreen("overview", "此刻的我")
        runIo({ PersonalOverview(app.repository, Clock.systemDefaultZone()).open() }) { show(it); refreshIfNeeded() }
    }

    private fun show(value: PersonalOverviewSnapshot) {
        note("从自己的方向出发，回看积累、尝试和变化。")
        val direction = value.direction
        val card = card()
        Ui.cardTitle(card, "当前方向")
        if (direction == null) Ui.note(card, "留下眼下的目标、条件与关注点。随时可以修改，不必一次想清楚。")
        else {
            Ui.note(card, "你在 ${direction.direction.statedOn} 留下的自述 · 第 ${direction.record.revision} 版")
            listOf("阶段与约束" to direction.direction.stage, "目标" to direction.direction.goals,
                "关注" to direction.direction.attention, "观察变化" to direction.direction.signals).forEach { (label, text) ->
                if (text.isNotBlank()) { Ui.fieldLabel(card, label); Ui.body(card, preview(text)) }
            }
            direction.direction.reviewOn?.let { Ui.note(card, if (direction.reviewDue) "$it 已到回看时间，可以看看方向是否仍适合。" else "计划 $it 再看一次") }
            addButton(card, "查看原文与修订", "directionSource") { openRecord(direction.record.id) }
        }
        addButton(card, if (direction == null) "写下当前方向" else "更新方向", "editDirection") {
            recordId = direction?.record?.id; recordRevision = direction?.record?.revision
            creationId = UUID.randomUUID().toString()
            stage = direction?.direction?.stage.orEmpty(); goals = direction?.direction?.goals.orEmpty()
            attention = direction?.direction?.attention.orEmpty(); signals = direction?.direction?.signals.orEmpty()
            statedOn = direction?.direction?.statedOn?.toString() ?: LocalDate.now().toString()
            reviewOn = direction?.direction?.reviewOn?.toString().orEmpty()
            zoneId = direction?.direction?.zoneId ?: ZoneId.systemDefault().id
            editing = true; showEditor()
        }

        section("已采用的理解 · ${value.profiles.total}")
        note("这些是你选择采用的画像，仍可修订；自述与行为解释的依据在原文中分别保留。")
        if (value.profiles.items.isEmpty()) note("还没有当前有效、已采用的画像。不需要为了完整而填满。")
        value.profiles.items.forEach { profile ->
            val knowledge = requireNotNull(profile.knowledge).value
            listRow(root, "${dimension(profile.value.draft.dimension)}\n${preview(knowledge.claim.text)}\n依据 ${knowledge.scope.from} — ${knowledge.scope.through}", "overviewProfile:${profile.value.id}") {
                openKnowledge(knowledge.id, knowledge.revision)
            }
        }
        button("查看画像与变化", "overviewProfiles") { startActivity(Intent(this, ProfileActivity::class.java)) }

        section("持续积累的经验 · ${value.experiences.total}")
        if (value.experiences.items.isEmpty()) note("将不同时期的理解接成一条线索，就能看见它如何变化。")
        value.experiences.items.forEach { item ->
            listRow(root, "${item.thread.title}\n${preview(requireNotNull(item.current.knowledge).value.claim.text)}", "overviewExperience:${item.thread.id}") {
                startActivity(Intent(this, ExperienceActivity::class.java).putExtra(ExperienceActivity.EXTRA_THREAD_ID, item.thread.id))
            }
        }
        button("全部经验线索", "overviewExperiences") { startActivity(Intent(this, ExperienceActivity::class.java)) }

        section("正在尝试 · ${value.actions.total}")
        if (value.actions.items.isEmpty()) note("还没有选定的行动。目标可以先保留为方向，不强制变成任务。")
        value.actions.items.forEach { item ->
            listRow(root, "${item.title}\n${if (item.due) "已到回看时间" else "计划 ${item.reviewOn} 回看"}", "overviewAction:${item.id}") {
                startActivity(Intent(this, ActionsActivity::class.java).putExtra(ActionsActivity.EXTRA_ACTION_ID, item.id))
            }
        }
        button("全部行动与回看", "overviewActions") { startActivity(Intent(this, ActionsActivity::class.java)) }

        section("最近留下的认识 · ${value.recent.total}")
        note("按最近 30 天保存时间展示，不把保存数量当作成长。")
        value.recent.items.forEach { item ->
            listRow(root, preview(item.value.claim.text), "overviewKnowledge:${item.value.id}") { openKnowledge(item.value.id, item.value.revision) }
        }
        if (value.recent.items.isEmpty()) note("最近还没有保存的有效认识。可以先写经历，再看看有什么值得留下。")
        if (value.pendingKnowledge > 0) {
            section("需要再看 · ${value.pendingKnowledge}")
            note("相关资料、反馈或适用时间有变化，旧理解不再作为当前结论。")
        }
        button("打开知识库", "overviewKnowledge") { startActivity(Intent(this, KnowledgeActivity::class.java)) }
        button("问一个自己的问题", "overviewQuestion") { startActivity(Intent(this, ConversationActivity::class.java)) }
        Ui.disclosure(root, "范围与来源", "overviewScope") { details ->
            Ui.note(details, "这里按页读取本机资料，每节只展示少量内容。自己的方向保存为带模板版本的普通记录；画像、经验和知识直接引用已保存内容，没有重新生成判断。完整内容可沿各入口查看。")
        }
    }

    private fun showEditor() {
        inputs.clear(); beginScreen("directionEditor", "当前方向")
        note("这是你的原话。可以只填一项，之后随着阶段变化修订。")
        fun input(key: String, label: String, value: String, hint: String = "", multiline: Boolean = true) {
            inputs[key] = labeledField(label, hint, value, multiline, if (multiline) 2_000 else 10).apply { tag = "direction:$key" }
        }
        input("stage", "当前阶段与约束", stage, "承担的角色、可用时间或现实条件")
        input("goals", "当前目标", goals, "希望维持、改变或达成什么")
        input("attention", "目前关注", attention, "需要留意，或值得保留的事情")
        input("signals", "如何观察变化", signals, "具体产出、一次尝试、休息感受……")
        input("statedOn", "声明日期", statedOn, "YYYY-MM-DD", false)
        input("reviewOn", "计划回看日期（可选）", reviewOn, "YYYY-MM-DD", false)
        primaryButton("保存方向", "saveDirection") {
            capture()
            val draft = runCatching { PersonalDirection(stage, goals, attention, signals, LocalDate.parse(statedOn),
                reviewOn.takeIf { it.isNotBlank() }?.let(LocalDate::parse), zoneId) }
            draft.fold({ value ->
                runIo({ PersonalOverview(app.repository, Clock.systemDefaultZone()).save(value, recordId, recordRevision, creationId) }) {
                    editing = false; showHome()
                }
            }, { showError(IllegalArgumentException("日期请使用 YYYY-MM-DD，回看日期也可以留空。")) })
        }
    }

    private fun capture() {
        if (!editing || inputs.isEmpty()) return
        fun read(key: String) = inputs[key]?.text?.toString().orEmpty()
        stage = read("stage"); goals = read("goals"); attention = read("attention"); signals = read("signals")
        statedOn = read("statedOn"); reviewOn = read("reviewOn")
    }
    private fun openRecord(id: String) { startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORD_ID, id)) }
    private fun openKnowledge(id: String, revision: Int) { startActivity(Intent(this, KnowledgeActivity::class.java)
        .putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID, id).putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_REVISION, revision)) }
    private fun preview(value: String): String = if (value.codePointCount(0, value.length) <= 240) value
        else value.substring(0, value.offsetByCodePoints(0, 240)) + "…（节选，点开查看完整内容）"
    private fun dimension(value: ProfileDimension) = when (value) {
        ProfileDimension.VALUES_AND_DIRECTION -> "价值与方向"
        ProfileDimension.ROLES_AND_CONTEXT -> "角色与背景"
        ProfileDimension.INTERESTS_AND_PREFERENCES -> "兴趣与偏好"
        ProfileDimension.BEHAVIOR_AND_DECISIONS -> "行为与决策"
        ProfileDimension.ABILITIES_AND_STRATEGIES -> "能力与方法"
        ProfileDimension.ENERGY_AND_RECOVERY -> "精力与恢复"
        ProfileDimension.RELATIONSHIPS_AND_COLLABORATION -> "关系与协作"
        ProfileDimension.NARRATIVE_AND_CHANGES -> "经历与变化"
    }
}
