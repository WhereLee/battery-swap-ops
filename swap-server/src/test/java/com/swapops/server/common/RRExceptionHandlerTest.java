package com.swapops.server.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端错误的状态码映射（批次43 补，AUD-22）。
 *
 * <p>实测背景：用 GET 打 `@PostMapping` 端点、以及 springdoc 关闭后访问 `/api/v3/api-docs`，
 * 原先都落进兜底分支变成 **500 "系统繁忙"**——客户端错误被伪装成服务端故障，
 * 监控会误告警、排障会先怀疑服务。本组钉住"框架级异常各归其位"。
 */
@DisplayName("全局异常映射：客户端错误不得变成 500")
class RRExceptionHandlerTest {

    private final RRExceptionHandler handler = new RRExceptionHandler();

    @Test
    @DisplayName("路径/资源不存在 -> 404（springdoc 关闭时的文档端点走这里）")
    void 资源不存在返回404() {
        ResponseEntity<Result<Void>> response =
                handler.handleNotFound(new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/v3/api-docs"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("方法不支持 -> 405（GET 打 POST 端点）")
    void 方法不支持返回405() {
        ResponseEntity<Result<Void>> response =
                handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("GET"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().getCode()).isEqualTo(405);
        assertThat(response.getBody().getMsg()).contains("GET");
    }

    @Test
    @DisplayName("参数缺失/类型不符 -> 400")
    void 参数问题返回400() {
        assertThat(handler.handleBadRequest(new MissingServletRequestParameterException("cabinetNo", "String"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handleBadRequest(new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "seq", null, new NumberFormatException("For input string: abc")))
                .getBody().getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("未预期异常仍是 500 且不外泄栈信息")
    void 未预期异常仍500() {
        ResponseEntity<Result<Void>> response = handler.handleOther(new IllegalStateException("内部细节不应外泄"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMsg()).isEqualTo("系统繁忙，请稍后重试");
        assertThat(response.getBody().getMsg()).doesNotContain("内部细节");
    }
}
