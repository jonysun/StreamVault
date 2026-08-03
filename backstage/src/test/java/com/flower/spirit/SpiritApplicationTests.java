package com.flower.spirit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"logging.config=classpath:logback-test.xml",
		"spring.datasource.url=jdbc:sqlite:target/context-load.db?journal_mode=WAL&busy_timeout=10000&foreign_keys=on",
		"spring.task.scheduling.enabled=false",
		"spring.quartz.auto-startup=false"
})
class SpiritApplicationTests {

	@Test
	void contextLoads() {
//		BiliUtil.ArcSearch("319521269", null);
	}

}
