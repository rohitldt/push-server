package com.pushnotification.pushserver;

import org.hibernate.internal.build.AllowNonPortable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PushserverApplication {

	public static void main(String[] args) {
		SpringApplication.run(PushserverApplication.class, args);
	}
}
