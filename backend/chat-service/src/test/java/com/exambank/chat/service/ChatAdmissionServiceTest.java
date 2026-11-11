package com.exambank.chat.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChatAdmissionServiceTest {
    @Test
    void providerAndGlobalPermitsAreBothRequiredAndReleased() throws Exception {
        ChatAdmissionService admission = new ChatAdmissionService(2, 2, 1, 1, new SimpleMeterRegistry());
        var first = admission.reserve("zhipu");
        assertThat(first).isNotNull(); assertThat(first.await()).isTrue();
        var otherProvider = admission.reserve("siliconflow");
        assertThat(otherProvider).isNotNull(); assertThat(otherProvider.await()).isTrue();
        assertThat(admission.activeCount()).isEqualTo(2);
        first.close(); otherProvider.close();
        assertThat(admission.activeCount()).isZero();
        var next = admission.reserve("zhipu");
        assertThat(next).isNotNull(); assertThat(next.await()).isTrue(); next.close();
    }

    @Test
    void queueOverflowIsRejectedWithoutLeakingPermit() throws Exception {
        ChatAdmissionService admission = new ChatAdmissionService(1, 0, 1, 1, new SimpleMeterRegistry());
        var active = admission.reserve("zhipu");
        assertThat(active.await()).isTrue();
        assertThat(admission.reserve("zhipu")).isNull();
        active.close();
        assertThat(admission.activeCount()).isZero();
    }
}
