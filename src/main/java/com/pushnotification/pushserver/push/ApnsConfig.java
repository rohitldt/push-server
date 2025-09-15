package com.pushnotification.pushserver.push;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsClientBuilder;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import io.netty.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
public class ApnsConfig {

    @Bean(destroyMethod = "close")
    public ApnsClient apnsClient(
            @Value("${apns.team-id:}") String teamId,
            @Value("${apns.key-id:}") String keyId,
            @Value("${apns.auth-key-path:}") String authKeyPath,
            @Value("${apns.use-sandbox:true}") boolean useSandbox
    ) throws Exception {
        ApnsClientBuilder builder = new ApnsClientBuilder();
        if (authKeyPath != null && !authKeyPath.isBlank() && keyId != null && !keyId.isBlank() && teamId != null && !teamId.isBlank()) {
            builder.setApnsServer(useSandbox ? ApnsClientBuilder.DEVELOPMENT_APNS_HOST : ApnsClientBuilder.PRODUCTION_APNS_HOST)
                    .setSigningKey(ApnsSigningKey.loadFromPkcs8File(new File(authKeyPath), teamId, keyId));
        }
        return builder.build();
    }
}


