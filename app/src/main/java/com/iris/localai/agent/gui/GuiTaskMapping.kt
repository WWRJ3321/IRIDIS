package com.iris.localai.agent.gui

import com.iris.localai.agent.GuiTaskResult
import com.iris.localai.agent.GuiTaskStatus

/** 2.1.9: a GUI loop outcome → what the chat's `screen` call reports. Pure. */
object GuiTaskMapping {
    fun head(stop: GuiStop): String = when (stop) {
        GuiStop.DONE -> "完成"; GuiStop.GAVE_UP -> "未完成"; GuiStop.MAX_STEPS -> "达到步数上限"
        GuiStop.STOPPED -> "已停止"; GuiStop.ERROR -> "出错"
    }

    /** [handedOver]: the run ended at a 交给你操作 / 模型提问 point the user did not finish in the overlay. */
    fun result(o: GuiOutcome, handedOver: Boolean): GuiTaskResult {
        val status = when (o.stop) {
            GuiStop.DONE -> GuiTaskStatus.DONE
            GuiStop.GAVE_UP, GuiStop.MAX_STEPS, GuiStop.ERROR -> GuiTaskStatus.FAILED
            GuiStop.STOPPED -> if (handedOver) GuiTaskStatus.TAKEN_OVER else GuiTaskStatus.STOPPED
        }
        val asked = o.steps.lastOrNull { it.action is GuiAction.Ask }?.action as? GuiAction.Ask
        val msg = if (status == GuiTaskStatus.TAKEN_OVER && asked != null) "模型提问：${asked.question}" else o.message
        return GuiTaskResult(status, msg, o.steps.count { it.status != GuiStepStatus.FORMAT_ERROR })
    }
}
