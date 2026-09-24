package com.github.jing332.tts_server_android.lyric

import android.content.Context
import com.github.jing332.common.utils.FileUtils.readAllText
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.SpeechRule
import com.github.jing332.tts_server_android.model.rhino.speech_rule.SpeechRuleEngine

/**
 * 内置朗读规则：让「旁白/对话」这条规则一直在库里，不必手工保存一次。
 *
 * 原来这份脚本只是编辑器打开时的预填模板（SpeechRuleEditScreen 读 assets 填进编辑框），
 * 不点保存就不会落进数据库。所以导入备份之后总要手动新建再保存一遍。
 *
 * 这里在进程启动时补一遍：库里没有这条 ruleId 就照着内置脚本建一条并启用。
 * 已经有（无论启用还是禁用）就不动，免得覆盖用户自己改过的版本。
 */
object LyricDefaultRule {

    /** 与 assets/defaultData/speech_rule.js 里的 id 保持一致。 */
    const val RULE_ID = "ttsrv.multi_voice"

    private const val ASSET = "defaultData/speech_rule.js"

    /**
     * @return true 表示这次真的新建了一条规则
     */
    fun ensure(context: Context): Boolean = runCatching {
        val dao = dbm.speechRuleDao
        val all = dao.all
        if (all.any { it.ruleId == RULE_ID }) {
            false
        } else {
            val code = context.assets.open(ASSET).readAllText()
            if (code.isBlank()) {
                false
            } else {
                val rule = SpeechRule(isEnabled = true, code = code)
                SpeechRuleEngine(context, rule).evalInfo()
                rule.order = (all.maxOfOrNull { it.order } ?: 0) + 1
                dao.insert(rule)
                true
            }
        }
    }.getOrDefault(false)
}
