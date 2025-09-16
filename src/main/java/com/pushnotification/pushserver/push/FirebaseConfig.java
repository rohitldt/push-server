package com.pushnotification.pushserver.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    public FirebaseApp firebaseApp(@Value("${fcm.service-account-path:}") String serviceAccountPath) throws IOException {
        log.info("🔥 Initializing Firebase App with service account path: {}", serviceAccountPath);
        
        if (serviceAccountPath == null || serviceAccountPath.isBlank()) {
            log.warn("⚠️ No service account path provided, using default Firebase initialization");
            return FirebaseApp.getApps().isEmpty() ? FirebaseApp.initializeApp() : FirebaseApp.getInstance();
        }
        
        try (FileInputStream serviceAccount = new FileInputStream(serviceAccountPath)) {
            log.info("📁 Loading Firebase service account from: {}", serviceAccountPath);
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();
            FirebaseApp app = FirebaseApp.initializeApp(options);
            log.info("✅ Firebase App initialized successfully");
            return app;
        } catch (Exception e) {
            log.error("❌ Failed to initialize Firebase App: {}", e.getMessage());
            throw e;
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp app) {
        log.info("📱 Creating FirebaseMessaging instance");
        return FirebaseMessaging.getInstance(app);
    }
}


