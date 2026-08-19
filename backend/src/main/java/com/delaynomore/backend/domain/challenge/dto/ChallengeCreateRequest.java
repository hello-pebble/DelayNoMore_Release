package com.delaynomore.backend.domain.challenge.dto;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// 챌린지 개설 요청. 형식 검증은 여기서(@Valid) 하고, 위반은 400 + fieldErrors(GlobalExceptionHandler).
// participantCount·createdAt은 서버가 발급하므로 요청 바디로 받지 않는다.
public record ChallengeCreateRequest(

        @NotBlank(message = "챌린지 제목을 공백 제외 2자 이상 입력해주세요.")
        @Pattern(regexp = "\\s*\\S[\\s\\S]*\\S\\s*", message = "챌린지 제목을 공백 제외 2자 이상 입력해주세요.")
        String title,

        @NotNull(message = "기간은 1~365일 사이의 정수여야 합니다.")
        @Min(value = 1, message = "기간은 1~365일 사이의 정수여야 합니다.")
        @Max(value = 365, message = "기간은 1~365일 사이의 정수여야 합니다.")
        Integer durationDays,

        // 정원 상한 20은 데모 서버 보호용 — 경쟁을 보여주는 데 그 이상은 필요 없다.
        @NotNull(message = "모집 인원은 2~20명 사이의 정수여야 합니다.")
        @Min(value = 2, message = "모집 인원은 2~20명 사이의 정수여야 합니다.")
        @Max(value = 20, message = "모집 인원은 2~20명 사이의 정수여야 합니다.")
        Integer capacity,

        @NotNull(message = "참가비는 0~1000 포인트 사이의 정수여야 합니다.")
        @Min(value = 0, message = "참가비는 0~1000 포인트 사이의 정수여야 합니다.")
        @Max(value = 1000, message = "참가비는 0~1000 포인트 사이의 정수여야 합니다.")
        Integer entryFee
) {

    public Challenge toChallenge(String owner, String createdAt) {
        return new Challenge(null, owner, title.trim(), durationDays, capacity, entryFee, 0, createdAt);
    }
}
