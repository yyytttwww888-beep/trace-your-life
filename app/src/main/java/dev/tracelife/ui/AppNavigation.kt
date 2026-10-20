package dev.tracelife.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** A compact mobile sidebar. Destinations retain their existing independent activity state. */
object AppNavigation {
    fun show(activity: Activity, templates: () -> Unit): Dialog {
        val dialog = Dialog(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(activity, 20), Ui.dp(activity, 16), Ui.dp(activity, 20), Ui.dp(activity, 24))
            setBackgroundColor(Ui.bg(activity))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            panel.addView(this)
        }
        Ui.screenTitle(header, "Trace Life").apply {
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        Ui.textButton(header, "×", "closeNavigation") { dialog.dismiss() }.apply {
            contentDescription = "收起菜单"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48))
            setPadding(0, 0, 0, 0)
        }
        fun row(label: String, tag: String, action: () -> Unit) {
            if (tag == "navRecords") {
                Ui.listRow(panel, label, tag) { dialog.dismiss(); action() }
            } else {
                Ui.textButton(panel, label, tag) { dialog.dismiss(); action() }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
            }
        }
        fun destination(label: String, tag: String, target: Class<out Activity>) =
            row(label, tag) { activity.startActivity(Intent(activity, target)) }

        Ui.sectionTitle(panel, "日常")
        row("记录", "navRecords") { }
        row("复盘模板", "navTemplates", templates)
        destination("分析", "navReflection", ReflectionActivity::class.java)
        destination("逐条整理", "navReview", ReviewActivity::class.java)
        Ui.sectionTitle(panel, "回看")
        destination("趋势", "navDashboard", DashboardActivity::class.java)
        destination("行动", "navActions", ActionsActivity::class.java)
        destination("回顾", "navPeriod", PeriodActivity::class.java)
        Ui.sectionTitle(panel, "资料")
        destination("知识库", "navKnowledge", KnowledgeActivity::class.java)
        destination("查找", "navDiscovery", DiscoveryActivity::class.java)
        destination("关于我", "navProfile", ProfileActivity::class.java)
        destination("备份与导出", "navTransfer", TransferActivity::class.java)
        destination("设置", "navSettings", SettingsActivity::class.java)
        dialog.setContentView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(panel)
        })
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Ui.bg(activity)))
            setGravity(Gravity.START)
            val width = minOf(Ui.dp(activity, 320), activity.resources.displayMetrics.widthPixels - Ui.dp(activity, 48))
            setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0.32f)
        }
        return dialog
    }
}
