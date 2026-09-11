package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.slack.repository.InMemorySlackRepository;
import com.delaynomore.backend.domain.slack.repository.SlackRepository.SlackLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SlackLinkServiceTest {

    private InMemorySlackRepository repository;
    private SlackLinkService service;

    @BeforeEach
    void setUp() {
        repository = new InMemorySlackRepository();
        service = new SlackLinkService(repository);
    }

    @Test
    void 발급된_코드로_연결하면_링크가_만들어지고_코드는_재사용할_수_없다() {
        String code = service.issueCode("user-1");
        assertThat(code).hasSize(8);

        Optional<SlackLink> linked = service.linkByCode(code, "T1", "U1", "D1");
        assertThat(linked).isPresent();
        assertThat(linked.get().owner()).isEqualTo("user-1");
        assertThat(linked.get().channelId()).isEqualTo("D1");

        // 같은 코드 재입력 — 소비된 코드는 두 번 성공할 수 없다.
        assertThat(service.linkByCode(code, "T1", "U2", "D2")).isEmpty();
    }

    @Test
    void 재발급하면_이전_코드는_무효가_된다() {
        String first = service.issueCode("user-1");
        String second = service.issueCode("user-1");

        assertThat(service.linkByCode(first, "T1", "U1", "D1")).isEmpty();
        assertThat(service.linkByCode(second, "T1", "U1", "D1")).isPresent();
    }

    @Test
    void 없는_코드는_연결되지_않는다() {
        assertThat(service.linkByCode("XXXXXXXX", "T1", "U1", "D1")).isEmpty();
    }

    @Test
    void 재연결은_기존_활동시간을_보존한다() {
        String first = service.issueCode("user-1");
        service.linkByCode(first, "T1", "U1", "D1");
        // 활동시간을 바꿔 둔 상태를 흉내낸다.
        repository.upsertLink(new SlackLink("user-1", "T1", "U1", "D1", 600, 1320));

        String second = service.issueCode("user-1");
        Optional<SlackLink> relinked = service.linkByCode(second, "T1", "U1-new", "D2");

        assertThat(relinked).isPresent();
        assertThat(relinked.get().activeStartMin()).isEqualTo(600);
        assertThat(relinked.get().activeEndMin()).isEqualTo(1320);
        assertThat(relinked.get().slackUserId()).isEqualTo("U1-new");
    }

    @Test
    void 같은_슬랙_계정을_다른_owner가_연결하면_이전_연결이_해제된다() {
        service.linkByCode(service.issueCode("user-1"), "T1", "U1", "D1");
        service.linkByCode(service.issueCode("user-2"), "T1", "U1", "D1");

        assertThat(service.findByOwner("user-1")).isEmpty();
        assertThat(service.findByOwner("user-2")).isPresent();
    }
}
