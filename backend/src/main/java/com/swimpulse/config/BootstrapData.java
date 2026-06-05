package com.swimpulse.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class BootstrapData {
	/*
	 * BootstrapData originally seeded early local demo pools/events when the database
	 * was empty. The project now uses real facility rows, so sample-data bootstrap is
	 * intentionally disabled to avoid overwriting crawled or verified production-like
	 * values such as homepageUrl.
	 */
}
