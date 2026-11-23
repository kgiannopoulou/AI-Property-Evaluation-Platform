package com.propertyapp.inspection;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param aiServiceUrl base URL of the TypeScript ai-service
 * @param aiServiceToken shared secret sent as a bearer token; never exposed to the browser
 * @param aiTimeout how long to wait for the vision analysis
 * @param photoDir directory where uploaded photos are stored (shared with the ai-service's local store)
 */
@ConfigurationProperties(prefix = "app.inspection")
public record InspectionProperties(String aiServiceUrl, String aiServiceToken, Duration aiTimeout, String photoDir) {}
