package com.flower.spirit.platform.adapter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.flower.spirit.platform.PlatformResolver;

@Configuration(proxyBeanMethods = false)
public class YtDlpAdapterConfiguration {

	@Bean
	YtDlpPlatformAdapter youtubeYtDlpPlatformAdapter(YtDlpMetadataParser parser, PlatformResolver resolver) {
		return new YtDlpPlatformAdapter("youtube", parser, resolver, YtDlpPlatformAdapter.systemGateway());
	}

	@Bean
	YtDlpPlatformAdapter genericYtDlpPlatformAdapter(YtDlpMetadataParser parser, PlatformResolver resolver) {
		return new YtDlpPlatformAdapter("generic", parser, resolver, YtDlpPlatformAdapter.systemGateway());
	}
}
