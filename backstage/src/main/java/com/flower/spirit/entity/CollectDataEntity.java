package com.flower.spirit.entity;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.Transient;

import com.flower.spirit.common.DataEntity;

@Entity
@Table(name = "biz_collect_data")
public class CollectDataEntity   extends DataEntity<CollectDataEntity> implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = -2752642646580560817L;
	
	
	@Id
	@GeneratedValue(strategy = GenerationType.TABLE,generator="biz_collect_data")
	@TableGenerator(name = "biz_collect_data", allocationSize = 1, table = "seq_common", pkColumnName = "seq_id", valueColumnName = "seq_count")
    private Integer id;
	
	private String taskid;
	
	private String platform;
	
	private String taskname;
	
	private String taskstatus;
	
	private String createtime;
	
	private String endtime;
	
	/**
	 * 总任务数
	 */
	private String count;
	
	/**
	 * 已经完成数
	 */
	private String carriedout;
	
	private String originaladdress;
	
	private String monitoring;   //是否监控

	private String taskenabled; // 任务是否启用: Y/N
	
	private String lastCheckTime;
	
	private String lastid;
	
	private Integer maxcur;
	
	private Integer omaxcur;
	
	private String generatenfo;
	
	private String taskcron;

	@Lob
	private String lastfetchsnapshot; // 最近一次全量拉取列表快照(JSON)

	@Lob
	private String lastplanitems; // 最近一次计划下载列表(JSON)

	private String lastfetchtime; // 最近一次拉取时间

	private Integer lastfetchcount; // 最近一次拉取总数

	@Column(name = "last_successful_fetch_at")
	private Date lastSuccessfulFetchAt;

	@Column(name = "last_seen_publish_time")
	private String lastSeenPublishTime;

	@Column(name = "last_seen_work_id")
	private String lastSeenWorkId;

	@Transient
	private String keyword;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	
	public String getTaskid() {
		return taskid;
	}

	public void setTaskid(String taskid) {
		this.taskid = taskid;
	}

	public String getPlatform() {
		return platform;
	}

	public void setPlatform(String platform) {
		this.platform = platform;
	}

	public String getTaskname() {
		return taskname;
	}

	public void setTaskname(String taskname) {
		this.taskname = taskname;
	}

	public String getTaskstatus() {
		return taskstatus;
	}

	public void setTaskstatus(String taskstatus) {
		this.taskstatus = taskstatus;
	}

	public String getCreatetime() {
		return createtime;
	}

	public void setCreatetime(String createtime) {
		this.createtime = createtime;
	}

	public String getEndtime() {
		return endtime;
	}

	public void setEndtime(String endtime) {
		this.endtime = endtime;
	}

	public String getCount() {
		return count;
	}

	public void setCount(String count) {
		this.count = count;
	}

	public String getCarriedout() {
		return carriedout;
	}

	public void setCarriedout(String carriedout) {
		this.carriedout = carriedout;
	}

	public String getOriginaladdress() {
		return originaladdress;
	}

	public void setOriginaladdress(String originaladdress) {
		this.originaladdress = originaladdress;
	}

	public String getMonitoring() {
		return monitoring;
	}

	public void setMonitoring(String monitoring) {
		this.monitoring = monitoring;
	}

	public String getTaskenabled() {
		return taskenabled;
	}

	public void setTaskenabled(String taskenabled) {
		this.taskenabled = taskenabled;
	}

	public String getLastCheckTime() {
		return lastCheckTime;
	}

	public void setLastCheckTime(String lastCheckTime) {
		this.lastCheckTime = lastCheckTime;
	}

	public String getLastid() {
		return lastid;
	}

	public void setLastid(String lastid) {
		this.lastid = lastid;
	}

	public Integer getMaxcur() {
		return maxcur;
	}

	public void setMaxcur(Integer maxcur) {
		this.maxcur = maxcur;
	}

	public Integer getOmaxcur() {
		return omaxcur;
	}

	public void setOmaxcur(Integer omaxcur) {
		this.omaxcur = omaxcur;
	}

	public String getGeneratenfo() {
		return generatenfo;
	}

	public void setGeneratenfo(String generatenfo) {
		this.generatenfo = generatenfo;
	}

	public String getTaskcron() {
		return taskcron;
	}

	public void setTaskcron(String taskcron) {
		this.taskcron = taskcron;
	}

	public String getLastfetchsnapshot() {
		return lastfetchsnapshot;
	}

	public void setLastfetchsnapshot(String lastfetchsnapshot) {
		this.lastfetchsnapshot = lastfetchsnapshot;
	}

	public String getLastplanitems() {
		return lastplanitems;
	}

	public void setLastplanitems(String lastplanitems) {
		this.lastplanitems = lastplanitems;
	}

	public String getLastfetchtime() {
		return lastfetchtime;
	}

	public void setLastfetchtime(String lastfetchtime) {
		this.lastfetchtime = lastfetchtime;
	}

	public Integer getLastfetchcount() {
		return lastfetchcount;
	}

	public void setLastfetchcount(Integer lastfetchcount) {
		this.lastfetchcount = lastfetchcount;
	}

	public Date getLastSuccessfulFetchAt() {
		return lastSuccessfulFetchAt;
	}

	public void setLastSuccessfulFetchAt(Date lastSuccessfulFetchAt) {
		this.lastSuccessfulFetchAt = lastSuccessfulFetchAt;
	}

	public String getLastSeenPublishTime() {
		return lastSeenPublishTime;
	}

	public void setLastSeenPublishTime(String lastSeenPublishTime) {
		this.lastSeenPublishTime = lastSeenPublishTime;
	}

	public String getLastSeenWorkId() {
		return lastSeenWorkId;
	}

	public void setLastSeenWorkId(String lastSeenWorkId) {
		this.lastSeenWorkId = lastSeenWorkId;
	}

	public String getKeyword() {
		return keyword;
	}

	public void setKeyword(String keyword) {
		this.keyword = keyword;
	}

	@Override
	public String toString() {
		return "CollectDataEntity [id=" + id + ", taskid=" + taskid + ", platform=" + platform + ", taskname="
				+ taskname + ", taskstatus=" + taskstatus + ", createtime=" + createtime + ", endtime=" + endtime
				+ ", count=" + count + ", carriedout=" + carriedout + ", originaladdress=" + originaladdress
				+ ", monitoring=" + monitoring + ", taskenabled=" + taskenabled + ", lastCheckTime=" + lastCheckTime + ", lastid=" + lastid + ", maxcur="
				+ maxcur + ", omaxcur=" + omaxcur + ", generatenfo=" + generatenfo + ", taskcron=" + taskcron
				+ ", lastfetchtime=" + lastfetchtime + ", lastfetchcount=" + lastfetchcount + "]";
	}

	
	
	
	
	

}
