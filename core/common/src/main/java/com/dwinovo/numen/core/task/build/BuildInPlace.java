package com.dwinovo.numen.core.task.build;

/**
 * 「就地施工」闸门（{@code build} 的 {@code in_place} 参数）。
 *
 * <p><b>为什么需要它</b>：{@code build} 的每一单都会先赴工地（TRAVEL：寻路到工地外沿）
 * 再施工，施工中还会在巡视点之间挪窝。于是「人已经在坑底、要在头顶就地封一格」这种动作
 * 有真实风险：任务可能在放置之前先把她挪出坑，然后封住一个空腔——而身体在坑外这件事
 * 事后才被发现，方块已经放进去了。</p>
 *
 * <p><b>它做什么</b>：只有两件事，都限定在「放置那一格之前」：</p>
 * <ol>
 *   <li>取消赴工地与巡走（就地开工），</li>
 *   <li>每格放置前核对：身体仍在本坑同列、脚的高度就是 {@code feetY}、目标格不压身体那两格。
 *       任一项不成立 → 整单失败，<b>不放置</b>。</li>
 * </ol>
 *
 * <p><b>它不是</b>：新的调度器、新的世界扫描、或通用的「别动」开关。材料/方块保护/让路档位
 * 等既有判据一律不变，它只在那些判据之前多问一句「身体还在这儿吗」。</p>
 *
 * <p>本类刻意只依赖四个整数，好让「闸门判据」能在游戏外直接测。</p>
 */
public record BuildInPlace(int x, int z, int feetY, int headY) {

    /** 身体是否仍锚在这个坑里：同列 + 脚的高度就在 feetY（±0.2 容差）。 */
    public boolean anchorsBodyAt(double bodyX, double bodyY, double bodyZ) {
        return (int) Math.floor(bodyX) == x
                && (int) Math.floor(bodyZ) == z
                && bodyY >= feetY - 0.2
                && bodyY <= feetY + 0.2;
    }

    /**
     * 目标格是否压着身体占用的两格（脚格/头格）。
     *
     * <p>只判同列同层：身体只可能站在 {@code (x, feetY..headY, z)} 这两格里，
     * 别的格即便相邻也不影响站立。</p>
     */
    public boolean clipsBodyCell(int tx, int ty, int tz) {
        return tx == x && tz == z && ty >= feetY && ty <= headY;
    }

    /** 头脚两格（身体占用的空间），供调用方核对这两格确实是空的。 */
    public boolean isBodyCell(int ty) {
        return ty >= feetY && ty <= headY;
    }
}
