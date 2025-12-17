package org.test.stock.config

import io.micrometer.core.aop.TimedAspect
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MetricsConfig {

    /**
     * @Timed 어노테이션이 동작하도록 TimedAspect 빈 등록
     * 이 빈이 없으면 @Timed 어노테이션이 무시됨
     */
    @Bean
    fun timedAspect(registry: MeterRegistry): TimedAspect {
        return TimedAspect(registry)
    }
}
