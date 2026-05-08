package com.axin.kagent;

import com.axin.kagent.agent.planandsolve.PlanAndSolveDemoRunner;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PlanAndSolveDemoRunnerTest {

	@Resource
	private PlanAndSolveDemoRunner planAndSolveDemoRunner;

	@Test
	void test() {
		planAndSolveDemoRunner.run();
	}
}