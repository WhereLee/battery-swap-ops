package com.swapops.server.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全表 UPDATE/DELETE 守卫（批次43 补）。
 *
 * <p><b>为什么需要它</b>：批次30 给 MyBatis-Plus 挂了 {@code BlockAttackInnerInterceptor}
 * （防误操作全表 UPDATE/DELETE），但"加了拦截器"这件事**没有回归到所有既有语句上**：
 * {@code DevResetService} 里有三处"只有 SET、没有 WHERE"的全表语句，于是 {@code POST /api/dev/device/reset}
 * 从批次30 起**每次必然 500**（实测：`Prohibition of table update operation`）——一个被多个剧本依赖的
 * 联调端点，坏了很久没人发现，因为它的失败形态是"运行期 500"，而没有任何静态检查或单测覆盖它。
 *
 * <p>修那三处只是治标：只要拦截器还在，**任何新写的无谓词 update/delete 都会在运行期炸**。
 * 所以这里做静态守卫——扫描源码里的 {@code LambdaUpdateWrapper}/{@code LambdaQueryWrapper} 语句块，
 * 要求每块至少含一个条件方法。这是本仓库既有的做法（{@code AdminEndpointGuardTest}、
 * {@code TransactionalSelfInvocationGuardTest} 都是同类守卫），代价低、在 CI 里每次跑。
 *
 * <p>豁免：确需全表操作时在语句块里写注释 {@code guard:allow-full-table}（当前 0 处）。
 */
@DisplayName("守卫：不得出现无 WHERE 的全表 update/delete（BlockAttackInnerInterceptor 会在运行期拒绝）")
class MapperPredicateGuardTest {

    /** 语句块结束：`...));` 或 `...);` —— 取从调用点到第一个 `);` 的文本作为块。 */
    private static final Pattern STATEMENT = Pattern.compile(
            "(?s)(\\w*Dao|\\w*Mapper)\\.(update|delete)\\(\\s*(null|\\w+)\\s*,\\s*new\\s+Lambda(?:Update|Query)Wrapper.*?\\)\\s*;");

    /** 行注释与块注释：匹配前必须剥掉。 */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    /** 条件方法（MyBatis-Plus wrapper 上的谓词）。 */
    private static final Pattern PREDICATE = Pattern.compile(
            "\\.(eq|ne|gt|ge|lt|le|in|notIn|isNull|isNotNull|like|notLike|between|and|or|exists|notExists)\\(");

    private static final String ALLOW = "guard:allow-full-table";

    /**
     * 剥掉注释但保留换行（否则行号会漂）。豁免标记 {@code guard:allow-full-table} 写在注释里，
     * 所以豁免判定要在剥注释之前做——见调用点。
     */
    private static String stripComments(String text) {
        String withoutBlock = BLOCK_COMMENT.matcher(text).replaceAll(match -> "\n".repeat(countNewlines(match.group())));
        return LINE_COMMENT.matcher(withoutBlock).replaceAll("");
    }

    private static int countNewlines(String value) {
        return (int) value.chars().filter(c -> c == '\n').count();
    }

    private Path sourceRoot() {
        List<Path> candidates = List.of(
                Path.of("src", "main", "java"),
                Path.of("..", "swap-server", "src", "main", "java"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 swap-server 源码根目录（工作目录=" + Path.of("").toAbsolutePath() + "）");
    }

    @Test
    @DisplayName("源码里不存在无谓词的全表 update/delete")
    void noUnpredicatedFullTableWrite() throws IOException {
        Path root = sourceRoot();
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                // 豁免标记写在注释里 ⇒ 先在原文上判定豁免，再在"剥注释后的代码"上找语句与谓词。
                boolean fileAllows = raw.contains(ALLOW);
                // 先剥注释：负控实测发现，把谓词注释掉（`// .gt(...)`）能骗过最初的正则——
                // 一条"注释掉的 WHERE"当然不是 WHERE，守卫必须只看代码。
                String text = stripComments(raw);
                Matcher matcher = STATEMENT.matcher(text);
                while (matcher.find()) {
                    scanned++;
                    String block = matcher.group();
                    if (fileAllows) {
                        continue;
                    }
                    if (!PREDICATE.matcher(block).find()) {
                        long line = text.substring(0, matcher.start()).chars().filter(c -> c == '\n').count() + 1;
                        violations.add(root.relativize(file) + ":" + line + " -> "
                                + block.replaceAll("\\s+", " ").trim());
                    }
                }
            }
        }
        assertThat(scanned)
                .as("扫描到的 wrapper 语句数（守卫自身的覆盖率自检：为 0 说明正则或路径失效，那这道守卫就是摆设）")
                .isGreaterThan(20);
        assertThat(violations)
                .as("以下 update/delete 没有 WHERE 条件，运行期会被 BlockAttackInnerInterceptor 拒绝：\n%s",
                        String.join("\n", violations))
                .isEmpty();
    }
}
