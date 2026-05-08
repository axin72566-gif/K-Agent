package com.axin.kagent;

import com.axin.kagent.agent.react.ReActDemoRunner;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ReActDemoRunnerTest {

	@Resource
	private ReActDemoRunner reactDemoRunner;

	@Test
	void test() {
		reactDemoRunner.run();
	}

}