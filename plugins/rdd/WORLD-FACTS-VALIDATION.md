# RDD 世界事实条件

`RddWorldFacts.matches(NumenPlayer, Map<String,Object>)` 由宿主 Detector 在服务端线程调用。它只读取事实，不发工具、不推进任务、不主动加载区块。非法输入、未知 ID、所需区块未加载、非服务端线程都不能通过。

| condition 示例 | 通过证据 | 不代表什么 |
|---|---|---|
| `{"type":"advancement","advancement":"minecraft:story/mine_diamond"}` | 当前 companion 自己的成就进度完成 | 不是主人获得成就，也不是背包推断 |
| `{"type":"entity_killed","entity":"minecraft:ender_dragon","minimum":1}` | 当前 companion 的原版 ENTITY_KILLED 统计达到正整数阈值 | 不是目标消失、卸载或旁人击杀；统计为该玩家累计值，不是当前任务增量 |
| `{"type":"structure","structure":"minecraft:stronghold","dimension":"minecraft:overworld"}` | 当前坐标在有效结构起点的包围盒内，当前块和起点块已经加载 | 不是 locate 找到一个远方坐标，不保证目标房间在当前位置 |
| `{"type":"base","dimension":"minecraft:overworld"}` | companion 本人重生点是完整、可站立重生的床，床周围 X/Z ±8、Y ±4 方块内放有箱子和熔炉 | 不是背包里有床和箱子；不宣称箱子里已有备用物资或建筑归属 |

`dimension` 对 structure/base 可省略；base 总是在本人重生维度检查。`minimum` 可省略，默认 1。ID 必须带命名空间，击杀 minimum 拒绝 0、负数、小数、无穷值及溢出。base 固定扫描范围，调用者不能扩大成本。base 所需区块没有全部加载时保守返回 false，稍后回来再验收。

## 实机验收矩阵（待主代理部署验证）

1. 相同成就只给主人解锁，Numen 条件仍 false；给 Numen 解锁后 true。
2. 未杀怪前 false；目标卸载或别人击杀仍 false；本人真实击杀后 true。重启后核对原版统计恢复。
3. locate 一个强要塞坐标不算到达；站进已加载有效结构包围盒后 true；错误结构 ID、错误维度 false。确认查询不增加加载区块。
4. 背包里携带床/箱子/炉子 false；已放方块但未给本人设重生点 false；完整设置且床有出口后 true。
5. 破坏床任一半、堵死重生出口、拆箱子或炉子后 false。箱子可以是普通或陷阱箱，熔炉必须是普通熔炉；不将末影箱/烟熏炉冒充该合同。
6. 离开基地使所需区块卸载后 false；返回加载后重新检查。不能为通过验收强制载入远端。

Java 编译只能证明 API 签名成立；这些场景尚未跑实机，不能标记 VERIFIED。
