package org.cardanofoundation.lob.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.math.BigDecimal;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.javers.core.Javers;

import org.junit.jupiter.api.Test;

class LobServiceAppTest {

    private final LobServiceApp app = new LobServiceApp();

    @Test
    void startupRunnerRuns() {
        assertThatNoException().isThrownBy(() -> app.onStart().run());
    }

    @Test
    void registersEtagFilterAndTimedAspect() {
        assertThat(app.new CacheConfig().shallowEtagHeaderFilter()).isNotNull();
        assertThat(app.new MetricsConfig().timedAspect(new SimpleMeterRegistry())).isNotNull();
    }

    @Test
    void schedulerUsesSharedJobThreadPool() {
        ScheduledTaskRegistrar registrar = app.new SchedulerConfig().scheduledTaskRegistrar();

        assertThat(registrar.getScheduler()).isInstanceOfSatisfying(ThreadPoolTaskScheduler.class, scheduler -> {
            assertThat(scheduler.getPoolSize()).isEqualTo(20);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("job-");
        });
    }

    @Test
    void providesSystemClock() {
        assertThat(app.new TimeConfig().clock()).isNotNull();
    }

    @Test
    void javersTreatsBigDecimalsOfDifferentScaleAsEqual() {
        Javers javers = app.new JaversConfig().javers();

        assertThat(javers.compare(new Amount(new BigDecimal("10.0")), new Amount(new BigDecimal("10.00"))).hasChanges()).isFalse();
        assertThat(javers.compare(new Amount(new BigDecimal("10.00")), new Amount(new BigDecimal("10.01"))).hasChanges()).isTrue();
    }

    @Test
    void buildsNetsuiteRestClient() {
        LobServiceApp.RestClientConfig config = app.new RestClientConfig();
        ReflectionTestUtils.setField(config, "netsuiteClientTimeoutSeconds", 5);

        assertThat(config.restClient()).isInstanceOf(RestClient.class);
    }

    record Amount(BigDecimal value) {
    }

}
