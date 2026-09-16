package com.swapops.server.common.validation;

import com.swapops.server.order.form.CreateOrderForm;
import com.swapops.server.user.form.RechargeForm;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-7：表单参数校验注解单测（编程式 Validator，真实执行约束而非 mock）。
 */
@DisplayName("表单参数校验（P1-7）")
class FormValidationTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("CreateOrderForm：type 空/非法值被拒绝；合法 SWAP 通过")
    void createOrderForm() {
        CreateOrderForm blank = new CreateOrderForm();
        assertThat(fieldsOf(VALIDATOR.validate(blank))).contains("type");

        CreateOrderForm illegal = new CreateOrderForm();
        illegal.setType("FOO");
        assertThat(fieldsOf(VALIDATOR.validate(illegal))).contains("type");

        CreateOrderForm ok = new CreateOrderForm();
        ok.setType("SWAP");
        ok.setCabinetNo("SWAP-C-001");
        assertThat(VALIDATOR.validate(ok)).isEmpty();
    }

    @Test
    @DisplayName("RechargeForm：amountFen 空/0/负数被拒绝；正数通过")
    void rechargeForm() {
        RechargeForm blank = new RechargeForm();
        assertThat(fieldsOf(VALIDATOR.validate(blank))).contains("amountFen");

        RechargeForm zero = new RechargeForm();
        zero.setAmountFen(0);
        assertThat(fieldsOf(VALIDATOR.validate(zero))).contains("amountFen");

        RechargeForm negative = new RechargeForm();
        negative.setAmountFen(-5);
        assertThat(fieldsOf(VALIDATOR.validate(negative))).contains("amountFen");

        RechargeForm ok = new RechargeForm();
        ok.setAmountFen(100);
        assertThat(VALIDATOR.validate(ok)).isEmpty();
    }

    private static Set<String> fieldsOf(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
