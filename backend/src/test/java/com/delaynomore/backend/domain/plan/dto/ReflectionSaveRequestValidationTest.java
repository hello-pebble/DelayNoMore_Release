package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.ReflectionDifficulty;
import com.delaynomore.backend.domain.plan.entity.ReflectionReason;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// 회고 저장 요청 검증 단위 테스트 — Spring 컨텍스트 없이 순수 Validator로 돌린다.
// 선택지의 소스오브트루스는 enum(ReflectionDifficulty/ReflectionReason)이지만 @Pattern은
// 컴파일 상수만 받으므로, regexp가 enum 정의와 어긋나지 않는지(드리프트)를 여기서 가드한다.
class ReflectionSaveRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<ConstraintViolation<ReflectionSaveRequest>> violations(String difficulty, String reason) {
        return validator.validate(new ReflectionSaveRequest(difficulty, reason));
    }

    // 레코드 컴포넌트의 @Pattern.regexp를 읽는다 — 드리프트 가드 전용.
    private static String patternOf(String componentName) throws NoSuchFieldException {
        return ReflectionSaveRequest.class.getDeclaredField(componentName)
                .getAnnotation(Pattern.class).regexp();
    }

    @Test
    void 검증_유효한코드조합_통과() {
        // given — enum의 모든 코드가 실제로 저장 가능한지 전수 확인
        for (ReflectionDifficulty difficulty : ReflectionDifficulty.values()) {
            for (ReflectionReason reason : ReflectionReason.values()) {
                // when / then
                assertThat(violations(difficulty.name(), reason.name()))
                        .as("%s / %s", difficulty, reason).isEmpty();
            }
        }
    }

    @Test
    void 검증_허용외코드_위반() {
        // given — 오타·소문자·빈 값
        assertThat(violations("easy", "AS_PLANNED")).isNotEmpty();
        assertThat(violations("EASY", "NO_REASON")).isNotEmpty();
        assertThat(violations("", "")).isNotEmpty();
    }

    @Test
    void 검증패턴_difficulty_enum정의와일치() throws NoSuchFieldException {
        // when / then — enum에 값을 추가·삭제하면 이 테스트가 @Pattern 갱신을 강제한다
        assertThat(patternOf("difficulty")).isEqualTo(
                Arrays.stream(ReflectionDifficulty.values())
                        .map(Enum::name)
                        .collect(Collectors.joining("|")));
    }

    @Test
    void 검증패턴_reason_enum정의와일치() throws NoSuchFieldException {
        // when / then
        assertThat(patternOf("reason")).isEqualTo(
                Arrays.stream(ReflectionReason.values())
                        .map(Enum::name)
                        .collect(Collectors.joining("|")));
    }

    @Test
    void 메타응답_enum코드라벨쌍_반환() {
        // when
        ReflectionOptionsResponse response = ReflectionOptionsResponse.create();

        // then — 메타 API가 내려주는 코드·라벨이 enum 정의 순서 그대로인지(프론트 렌더 순서 보장)
        assertThat(response.difficulties())
                .containsExactly(
                        new MetaOptionResponse("EASY", "여유로웠어요"),
                        new MetaOptionResponse("NORMAL", "적당했어요"),
                        new MetaOptionResponse("HARD", "벅찼어요"));
        assertThat(response.reasons()).extracting(MetaOptionResponse::code)
                .containsExactly("AS_PLANNED", "NOT_ENOUGH_TIME", "TOO_MUCH_WORK",
                        "HARD_TO_FOCUS", "HARDER_THAN_EXPECTED");
    }
}
