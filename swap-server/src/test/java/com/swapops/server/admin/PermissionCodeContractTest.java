package com.swapops.server.admin;

import com.swapops.server.admin.enums.AdminRole;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限码契约测试（S8）：前端声明的权限码必须是后端码集的子集。
 *
 * <p>动机：前端路由 {@code meta.codes} 与按钮 {@code v-access} 用字符串写权限码，
 * 一旦拼错或后端改名，症状是"按钮永远不出现"或"点了才 403"——**这类漂移运行期无法发现**，
 * 故在 CI（mvn verify）用本测试做编译期门禁，而不是靠人工核对。
 */
@DisplayName("权限码契约（后端码集 ↔ 前端声明）")
class PermissionCodeContractTest {

    /** swap-web 由批次30 引入；不存在时本测试跳过（不阻断）。 */
    private static final Path FRONTEND_PERMISSIONS =
            Path.of("..", "swap-web", "src", "api", "permissions.ts");

    private static final Pattern CODE_PATTERN = Pattern.compile("\"(admin:[a-z0-9-]+:[a-z-]+)\"");

    @Test
    @DisplayName("后端码集本身：非空且为 admin:{域}:{操作} 三段式")
    void 后端码集命名合法() {
        assertThat(AdminRole.ALL_CODES).isNotEmpty();
        for (String code : AdminRole.ALL_CODES) {
            assertThat(code).as("权限码命名必须三段式").matches("^admin:[a-z0-9-]+:[a-z-]+$");
        }
        for (AdminRole role : AdminRole.values()) {
            assertThat(role.permissions()).as("%s 的码都在全集内", role).isSubsetOf(AdminRole.ALL_CODES);
        }
    }

    @Test
    @DisplayName("前端声明的权限码必须全部存在于后端码集（防漂移）")
    void 前端码必须是后端码子集() throws IOException {
        Assumptions.assumeTrue(Files.exists(FRONTEND_PERMISSIONS),
                "swap-web 尚未引入（批次30 起生效），跳过前端侧契约校验");

        String source = Files.readString(FRONTEND_PERMISSIONS, StandardCharsets.UTF_8);
        Matcher matcher = CODE_PATTERN.matcher(source);
        Set<String> declared = new LinkedHashSet<>();
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }

        assertThat(declared).as("permissions.ts 至少要声明一个权限码（否则本门禁形同虚设）").isNotEmpty();
        assertThat(declared).as("前端权限码必须是后端 AdminRole.ALL_CODES 子集，越界即前后端漂移")
                .isSubsetOf(AdminRole.ALL_CODES);
    }
}
