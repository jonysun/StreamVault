package com.flower.spirit.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flower.spirit.dao.CollectdDataDao;
import com.flower.spirit.dao.CollectdDataDetailDao;
import com.flower.spirit.dao.GraphicContentDao;
import com.flower.spirit.dao.ProcessHistoryDao;
import com.flower.spirit.dao.VideoDataDao;
import com.flower.spirit.entity.CollectDataEntity;

@Service
public class ResetContentIndexService {

	private static final Logger logger = LoggerFactory.getLogger(ResetContentIndexService.class);

	@Autowired
	private VideoDataDao videoDataDao;

	@Autowired
	private GraphicContentDao graphicContentDao;

	@Autowired
	private CollectdDataDetailDao collectdDataDetailDao;

	@Autowired
	private ProcessHistoryDao processHistoryDao;

	@Autowired
	private CollectdDataDao collectdDataDao;

	@Transactional
	public void resetContentIndexKeepTasks() {
		long videoCount = videoDataDao.count();
		long graphicCount = graphicContentDao.count();
		long detailCount = collectdDataDetailDao.count();
		long historyCount = processHistoryDao.count();
		List<CollectDataEntity> tasks = collectdDataDao.findAll();

		logger.info("[ResetIndex] before reset video={} graphic={} detail={} history={} tasks={}",
				videoCount, graphicCount, detailCount, historyCount, tasks.size());

		videoDataDao.deleteAllInBatch();
		graphicContentDao.deleteAllInBatch();
		collectdDataDetailDao.deleteAllInBatch();
		processHistoryDao.deleteAllInBatch();

		for (CollectDataEntity task : tasks) {
			task.setCount(null);
			task.setCarriedout(null);
			task.setTaskstatus(null);
			task.setEndtime(null);
			task.setLastCheckTime(null);
			task.setLastid(null);
			task.setLastfetchsnapshot(null);
			task.setLastplanitems(null);
			task.setLastfetchtime(null);
			task.setLastfetchcount(null);
		}
		collectdDataDao.saveAll(tasks);

		logger.info("[ResetIndex] after reset video={} graphic={} detail={} history={} tasksRetained={}",
				videoDataDao.count(), graphicContentDao.count(), collectdDataDetailDao.count(), processHistoryDao.count(), collectdDataDao.count());
	}
}
