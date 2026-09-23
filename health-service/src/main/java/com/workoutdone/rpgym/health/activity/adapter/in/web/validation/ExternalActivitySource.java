package com.workoutdone.rpgym.health.activity.adapter.in.web.validation;

import com.workoutdone.rpgym.health.activity.domain.ActivitySource;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 외부 요청으로 받을 수 있는 출처인지 검증한다.
 *
 * SYNTHETIC은 서버 내부 수집기만 생성하므로 클라이언트가 보내면 거부한다.
 * 허용하면 실제 기기 데이터와 가짜 데이터가 출처 값으로 구분되지 않는다.
 * null은 @NotNull이 담당하므로 여기서는 통과시킨다.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ExternalActivitySource.Validator.class)
public @interface ExternalActivitySource {

    String message() default "외부 요청으로 보낼 수 없는 데이터 출처입니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ExternalActivitySource, ActivitySource> {

        @Override
        public boolean isValid(ActivitySource value, ConstraintValidatorContext context) {
            return value == null || value.isExternal();
        }
    }
}