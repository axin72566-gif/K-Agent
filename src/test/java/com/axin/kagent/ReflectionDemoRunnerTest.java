package com.axin.kagent;

import com.axin.kagent.agent.reflection.ReflectionDemoRunner;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;


@SpringBootTest
class ReflectionDemoRunnerTest {

	@Resource
	private ReflectionDemoRunner demoRunner;

	@Test
	void run() {
		demoRunner.run();
	}
}