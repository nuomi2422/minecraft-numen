package com.dwinovo.numen.plugins.camera.api;

import com.dwinovo.numen.plugins.camera.cam.CameraRig;
import com.dwinovo.numen.plugins.camera.director.BodyCam;
import com.dwinovo.numen.plugins.camera.lens.Lens;
import com.dwinovo.numen.plugins.camera.move.FastMove;
import com.dwinovo.numen.plugins.camera.possess.Possession;
import com.dwinovo.numen.plugins.camera.rec.VideoRecorder;
import com.dwinovo.numen.plugins.camera.tether.CompanionTether;

/**
 * 一键全关：把相机模块<b>所有会留下后果的东西</b>一次性收回去。
 *
 * <h2>为什么必须有这个</h2>
 * 用户的原话：「以后我进游戏我不好关」。这是真问题 —— 相机模块同时会动的有六样东西：
 * 机位、牵引（含游戏档）、附身（含游戏档）、飞行/疾跑、亮度、夜视，外加录像。其中
 * <b>游戏档和 gamma 是会写进存档/配置的</b>，人卡在旁观者档里或者世界亮得不对劲，
 * 靠逐个 {@code /cam xxx off} 是关不干净的。
 *
 * <h2>为什么不是「让 AI 自己关」</h2>
 * 外部 AI 可能已经断开、超时、或者正在跑一个长镜头。这个开关必须<b>不依赖任何外部通道</b>：
 * 一条本地指令、纯客户端线程内执行，断网也能用。
 *
 * <p>顺序有讲究：先停录像（否则封盘异步还在跑），再收档位类的东西（牵引、附身），
 * 最后收视觉（亮度、夜视）。{@link CameraRig#setMode} 放中间，避免关了档位镜头还挂着。
 */
public final class AllOff {

    private AllOff() {
    }

    /** 必须在客户端线程调用。返回一行给人看的摘要。 */
    public static String run() {
        StringBuilder sb = new StringBuilder();

        if (VideoRecorder.recording()) {
            sb.append("录像已停（").append(VideoRecorder.stop()).append("）；");
        }

        if (CompanionTether.follow()) {
            sb.append(CompanionTether.setFollow(false)).append("；");
        } else if (CompanionTether.ownsSpectator() && !Possession.isActive()) {
            // 身体机位切过的旁观者档：牵引没开也要还回去，否则人会卡在旁观者
            sb.append(CompanionTether.restoreMode()).append("；");
        }
        BodyCam.reset();
        if (Possession.isActive()) {
            sb.append(Possession.release()).append("；");
        }

        CameraRig.setMode(CameraRig.Mode.OFF);
        CameraRig.setHoldWhenLost(true);

        FastMove.setFly(false);
        FastMove.setSprint(false);
        FastMove.setFlyMultiplier(1.0f);

        Lens.restore();

        String out = sb.length() == 0 ? "本来就是全关的（镜头 OFF、无牵引、无附身）" : sb.toString();
        return "全关：机位 OFF、牵引停、附身退、飞行疾跑复位、亮度夜视还原。"
                + (sb.length() == 0 ? "" : "\n细节：" + out);
    }
}
