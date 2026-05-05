package com.swimpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SwimPulseApplication {

	public static void main(String[] args) {
		SpringApplication.run(SwimPulseApplication.class, args);
	}

}
