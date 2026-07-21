package com.flower.spirit.platform.adapter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.flower.spirit.platform.PlatformResolver;

@Component
public class TikTokPlatformAdapter extends YtDlpPlatformAdapter {

	@Autowired
	public TikTokPlatformAdapter(YtDlpMetadataParser parser, PlatformResolver resolver) {
		this(parser, resolver, YtDlpPlatformAdapter.systemGateway());
	}

	TikTokPlatformAdapter(YtDlpMetadataParser parser, PlatformResolver resolver, Gateway gateway) {
		super("tiktok", parser, resolver, gateway);
	}
}
