package com.dwinovo.numen.plugins.selfcompile;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Compiles only sources already accepted by the deterministic source gate. */
public final class MutationCompiler {
    private static final long MAX_SOURCE_BYTES = 200_000L;

    public CompileResult compile(MutationManifest manifest) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        Path workspace = Path.of(manifest.workspace()).toAbsolutePath().normalize();
        Path sourceDir = workspace.resolve("source").normalize();
        Path classesDir = workspace.resolve("classes").normalize();
        if (!Files.isDirectory(sourceDir) || !Files.isDirectory(classesDir)) {
            return new CompileResult(false, List.of("source/classes directory missing"), -1);
        }
        List<Path> sources = new ArrayList<>();
        try (var stream = Files.list(sourceDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted().forEach(sources::add);
        }
        if (sources.isEmpty()) return new CompileResult(false, List.of("no Java source files"), -1);
        for (Path source : sources) {
            if (Files.size(source) > MAX_SOURCE_BYTES) {
                return new CompileResult(false, List.of("source exceeds 200000 bytes: " + source.getFileName()), -1);
            }
            MutationStaticChecker.CheckResult gate = MutationStaticChecker.check(
                    Files.readString(source, StandardCharsets.UTF_8));
            if (!gate.accepted()) return new CompileResult(false, gate.violations(), -1);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return new CompileResult(false, List.of("JDK compiler unavailable"), -1);
        List<String> args = new ArrayList<>();
        args.add("-encoding");
        args.add("UTF-8");
        args.add("-proc:none");
        args.add("-d");
        args.add(classesDir.toString());
        String classpath = System.getProperty("java.class.path", "");
        if (!classpath.isBlank()) {
            args.add("-classpath");
            args.add(classpath);
        }
        sources.forEach(path -> args.add(path.toString()));
        Path report = workspace.resolve("reports").resolve("compile.log");
        Files.createDirectories(report.getParent());
        // 用 ByteArrayOutputStream 收字节，**再按 javac 自己的 charset 解码**。
        //
        // 2026-09-30 实测修掉的 P1（本批唯一挡门禁的失败）：javac 的诊断文本用的是
        // **平台默认字符集**（本机是 GBK），而旧代码硬按 UTF-8 解码 →
        // "错误:" 变成 "?????:" → MutationErrorParser 两种语言都匹配不上 →
        // errors.json 写成 0 字节 → 外部 AI 拿不到任何定位信息（"自动改码"失败侧整个失效）。
        // 前一版只把"逐字节 write(int) 转 char"换成 ByteArrayOutputStream，修了流、没修字符集。
        //
        // 修法（2026-09-30 实测，探针见本文件末尾注释）：按 **native.encoding** 解码。
        // 不用 `-J-Duser.language=en` 强制英文 —— 那会丢掉中文错误原文，而人还要靠 compile.log 读懂错在哪。
        java.nio.charset.Charset charset = diagnosticCharset();
        var byteOut = new java.io.ByteArrayOutputStream();
        int exit = compiler.run(null, byteOut, byteOut, args.toArray(String[]::new));
        byte[] raw = byteOut.toByteArray();
        String output = new String(raw, charset);
        // compile.log 按**原始字节**落盘：它本来就是编译器写的那个字符集，
        // 再转一次码只会二次损坏（这正是旧 bug 的另一半）。
        Files.write(report, raw);
        return new CompileResult(exit == 0, output.isBlank()
                ? List.of() : List.of(output), exit);
    }

    /**
     * javac 诊断字节应该按哪个字符集解码。
     *
     * <p>javac 把诊断写进我们给的字节流时用的是 <b>native.encoding</b>（平台 ANSI 代码页），
     * <b>不是</b> {@code file.encoding}/{@code defaultCharset}。
     * JEP 400（JDK 18+）把 {@code file.encoding} 默认成了 UTF-8，但
     * {@code native.encoding} 仍跟随平台 —— 于是中文 Windows 上：
     * {@code file.encoding=UTF-8} 而 javac 实际吐 GBK 字节。
     *
     * <p>本机实测（JDK 21.0.11，中文 Windows）：
     * <pre>
     * file.encoding=UTF-8  native.encoding=GBK  defaultCharset=UTF-8
     * 同一段 javac 字节：按 UTF-8 解 → "错误:" 变成 "?????:"（解析器认不出，errors.json 空）
     *                     按 GBK  解 → "错误: 进行语法分析时已到达文件结尾"（正常）
     * </pre>
     *
     * <p>注意：{@code JavaCompiler.getCharset()}（听起来正合适）<b>在 JDK 21 里不存在</b>，
     * 它是更晚的版本才加的 —— 别再想用它。
     */
    private static java.nio.charset.Charset diagnosticCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null && !nativeEncoding.isBlank()) {
            try {
                return java.nio.charset.Charset.forName(nativeEncoding);
            } catch (RuntimeException unsupported) {
                // 落到下面
            }
        }
        java.nio.charset.Charset fallback = java.nio.charset.Charset.defaultCharset();
        return fallback == null ? StandardCharsets.UTF_8 : fallback;
    }

    public record CompileResult(boolean success, List<String> diagnostics, int exitCode) {}
}
