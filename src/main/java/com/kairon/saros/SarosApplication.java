package com.kairon.saros;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * @author wangyongqing
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SarosApplication {

	public static void main(String[] args) {
		SpringApplication.run(SarosApplication.class, args);
	}

}
