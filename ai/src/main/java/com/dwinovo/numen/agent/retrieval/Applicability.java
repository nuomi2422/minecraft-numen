package com.dwinovo.numen.agent.retrieval;

/**
 * 75 号 §15.5 `Applicability`（PASS/FAIL/UNKNOWN）。
 *
 * <p><b>可用于行动的内容要求 PASS</b>；UNKNOWN 可呈现为待核实线索但不得自动执行。
 * G5a 最小 provider 里 <b>UNKNOWN 是默认</b>，PASS/FAIL 只由明确的
 * {@code applicableConditions}/{@code excludedConditions} 命中方式给出。
 */
public enum Applicability { PASS, FAIL, UNKNOWN }
