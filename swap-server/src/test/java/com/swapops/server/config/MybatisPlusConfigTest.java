package com.swapops.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.swapops.server.common.utils.PageParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页拦截器装配测试。
 *
 * <p>这组断言的存在理由：缺失 {@link PaginationInnerInterceptor} 时 {@code selectPage}
 * <b>不抛异常</b>，只是静默返回全表且 total=0，任何"接口返回 200 且字段齐全"的断言都抓不到
 * （本项目 10 处分页曾长期如此）。这里把装配本身钉死，配置被误删即在单测阶段变红。
 */
@DisplayName("MyBatis-Plus 插件装配")
class MybatisPlusConfigTest {

    private final List<InnerInterceptor> interceptors =
            new MybatisPlusConfig().mybatisPlusInterceptor().getInterceptors();

    @Test
    @DisplayName("分页拦截器已注册（缺失即 selectPage 静默退化为全表查询）")
    void 分页拦截器已注册() {
        assertThat(interceptors).anySatisfy(item -> assertThat(item).isInstanceOf(PaginationInnerInterceptor.class));
    }

    @Test
    @DisplayName("方言显式为 MySQL，不依赖运行时自动探测")
    void 方言显式指定() {
        assertThat(pagination().getDbType()).isEqualTo(DbType.MYSQL);
    }

    @Test
    @DisplayName("单页上限与 PageParams.MAX_LIMIT 一致（兜住绕过应用层钳制的 new Page 调用点）")
    void 单页上限兜底() {
        assertThat(pagination().getMaxLimit()).isEqualTo((long) PageParams.MAX_LIMIT);
    }

    @Test
    @DisplayName("overflow 关闭：页码越界返回空列表，而不是静默回到首页")
    void 页码越界不回首页() {
        assertThat(pagination().isOverflow()).isFalse();
    }

    @Test
    @DisplayName("防全表更新/删除拦截器已注册（全仓写操作均带 where，只拦事故不拦业务）")
    void 防全表写拦截器已注册() {
        assertThat(interceptors).anySatisfy(item -> assertThat(item).isInstanceOf(BlockAttackInnerInterceptor.class));
    }

    @Test
    @DisplayName("顺序：改写 SQL 的分页在前，纯校验的防全表写在后（官方建议顺序）")
    void 拦截器顺序() {
        int paginationIndex = -1;
        int blockAttackIndex = -1;
        for (int i = 0; i < interceptors.size(); i++) {
            if (interceptors.get(i) instanceof PaginationInnerInterceptor) {
                paginationIndex = i;
            }
            if (interceptors.get(i) instanceof BlockAttackInnerInterceptor) {
                blockAttackIndex = i;
            }
        }
        assertThat(paginationIndex).isNotNegative();
        assertThat(blockAttackIndex).isNotNegative();
        assertThat(paginationIndex).isLessThan(blockAttackIndex);
    }

    @Test
    @DisplayName("装配为单例 Bean 语义：多次调用不共享可变状态")
    void 每次装配独立() {
        MybatisPlusInterceptor first = new MybatisPlusConfig().mybatisPlusInterceptor();
        MybatisPlusInterceptor second = new MybatisPlusConfig().mybatisPlusInterceptor();
        assertThat(first).isNotSameAs(second);
        assertThat(first.getInterceptors()).hasSize(second.getInterceptors().size());
    }

    private PaginationInnerInterceptor pagination() {
        return interceptors.stream()
                .filter(PaginationInnerInterceptor.class::isInstance)
                .map(PaginationInnerInterceptor.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("分页拦截器未注册"));
    }
}
