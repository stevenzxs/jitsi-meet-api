package com.jitsimeet.api.lifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("lifecycle")
public record LifecycleProperties(Map<String, Credential> keys, List<String> mainDomains, List<String> breakoutDomains) {
    public record Credential(String sourceId, String secretBase64) {
        @Override public String toString() { return "Credential[redacted]"; }
    }
}
