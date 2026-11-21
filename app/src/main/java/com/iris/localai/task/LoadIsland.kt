package com.iris.localai.task

import com.iris.localai.I18n
import com.iris.localai.R
import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Beta2：模型加载挂到统一任务进度，应用内灵动岛（以及其它显示面）就能显示「正在加载」。
 * 在对话页不上岛（对话页底部已有加载行），在模型、设置等页面加载时才看得到。
 */
object LoadIsland {
    const val ID = "model_load"

    /** 引擎状态 → 任务动作（纯函数，单测覆盖）。 */
    sealed interface Step {
        data class Begin(val name: String) : Step
        data class Progress(val percent: Int) : Step
        data class End(val phase: TaskProgress.Phase) : Step
        data object None : Step
    }

    fun step(running: Boolean, s: EngineState): Step = when (s) {
        is EngineState.Loading -> if (!running) Step.Begin(s.model.displayName) else Step.Progress((s.progress * 100).toInt().coerceIn(0, 100))
        is EngineState.Ready -> if (running) Step.End(TaskProgress.Phase.DONE) else Step.None
        is EngineState.Failed -> if (running) Step.End(TaskProgress.Phase.FAILED) else Step.None
        EngineState.Idle -> if (running) Step.End(TaskProgress.Phase.STOPPED) else Step.None
    }

    fun start(scope: CoroutineScope, engine: EngineManager) {
        scope.launch {
            engine.state.collect { s ->
                runCatching {
                    when (val st = step(TaskProgress.running(ID), s)) {
                        is Step.Begin -> {
                            TaskProgress.begin(ID, TaskProgress.Kind.OTHER, I18n.s(R.string.v30b2_island_load), st.name, total = 100) { engine.cancelLoad() }
                            TaskProgress.progress(ID, 0, 100)
                        }
                        is Step.Progress -> TaskProgress.progress(ID, st.percent, 100)
                        is Step.End -> TaskProgress.finish(ID, st.phase)
                        Step.None -> {}
                    }
                }
            }
        }
    }
}
