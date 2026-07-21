package com.flower.spirit.platform.adapter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.flower.spirit.platform.PlatformResolver;

@Component
public class InstagramPlatformAdapter extends YtDlpPlatformAdapter {

	@Autowired
	public InstagramPlatformAdapter(YtDlpMetadataParser parser, PlatformResolver resolver) {
		this(parser, resolver, YtDlpPlatformAdapter.systemGateway());
	}

	InstagramPlatformAdapter(YtDlpMetadataParser parser, PlatformResolver resolver, Gateway gateway) {
		super("instagram", parser, resolver, gateway);
	}
}
