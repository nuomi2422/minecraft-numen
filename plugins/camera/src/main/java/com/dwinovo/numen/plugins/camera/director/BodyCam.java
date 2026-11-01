package com.dwinovo.numen.plugins.camera.director;

import com.dwinovo.numen.plugins.camera.cam.CameraRig;
import com.dwinovo.numen.plugins.camera.possess.Possession;
import com.dwinovo.numen.plugins.camera.tether.CompanionTether;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 身体机位：<b>不碰相机，只搬玩家</b>。
 *
 * <h2>和 CameraRig 那几种有什么不一样</h2>
 * SHOULDER / ORBIT / LOCK / CINEMATIC 都是「相机离开身体，自己算一个机位」——
 * 录制出来的画面<b>不是玩家眼睛看到的东西</b>。用户要的是「用我的身体直接拍」，
 * 所以这里反过来：相机保持原版（挂在玩家身上），AI 去搬玩家这个身体和它的朝向。
 * 这样拍到的每一帧，都是用户自己站在那儿会看到的画面。
 *
 * <h2>为什么必须先把人切成旁观者</h2>
 * 客户端改 {@code LocalPlayer} 的坐标只有旁观者档会被服务端原样接受（没有碰撞、
 * 没有重力、不会被摔落位移拉回）。生存/创造档里每 tick 强行 setPos 会和服务端
 * 的移动校正打架，表现是「一抽一抽」，而且会踩坏脚下的方块。
 *
 * <h2>为什么位置和朝向都要插值</h2>
 * 直接赋值是 20Hz 的瞬移：画面像幻灯片，而且 {@code Camera} 的 partial-tick 插值
 * 会把抖动放大。这里用一阶低通（每 tick 追 25%）把 20Hz 的台阶抹成连续运动。
 *
 * <h2>方位角在 BODY 下是相对的</h2>
 * {@link CameraRig#bearing()} 在这里不是世界方位，而是<b>相对目标朝向</b>的偏移：
 * 0° = 正后方（标准跟拍），±90° = 侧面，180° = 迎面。目标是会自己走路的同伴，
 * 它往哪走视角就跟到哪，这才叫「跟着跑」。
 *
 * <h2>坑</h2>
 * 目标解析不到（走出渲染距离/换维度）时<b>什么都不做</b>，保持当前站位 —— 不要
 * 归零、不要回原位，否则镜头会突然弹一下。
 */
public final class BodyCam {

    /** 每 tick 追多少。0.25 ≈ 200ms 量级收敛，够快又不抖。 */
    private static final double POS_LERP = 0.25;

    /** 朝向插值系数。比位置慢一点，转视角才不会「甩头」。 */
    private static final float ROT_LERP = 0.18f;

    private static Vec3 smoothed;
    private static float yawSm = Float.NaN;
    private static float pitchSm = Float.NaN;

    private BodyCam() {
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;

        if (CameraRig.mode() != CameraRig.Mode.BODY) {
            if (smoothed != null) {
                reset();
            }
            // 从 BODY 切走时把档位还回去（牵引在开就让牵引管，附身在让附身管）
            if (p != null && CompanionTether.ownsSpectator()
                    && !CompanionTether.follow() && !Possession.isActive()) {
                CompanionTether.restoreMode();
            }
            return;
        }

        if (p == null || mc.level == null) {
            return;
        }
        Entity t = CameraRig.resolveTarget();
        if (t == null) {
            return;   // 目标看不见：保持站位，别弹
        }
        if (!CompanionTether.ownsSpectator() && !p.isSpectator()) {
            CompanionTether.ensureSpectator();
        }

        // BODY 模式下 bearing 是「相对目标朝向」的偏移：0 = 正后方（跟拍），90 = 它右手边。
        // 为什么不像其他模式那样用世界方位角：跟拍的意义是「它往哪走镜头就跟到哪」；
        // 世界方位角固定在原地转，目标一转身就成了拍侧脸 —— 那是环绕，不是跟拍。
        double rad = Math.toRadians(t.getYRot() + 180.0 + CameraRig.bearing());
        double dist = Math.max(1.5, CameraRig.distance());
        double eyeH = p.getEyeHeight();
        // 想让「眼睛」落在目标身前 dist 格、高 height 的位置 → 反推脚在哪
        double eyeY = t.getY() + CameraRig.height() + 1.35;
        Vec3 stand = new Vec3(t.getX() + Math.cos(rad) * dist, eyeY - eyeH, t.getZ() + Math.sin(rad) * dist);

        Vec3 cur = smoothed == null ? p.position() : smoothed;
        Vec3 next = cur.add(stand.subtract(cur).scale(POS_LERP));
        if (next.distanceTo(stand) < 0.02) {
            next = stand;
        }
        smoothed = next;

        p.setPos(next.x, next.y, next.z);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0.0F;

        Vec3 eye = new Vec3(next.x, next.y + eyeH, next.z);
        // 瞄目标胸口（不是脚下，也不是头顶）：构图更稳，也不会把地平线顶出去
        Vec3 look = new Vec3(t.getX(), t.getY() + t.getEyeHeight() * 0.9, t.getZ());
        Vec3 d = look.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));

        if (Float.isNaN(yawSm)) {
            yawSm = yaw;
            pitchSm = pitch;
        }
        // 角度差取最短路径（-180..180），否则从 179° 到 -179° 会整圈甩过去
        float dy = (((yaw - yawSm) % 360f) + 540f) % 360f - 180f;
        yawSm += dy * ROT_LERP;
        pitchSm += (pitch - pitchSm) * ROT_LERP;

        p.setYRot(yawSm);
        p.setXRot(pitchSm);
    }

    public static void reset() {
        smoothed = null;
        yawSm = Float.NaN;
        pitchSm = Float.NaN;
    }

    public static String status() {
        return CameraRig.mode() == CameraRig.Mode.BODY ? "身体机位：相机=玩家，AI 在搬人" : "未启用";
    }
}
