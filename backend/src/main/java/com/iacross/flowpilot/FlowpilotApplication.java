package com.iacross.flowpilot;

import com.iacross.flowpilot.shared.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing                                    // wires @CreatedDate / @LastModifiedDate
@EnableConfigurationProperties(JwtProperties.class)  // binds app.security.jwt.*
public class FlowpilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowpilotApplication.class, args);
    }
}
