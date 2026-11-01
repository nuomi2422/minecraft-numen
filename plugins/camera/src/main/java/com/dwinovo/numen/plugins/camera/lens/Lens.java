package com.dwinovo.numen.plugins.camera.lens;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 镜头之外的两件事：<b>亮度</b>和<b>夜视</b>。
 *
 * <h2>为什么亮度不是「拿手电筒照」</h2>
 * 亮度是客户端渲染参数（{@code options.gamma}），改它只影响本机画面，不动世界、不动光照，
 * 也不影响同伴看见什么。夜视不一样：它是实体状态（{@code NIGHT_VISION}），会改变渲染出的
 * 颜色和暗部细节。<b>两者都是拍摄侧的干预</b>，按项目纪律必须落一条记录
 * （{@code config/numen_api/cam_interventions.log}），不能混进「自然拍摄」的取证里。
 *
 * <h2>为什么要把原值存下来</h2>
 * gamma 是写进 {@code options.txt} 的全局设置。改完不还原，用户下次开游戏会发现自己
 * 世界亮得不对劲，而且根本想不起是谁改的。所以：第一次改之前记下原值，{@link #restore()}
 * 时精确写回；退出世界也自动还原。
 *
 * <h2>为什么夜视要判断「本来有没有」</h2>
 * 如果服务端或药水本来给了夜视，我们退出时不该把它一起收掉。只有「我们加的那一个」
 * 才由我们删。
 */
public final class Lens {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** gamma 的合法范围。1.21 的「亮度」滑条就是 0..1。 */
    private static final double GAMMA_MIN = 0.0;
    private static final double GAMMA_MAX = 1.0;

    /** 原 gamma。NaN = 还没动过。 */
    private static volatile float savedGamma = Float.NaN;

    /** 夜视是不是我们加的。 */
    private static volatile boolean nvOurs;

    /** 加夜视之前玩家本来就有没有。 */
    private static volatile boolean nvPreexisting;

    private static volatile String status = "默认（没改亮度和夜视）";

    private Lens() {
    }

    public static String status() {
        return status;
    }

    /** 当前 gamma（没进世界时返回 -1）。 */
    public static double gamma() {
        Minecraft mc = Minecraft.getInstance();
        return mc.options == null ? -1 : mc.options.gamma().get();
    }

    public static boolean nightVision() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasEffect(MobEffects.NIGHT_VISION);
    }

    /** 设亮度。返回给指令/HTTP 的一句话。必须在客户端线程调用。 */
    public static String setGamma(double g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return "还没进世界";
        }
        double v = Math.max(GAMMA_MIN, Math.min(GAMMA_MAX, g));
        if (Float.isNaN(savedGamma)) {
            savedGamma = mc.options.gamma().get().floatValue();
        }
        mc.options.gamma().set(v);
        mc.options.save();
        status = String.format("亮度 %.2f（原值 %.2f，/cam off 或退世界自动还原）", v, savedGamma);
        intervene("gamma=" + String.format("%.2f", v));
        return status;
    }

    /** 开/关夜视。必须在客户端线程调用。 */
    public static String setNightVision(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return "还没进世界";
        }
        if (on) {
            if (mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
                status = "夜视已开（本来就有，不是我们加的）";
                return status;
            }
            nvPreexisting = false;
            // 无限时长、无粒子、不显示图标以外的其它副作用。客户端加的效果只影响渲染，
            // 不会同步到服务端，也不会让同伴/怪物「看不见你」这种判定变化。
            mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false));
            nvOurs = true;
            status = "夜视已开（拍摄侧干预，已记录）";
            intervene("night_vision=on");
            return status;
        }
        if (nvOurs) {
            mc.player.removeEffect(MobEffects.NIGHT_VISION);
            nvOurs = false;
            status = "夜视已关";
            intervene("night_vision=off");
            return status;
        }
        status = "夜视没开（没东西可关）";
        return status;
    }

    /** 精确还原亮度，并收掉我们加的夜视。幂等。 */
    public static void restore() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && !Float.isNaN(savedGamma)) {
            mc.options.gamma().set((double) savedGamma);
            mc.options.save();
            savedGamma = Float.NaN;
        }
        if (nvOurs && mc.player != null) {
            mc.player.removeEffect(MobEffects.NIGHT_VISION);
        }
        nvOurs = false;
        nvPreexisting = false;
        status = "默认（没改亮度和夜视）";
    }

    /** 退出世界时自动还原 —— 别把亮度留在别人的存档里。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null && (!Float.isNaN(savedGamma) || nvOurs)) {
            restore();
        }
    }

    /**
     * 落一条干预记录。
     *
     * <p>为什么单独一个文件而不是打日志：验收时要能一眼看出「这段画面不是自然光照」，
     * 而且这份记录要跟录像放在一起看。日志会被别的 mod 刷掉，文件不会。
     */
    private static void intervene(String what) {
        try {
            Path p = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("numen_api").resolve("cam_interventions.log");
            Files.createDirectories(p.getParent());
            String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\t" + what + "\n";
            Files.writeString(p, line, StandardCharsets.UTF_8,
                    Files.exists(p) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception e) {
            LOGGER.warn("[numencam] 干预记录写失败：{}", e.toString());
        }
    }
}
