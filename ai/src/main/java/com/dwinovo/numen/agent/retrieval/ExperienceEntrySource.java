package com.dwinovo.numen.agent.retrieval;

import java.util.List;

/**
 * G5a 的「experience 侧拿得到条目」最小 SPI（75 号 §15.8-B4 的「API 兼容」缝）。
 *
 * <p>`ai` 模块与 `experience-core` 平级，不能直接 import ——
 * 所以这里定义一个独立 SPI，将来真 host 的 `ExperienceStore` 塞进来时，
 * 它把他们的 `ExperienceEntry` 转成 `ExperienceEntryRead`（B4 接线时的适配层写）。
 * 本批单测用合成 source 就够了。
 */
public interface ExperienceEntrySource {

    List<ExperienceEntryRead> allEntries();
}
