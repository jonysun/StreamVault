package com.flower.spirit.entity;

import java.io.Serializable;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;

/**
 * 基础配置 app 配置
 * @author flower
 *
 */
@Entity
@Table(name = "biz_config")
public class ConfigEntity implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 3373882641617252642L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.TABLE,generator="biz_config_seq")
	@TableGenerator(name = "biz_config_seq", allocationSize = 1, table = "seq_common", pkColumnName = "seq_id", valueColumnName = "seq_count")
    private Integer id;
	
	private String apptoken;
	
	private String readonlytoken;
	
	private String ipauth;
	
	private String openprocesshistory;
	
	private String taskcron; //任务定时器
	
	private String generatenfo;
	
	private String danmudown;
	
	private String agenttype; //类型
	
	private String agentaddress;  //地址
	
	private String agentport;  //端口
	
	private String agentaccpass;  //密码
	
	private String useragent;
	
	private String frontend; //video  nav

	private String mediahomemode; // 媒体首页展示模式: grid/feed

	private String mediafeedmuted; // 滑动模式默认静音自动播放: 1/0
	
	private String ytdlpmode;
	
	// private String ytdlpargs;
	
	private String nfonetaddr;
	
	private String rangenum;
	
	private String hiddenplatforms;  // 在视频首页隐藏的平台（逗号分隔）
	
	private String filenametemplate;  // 自定义文件命名模板

	private String f2logfullonerror; // f2失败时输出完整traceback

	private String f2logmasksensitive; // f2日志敏感信息脱敏

	private String f2logmaxpreview; // f2日志预览最大长度

	private String mediapreviewlimit; // 图影集萃“查看更多”阈值

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getApptoken() {
		return apptoken;
	}

	public void setApptoken(String apptoken) {
		this.apptoken = apptoken;
	}

	public String getIpauth() {
		return ipauth;
	}

	public void setIpauth(String ipauth) {
		this.ipauth = ipauth;
	}

	public String getOpenprocesshistory() {
		return openprocesshistory;
	}

	public void setOpenprocesshistory(String openprocesshistory) {
		this.openprocesshistory = openprocesshistory;
	}

	public String getTaskcron() {
		return taskcron;
	}

	public void setTaskcron(String taskcron) {
		this.taskcron = taskcron;
	}

	public String getGeneratenfo() {
		return generatenfo;
	}

	public void setGeneratenfo(String generatenfo) {
		this.generatenfo = generatenfo;
	}

	public String getAgenttype() {
		return agenttype;
	}

	public void setAgenttype(String agenttype) {
		this.agenttype = agenttype;
	}

	public String getAgentaddress() {
		return agentaddress;
	}

	public void setAgentaddress(String agentaddress) {
		this.agentaddress = agentaddress;
	}

	public String getAgentport() {
		return agentport;
	}

	public void setAgentport(String agentport) {
		this.agentport = agentport;
	}

	public String getAgentaccpass() {
		return agentaccpass;
	}

	public void setAgentaccpass(String agentaccpass) {
		this.agentaccpass = agentaccpass;
	}

	public String getUseragent() {
		return useragent;
	}

	public void setUseragent(String useragent) {
		this.useragent = useragent;
	}

	public String getReadonlytoken() {
		return readonlytoken;
	}

	public void setReadonlytoken(String readonlytoken) {
		this.readonlytoken = readonlytoken;
	}

	public String getFrontend() {
		return frontend;
	}

	public void setFrontend(String frontend) {
		this.frontend = frontend;
	}

	public String getMediahomemode() {
		return mediahomemode;
	}

	public void setMediahomemode(String mediahomemode) {
		this.mediahomemode = mediahomemode;
	}

	public String getMediafeedmuted() {
		return mediafeedmuted;
	}

	public void setMediafeedmuted(String mediafeedmuted) {
		this.mediafeedmuted = mediafeedmuted;
	}

	public String getYtdlpmode() {
		return ytdlpmode;
	}

	public void setYtdlpmode(String ytdlpmode) {
		this.ytdlpmode = ytdlpmode;
	}

	public String getNfonetaddr() {
		return nfonetaddr;
	}

	public void setNfonetaddr(String nfonetaddr) {
		this.nfonetaddr = nfonetaddr;
	}

	public String getRangenum() {
		return rangenum;
	}

	public void setRangenum(String rangenum) {
		this.rangenum = rangenum;
	}

	public String getDanmudown() {
		return danmudown;
	}

	public void setDanmudown(String danmudown) {
		this.danmudown = danmudown;
	}

	public String getHiddenplatforms() {
		return hiddenplatforms;
	}

	public void setHiddenplatforms(String hiddenplatforms) {
		this.hiddenplatforms = hiddenplatforms;
	}

	public String getFilenametemplate() {
		return filenametemplate;
	}

	public void setFilenametemplate(String filenametemplate) {
		this.filenametemplate = filenametemplate;
	}

	public String getF2logfullonerror() {
		return f2logfullonerror;
	}

	public void setF2logfullonerror(String f2logfullonerror) {
		this.f2logfullonerror = f2logfullonerror;
	}

	public String getF2logmasksensitive() {
		return f2logmasksensitive;
	}

	public void setF2logmasksensitive(String f2logmasksensitive) {
		this.f2logmasksensitive = f2logmasksensitive;
	}

	public String getF2logmaxpreview() {
		return f2logmaxpreview;
	}

	public void setF2logmaxpreview(String f2logmaxpreview) {
		this.f2logmaxpreview = f2logmaxpreview;
	}

	public String getMediapreviewlimit() {
		return mediapreviewlimit;
	}

	public void setMediapreviewlimit(String mediapreviewlimit) {
		this.mediapreviewlimit = mediapreviewlimit;
	}

	// public String getYtdlpargs() {
	// 	return ytdlpargs;
	// }

	// public void setYtdlpargs(String ytdlpargs) {
	// 	this.ytdlpargs = ytdlpargs;
	// }

	
	
	

}
