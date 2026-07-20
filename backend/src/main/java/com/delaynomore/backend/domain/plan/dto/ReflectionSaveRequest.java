package com.delaynomore.backend.domain.plan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// 회고 저장 요청(PUT — 업서트). 완료/전체 개수 필드는 의도적으로 없다 —
// 서버가 plan.tasks에서 재계산하므로 클라이언트가 보낸 수치를 믿지 않는다.
// 선택지의 소스오브트루스는 ReflectionDifficulty/ReflectionReason enum이다. @Pattern은
// 컴파일 상수만 받아 enum을 직접 참조할 수 없으므로, regexp와 enum의 일치는
// ReflectionSaveRequestValidationTest의 드리프트 가드가 보장한다.
public record ReflectionSaveRequest(

        @NotBlank(message = "체감 난이도를 선택해주세요.")
        @Pattern(regexp = "EASY|NORMAL|HARD", message = "체감 난이도는 EASY/NORMAL/HARD 중 하나여야 합니다.")
        String difficulty,

        @NotBlank(message = "이유를 선택해주세요.")
        @Pattern(regexp = "AS_PLANNED|NOT_ENOUGH_TIME|TOO_MUCH_WORK|HARD_TO_FOCUS|HARDER_THAN_EXPECTED",
                message = "이유 선택지가 올바르지 않습니다.")
        String reason
) {
}
