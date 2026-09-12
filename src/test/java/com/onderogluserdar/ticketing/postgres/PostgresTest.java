package com.onderogluserdar.ticketing.postgres;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.onderogluserdar.ticketing.TicketingApplication;

@SpringBootTest(
        classes = TicketingApplication.class,
        // More connections than the concurrency test has workers: the default pool of 10 would cap
        // real parallelism and the no-oversell proof could pass without any locking at all.
        properties = "spring.datasource.hikari.maximum-pool-size=25")
@Import(PostgresTest.PostgresContainer.class)
abstract class PostgresTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainer {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18-alpine");
        }
    }
}
