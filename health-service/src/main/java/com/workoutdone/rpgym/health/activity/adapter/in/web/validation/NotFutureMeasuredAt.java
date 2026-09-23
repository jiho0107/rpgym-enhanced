package com.workoutdone.rpgym.health.activity.adapter.in.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;

/**
 * 측정 시점이 미래가 아닌지 검증한다.
 *
 * 기기 시계는 서버와 조금씩 어긋나므로 @PastOrPresent처럼 엄격하게 막지 않고
 * toleranceSeconds 만큼은 허용한다.
 *
 * 미래 시점이 한 번 저장되면 그날의 순서 판단이 모두 그 행 기준으로 틀어진다.
 * - findLatestSnapshot이 그 행을 "최신"으로 잡아 오늘 조회가 그 값에 고정된다.
 * - DailyHealthSummary.lastSyncedAt이 미래로 잡혀, 이후 정상 동기화가
 *   "이전 시점 데이터"로 판단되어 요약과 목표 진행도가 갱신되지 않는다.
 * 저장 후에는 되돌릴 방법이 없으므로 요청 단계에서 거부한다.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotFutureMeasuredAt.Validator.class)
public @interface NotFutureMeasuredAt {

    String message() default "측정 시점은 현재 시각 이후일 수 없습니다.";

    /** 서버 시각보다 앞서도 허용하는 오차 (초) */
    long toleranceSeconds() default 300;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NotFutureMeasuredAt, Instant> {

        private Duration tolerance;

        @Override
        public void initialize(NotFutureMeasuredAt annotation) {
            this.tolerance = Duration.ofSeconds(annotation.toleranceSeconds());
        }

        @Override
        public boolean isValid(Instant value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            Instant now = context.getClockProvider().getClock().instant();
            return !value.isAfter(now.plus(tolerance));
        }
    }
}