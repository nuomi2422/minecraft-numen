package com.dwinovo.numen.worldstate.core.store;

import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;

import java.util.List;
import java.util.Optional;

/**
 * 观测存储的读取契约。<b>读取永不触发采集</b>——这正是「读」与「取」分开的那条线。
 *
 * <p>写入必须遵守「旧刷新不得覆盖更新的观测」：由 {@link #put} 的实现在修订号上把关。
 */
public interface ObservationStore {

    Optional<Observation> get(ObservationKey key);

    /**
     * 写入一条观测。实现必须拒绝比现有观测更旧的修订号（防止迟到的旧刷新覆盖新结果）。
     * 
     * @return 是否真的写入了
     */
    boolean put(Observation observation);

    List<Observation> all();

    /**
     * 当前存储里的最大修订号（没有观测时返回 0）。
     *
     * <p>用途：进程重启后，{@code RefreshService} 的序列号要从这里**接着数**——
     * 否则新进程从 1 开始，而磁盘里载入的观测修订号早已是几十上百，{@code put} 会判定
     * "新观测更旧"而**静默丢弃一切刷新**（2026-11-05 实机事故：重启后观测永远刷不动）。
     */
    long maxRevision();

    int size();

    void clear();
}
