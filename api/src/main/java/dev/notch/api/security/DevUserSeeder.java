package dev.notch.api.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Creates the local developer account on startup if it does not exist yet. */
@Component
@Profile("local")
public class DevUserSeeder implements ApplicationRunner {

    private final JdbcClient jdbc;

    public DevUserSeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.sql("INSERT INTO users (id, handle) VALUES (:id, 'dev') ON CONFLICT (id) DO NOTHING")
                .param("id", DevUser.ID)
                .update();
    }
}
