package com.flower.spirit.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import com.flower.spirit.config.Global;
import com.flower.spirit.entity.BiliConfigEntity;
import com.flower.spirit.entity.ConfigEntity;
import com.flower.spirit.entity.CookiesConfigEntity;
import com.flower.spirit.entity.TikTokConfigEntity;
import com.flower.spirit.service.BiliConfigService;
import com.flower.spirit.service.ConfigService;
import com.flower.spirit.service.CookiesConfigService;
import com.flower.spirit.service.TikTokConfigService;


/**
 * 页面类控制器
 * @author flower
 *
 */
@Controller
@RequestMapping(value = "/admin")
public class PageController {
	

	@Autowired
	private ConfigService configService;
	
	@Autowired
	private BiliConfigService biliConfigService;
	
	@Autowired
	private TikTokConfigService  tikTokConfigService;
	
	@Autowired
	private CookiesConfigService cookiesConfigService;
	
	/**
	 * 管理员控制台
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/admin")
	public String admin(HttpServletRequest request) {
		if(request.getSession().getAttribute(Global.user_session_key) ==  null) {
			return "admin/login";
		} else {
			return "admin/home";
		}
	}
	/**
	 * 管理员登录
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/login")
	public String login(HttpServletRequest request) {
		if (request.getSession().getAttribute(Global.user_session_key) != null) {
			return "redirect:/admin/home";
		}
		return "admin/login";
	}

	/**
	 * 管理员控制台
	 * @return
	 */
	@RequestMapping(value = "/index")
	public String index(Model model) {
		model.addAttribute("mediaHomeMode", Global.mediaHomeMode);
		model.addAttribute("mediaFeedMuted", Global.mediaFeedMuted);
		model.addAttribute("mediaFeedSource", Global.mediaFeedSource);
		return "admin/index";
	}
	
	/**
	 * 后台欢迎页
	 * @return
	 */
	@RequestMapping(value = "/welcome")
	public String welcome() {
		return "admin/welcome";
	}
	@RequestMapping(value = "/update")
	public String update() {
		return "admin/update";
	}
	/**
	 * 退出登录
	 * @param request
	 * @return
	 */
	@RequestMapping(value = "/loginOut")
	public String loginOut(HttpServletRequest request) {
//		request.getSession().setAttribute(Global.user_session_key, null);
	    HttpSession session = request.getSession(false);
	    if (session != null) {
	        // 完全销毁Session
	        session.invalidate();
	    }
		return "admin/login";
	}
	/**
	 * 用户列表页
	 * @return
	 */
	@RequestMapping(value = "/userList")
	public String userList() {
		return "admin/userList";
	}
	/**
	 * 新增用户页
	 * @return
	 */
	@RequestMapping(value = "/addUser")
	public String addUser() {
		return "admin/addUser";
	}
	
	/**
	 * 下载列表页
	 * @return
	 */
	@RequestMapping(value = "/downLoaderList")
	public String downLoaderList() {
		return "admin/downLoaderList";
	}
	/**
	 * 系统配置页
	 * @param model
	 * @return
	 */
	@RequestMapping(value = "/config")
	public String config(Model model) {
		ConfigEntity config = configService.getData();
		BiliConfigEntity bili = biliConfigService.getData();
		TikTokConfigEntity tiktok = tikTokConfigService.getData();
		CookiesConfigEntity cookies = cookiesConfigService.getData();
		model.addAttribute("bili", bili);
		model.addAttribute("config", config);
		model.addAttribute("tiktok", tiktok);
		model.addAttribute("cookies", cookies);
		return "admin/config";
	}
	/**
	 * 视频列表页
	 * @return
	 */
	@RequestMapping(value = "/videoDataList")
	public String videoDataList(Model model) {
		model.addAttribute("readtoken", Global.readonlytoken);
		model.addAttribute("videoListSortField", Global.videoListSortField);
		model.addAttribute("videoListSortOrder", Global.videoListSortOrder);
		return "admin/videoDataList";
	}
	
	
	@RequestMapping(value = "/processHistoryList")
	public String processHistoryList() {
		return "admin/processHistoryList";
	}
	
	@RequestMapping(value = "/collectDataList")
	public String collectDataList(Model model) {
		return "admin/collectDataList";
	}
	
	
	@RequestMapping(value = "/collectDataDetailList")
	public String collectDataDetailList(HttpServletRequest request,Model model) {
		String taskid = request.getParameter("taskid");
		model.addAttribute("taskid", taskid);
		return "admin/collectDataDetailList";
	}
	
	@RequestMapping(value = "/videokpop")
	public String videokpop() {
		return "admin/videokpop";
	}
	
	
	/**
	 * 视频列表页
	 * @return
	 */
	@RequestMapping(value = "/graphicContentList")
	public String graphicContentList(Model model) {
		model.addAttribute("mediaPreviewLimit", Global.mediaPreviewLimit);
		model.addAttribute("graphicListSortField", Global.graphicListSortField);
		model.addAttribute("graphicListSortOrder", Global.graphicListSortOrder);
		return "admin/graphicContent";
	}

	@RequestMapping(value = "/authorList")
	public String authorList() {
		return "admin/authorList";
	}

	@RequestMapping(value = "/blockedWorkList")
	public String blockedWorkList() {
		return "admin/blockedWorkList";
	}

	/**
	 * 线程池监控页面
	 * 
	 * @return
	 */
	@RequestMapping(value = "/threadPoolMonitor")
	public String threadPoolMonitor() {
		return "admin/threadPoolMonitor";
	}

	/**
	 * 数据统计页面
	 * 
	 * @return
	 */
	@RequestMapping(value = "/home")
	public String dataStatistics() {
		return "admin/home";
	}
	
	@RequestMapping(value = "/directData")
	public String directData() {
		return "admin/directData";
	}
}
