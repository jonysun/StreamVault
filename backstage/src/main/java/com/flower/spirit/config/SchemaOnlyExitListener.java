package com.flower.spirit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class SchemaOnlyExitListener {

    private final ConfigurableApplicationContext context;
    private final boolean schemaOnly;

    public SchemaOnlyExitListener(ConfigurableApplicationContext context,
            @Value("${streamvault.schema-only:false}") boolean schemaOnly) {
        this.context = context;
        this.schemaOnly = schemaOnly;
    }

    @Order(Integer.MAX_VALUE)
    @EventListener(ApplicationReadyEvent.class)
    public void exitAfterSchemaValidation() {
        if (schemaOnly) {
            SpringApplication.exit(context, () -> 0);
        }
    }
}
