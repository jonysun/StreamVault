package com.flower.spirit;

import java.io.File;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.flower.spirit.service.PublishTimeBackfillService;
import com.flower.spirit.service.ResetContentIndexService;
import com.flower.spirit.utils.FileUtil;


@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = "com.flower.spirit.dao")
@EntityScan(basePackages = "com.flower.spirit.entity")
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class SpiritApplication {

	public static void main(String[] args) {
		SpiritApplication.initData();
		boolean backfillOnly = false;
		for (String arg : args) {
			if ("--backfill-douyin-publishtime".equals(arg)) {
				backfillOnly = true;
				break;
			}
			if ("--reset-content-index".equals(arg)) {
				backfillOnly = true;
				break;
			}
		}
		SpringApplication application = new SpringApplication(SpiritApplication.class);
		if (backfillOnly) {
			application.setWebApplicationType(WebApplicationType.NONE);
		}
		ConfigurableApplicationContext context = application.run(args);
		for (String arg : args) {
			if ("--backfill-douyin-publishtime".equals(arg)) {
				PublishTimeBackfillService service = context.getBean(PublishTimeBackfillService.class);
				service.backfillDouyinPublishTime();
				SpringApplication.exit(context, () -> 0);
				return;
			}
			if ("--reset-content-index".equals(arg)) {
				ResetContentIndexService service = context.getBean(ResetContentIndexService.class);
				service.resetContentIndexKeepTasks();
				SpringApplication.exit(context, () -> 0);
				return;
			}
		}
	}

	
	public static void initData() {
		try {
			  File destDir = new File("/app/db/spirit.db");
			  if(!destDir.exists()) {
				  FileUtil.copyDir("/home/app/db", "/app/db");
			  }
		} catch (Exception e) {
		}
	}
}
