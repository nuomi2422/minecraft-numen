package com.dwinovo.numen.plugins.worldstate.probe;

import java.util.List;

/**
 * 目录条目元数据：只描述「问什么」，不含实现。{@link ProbeService} 的 action=list
 * 与离线单测只读这一层，不加载 Minecraft。
 */
public record ProbeSpec(String id, String title, String description,
                        List<String> params, List<String> returns) {
}
