package com.ezeebit.wallet;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same races against real MySQL 8 / InnoDB row locking. Skipped automatically when Docker
 * is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlConcurrencyTest extends ConcurrencyScenarios {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
}
