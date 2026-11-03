package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 近重复门的冻结用例（2026-11-03，取自 a8f7e752 的真实库）。
 *
 * <p>冻结样本的来历：库 24 条时，同机制重复 4 组 ——
 * {@code mine} 4 条（#8 #12 #20 #23）、{@code interact_at} 4 条（#10 #11 #13 #21）、
 * 夜间刷怪 2 条、女巫 2 条。下面的文本就是库里真实条目的 title+mechanism。
 */
class NearDuplicateGateTest {

    static final String MINE_A = "mine 是『寻路到已存在的方块再挖』，它不负责开辟通道：身体处在封闭、无出口的位置时，即使半径内找到 64 个 iron_ore，它也会以 'found N iron_ore but could not reach any of them from x,y,z — no path out, and nothing minable in place' 直接 0 进度失败；这不是参数被拒，换参数在同一点重试结果一样。 mine 是『寻路到已存在的方块再挖』，它不负责开辟通道：身体处在封闭、无出口的位置时，即使半径内找到 64 个 iron_ore，它也会以 'found N iron_ore but could not reach any of them from x,y,z — no path out, and nothing minable in place' 直接 0 进度失败；这不是参数被拒，换参数在同一点重试结果一样。";
    static final String MINE_B = "mine 只做『走到已经存在的目标方块旁把它挖掉』，不负责开路：身体处在封闭体里（四周无可站立连通面、也没有可挖的邻近方块）时，即使半径内探到 64 个 iron_ore，它也以 'could not reach any of them from x,y,z — no path out, and nothing minable in place' 直接 0 进度返回。这个失败跟着身体走、不跟着坐标走 —— 在同一个封闭体内换三个起点，报的还是同一句。要出货必须先把身体弄到有通路的开阔处（goto 到已知开阔点）或自己先挖出通道。 mine 只做『走到已经存在的目标方块旁把它挖掉』，不负责开路：身体处在封闭体里（四周无可站立连通面、也没有可挖的邻近方块）时，即使半径内探到 64 个 iron_ore，它也以 'could not reach any of them from x,y,z — no path out, and nothing minable in place' 直接 0 进度返回。这个失败跟着身体走、不跟着坐标走 —— 在同一个封闭体内换三个起点，报的还是同一句。要出货必须先把身体弄到有通路的开阔处（goto 到已知开阔点）或自己先挖出通道。";
    static final String MINE_C = "mine 只在已存在的地形网里『走到目标方块旁再挖』，不负责开路：身体处在封闭腔体（四周无可站立连通面、邻近也没有能挖穿的可挖方块）时，无论半径里找到多少 iron_ore，都以 'found 64 iron_ore+1 but could not reach any of them from x,y,z — no path out, and nothing minable in place' 0 进度失败。这个失败跟着身体走、不跟着坐标走：本例 -375,80,798 / -381,88,765 / -381,88,761 三个起点报的是同一句。恢复顺序固定：先把身体弄出封闭体（goto 到已知开阔点，或 rdd_teleport 把 x/z 保持不动、y 设到本区块地表 60~80），再重发 mine —— 不是继续重发 mine。 mine 只在已存在的地形网里『走到目标方块旁再挖』，不负责开路：身体处在封闭腔体（四周无可站立连通面、邻近也没有能挖穿的可挖方块）时，无论半径里找到多少 iron_ore，都以 'found 64 iron_ore+1 but could not reach any of them from x,y,z — no path out, and nothing minable in place' 0 进度失败。这个失败跟着身体走、不跟着坐标走：本例 -375,80,798 / -381,88,765 / -381,88,761 三个起点报的是同一句。恢复顺序固定：先把身体弄出封闭体（goto 到已知开阔点，或 rdd_teleport 把 x/z 保持不动、y 设到本区块地表 60~80），再重发 mine —— 不是继续重发 mine。";
    static final String SKELETON = "远程攻击（骷髅的箭）无法被近战格挡；在无顶暴露（canSeeSky=true）、无护甲、只有木剑的状态下，骷髅能从距离外持续命中，同伴既够不到它也不能减伤，最终被射杀。本例 dayTime=332 为白天，说明致死与昼夜无关，露天暴露才是关键。 远程攻击（骷髅的箭）无法被近战格挡；在无顶暴露（canSeeSky=true）、无护甲、只有木剑的状态下，骷髅能从距离外持续命中，同伴既够不到它也不能减伤，最终被射杀。本例 dayTime=332 为白天，说明致死与昼夜无关，露天暴露才是关键。";
    static final String WITCH = "女巫的 indirectMagic（药水）是远程持续伤害，近战武器无法格挡；在无护甲、露天无掩体（canSeeSky=true）且正在以 attack 正面接近时，魔法伤害累积到 hp=0。本例 dayTime=2004 为白天，与夜间刷怪无关，暴露与装备缺失才是主因。 女巫的 indirectMagic（药水）是远程持续伤害，近战武器无法格挡；在无护甲、露天无掩体（canSeeSky=true）且正在以 attack 正面接近时，魔法伤害累积到 hp=0。本例 dayTime=2004 为白天，与夜间刷怪无关，暴露与装备缺失才是主因。";
    static final String GOTO = "goto 只走『当前已能走通』的地形网：路径必须靠自己垫方块才能跨越的落差时，若可花费方块清单（scaffold_materials）里没有对应方块，它会以 'blocked: stopped N blocks short horizontally and M blocks above/below you — target is location x=.. z=..' 停在半路；把 cobblestone 一类廉价方块加进白名单后，同一次 goto 会在途中真的放下方块（本次自报 'En route I had to place 2 cobblestone'）并到达目标格。垂直差达数十格（本次 80）时，光有白名单也不够，必须先到可达的中间层。 goto 只走『当前已能走通』的地形网：路径必须靠自己垫方块才能跨越的落差时，若可花费方块清单（scaffold_materials）里没有对应方块，它会以 'blocked: stopped N blocks short horizontally and M blocks above/below you — target is location x=.. z=..' 停在半路；把 cobblestone 一类廉价方块加进白名单后，同一次 goto 会在途中真的放下方块（本次自报 'En route I had to place 2 cobblestone'）并到达目标格。垂直差达数十格（本次 80）时，光有白名单也不够，必须先到可达的中间层。";

