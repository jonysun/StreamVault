package com.flower.spirit.platform.adapter;

import com.flower.spirit.platform.DownloadResult;
import com.flower.spirit.platform.WorkDownloadRequest;
import com.flower.spirit.platform.WorkMetadata;
import com.flower.spirit.platform.WorkParseRequest;

public interface PlatformWorkAdapter {

	String platformKey();

	boolean supports(String input);

	WorkMetadata parse(WorkParseRequest request);

	DownloadResult download(WorkMetadata metadata, WorkDownloadRequest request);
}
