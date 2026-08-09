package com.truecost.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Loads the reference data seed CSVs and exits, active only under the seed profile so a normal
 * application boot never touches this path. scripts/seed.sh runs it with
 * spring.main.web-application-type=none, so the process seeds and exits without ever opening a
 * port.
 */
@Component
@Profile("seed")
public class SeedRunner implements ApplicationRunner {

    private final SeedLoader seedLoader;
    private final ConfigurableApplicationContext context;

    public SeedRunner(SeedLoader seedLoader, ConfigurableApplicationContext context) {
        this.seedLoader = seedLoader;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedLoader.loadAll();
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
