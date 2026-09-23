package com.ridematching;

import org.springframework.boot.SpringApplication;

public class TestRideMatchingApplication {

	public static void main(String[] args) {
		SpringApplication.from(RideMatchingApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
