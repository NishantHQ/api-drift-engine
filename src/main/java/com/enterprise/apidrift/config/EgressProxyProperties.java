package com.enterprise.apidrift.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Getter @Setter
@Component
@ConfigurationProperties(prefix = "egress.proxy")
public class EgressProxyProperties {

    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 30;
    private long maxPayloadSizeBytes = 20_971_520L; // 20 MB
    private List<String> blockedSubnets = List.of(
            "127.0.0.0/8", "0:0:0:0:0:0:0:1/128", "169.254.0.0/16",
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "fc00::/7", "fe80::/10"
    );
}
