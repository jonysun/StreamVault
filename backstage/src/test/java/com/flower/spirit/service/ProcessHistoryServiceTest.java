package com.flower.spirit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.flower.spirit.config.Global;
import com.flower.spirit.dao.ProcessHistoryDao;
import com.flower.spirit.entity.ProcessHistoryEntity;

@ExtendWith(MockitoExtension.class)
class ProcessHistoryServiceTest {

	@Mock
	private ProcessHistoryDao processHistoryDao;

	@InjectMocks
	private ProcessHistoryService processHistoryService;

	@Test
	void completesExistingProcessHistoryWithoutCreatingANewRow() {
		Global.openprocesshistory = true;
		ProcessHistoryEntity existing = new ProcessHistoryEntity(null, "https://v.douyin.com/abc/", "抖音", "已提交未执行");
		existing.setId(29);
		when(processHistoryDao.findById(29)).thenReturn(Optional.of(existing));

		processHistoryService.completeProcess(29, "任务执行完成");

		ArgumentCaptor<ProcessHistoryEntity> captor = ArgumentCaptor.forClass(ProcessHistoryEntity.class);
		verify(processHistoryDao, times(1)).save(captor.capture());
		ProcessHistoryEntity saved = captor.getValue();
		assertThat(saved.getId()).isEqualTo(29);
		assertThat(saved.getStatus()).isEqualTo("执行完毕");
		assertThat(saved.getTasklog()).isEqualTo("任务执行完成");
		assertThat(saved.getCreatetime()).isNotBlank();
	}
}
