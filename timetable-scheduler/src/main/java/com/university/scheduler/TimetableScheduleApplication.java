package com.university.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = { "com.university.scheduler" })
public class TimetableScheduleApplication {
	public static void main(String[] args) {
		SpringApplication.run(TimetableScheduleApplication.class, args);
	}
}