    @Test void sameMechanismDifferentWordingIsDuplicate() {
        var d = NearDuplicateGate.check(MINE_A, "", List.of(MINE_B), List.of("mine v2"));
        assertTrue(d.duplicate(), "同一机制换措辞必须判重复，sim=" + d.similarity());
    }

    @Test void sameMechanismFarWordingStillLandsAboveSuspicious() {
        var d = NearDuplicateGate.check(MINE_A, "", List.of(MINE_C), List.of("mine v4"));
        assertTrue(d.duplicate() || d.suspicious(),
                "同机制的第四种写法至少要进疑似区间，sim=" + d.similarity());
    }

    @Test void differentRangedThreatsAreNotDuplicate() {
        var d = NearDuplicateGate.check(SKELETON, "", List.of(WITCH), List.of("witch"));
        assertFalse(d.duplicate(), "骷髅箭和女巫药水是不同机制，不能合并，sim=" + d.similarity());
        assertFalse(d.suspicious(), "也不该进疑似区间，sim=" + d.similarity());
    }

    @Test void differentToolsAreNotDuplicate() {
        var d = NearDuplicateGate.check(MINE_A, "", List.of(GOTO), List.of("goto"));
        assertFalse(d.duplicate(), "mine 和 goto 是不同工具，sim=" + d.similarity());
    }

    @Test void emptyLibraryNeverDuplicates() {
        var d = NearDuplicateGate.check(MINE_A, "", List.of(), List.of());
        assertFalse(d.duplicate());
        assertFalse(d.suspicious());
    }
}
