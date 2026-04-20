package com.flower.spirit.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flower.spirit.config.Global;

/**
 * 自定义文件命名模板工具类
 * 支持的变量：
 *   {发布日期}  - 内容发布日期，格式 yyyyMMdd
 *   {下载时间}  - 当前下载日期，格式 yyyyMMdd
 *   {作者}      - 内容作者昵称
 *   {标题}      - 内容标题
 *   {视频ID}    - 视频唯一标识ID
 *   {平台}      - 来源平台名称
 *   {年份}      - 发布日期的年份
 *   {月份}      - 发布日期的月份
 *   {日期}      - 发布日期的日
 *
 * 示例模板：{发布日期}_{作者}_{标题}_{视频ID}
 * 默认模板：空（使用原有 StringUtil.getFileName 逻辑）
 */
public class FileNameTemplateUtil {

    private static final Logger logger = LoggerFactory.getLogger(FileNameTemplateUtil.class);

    /** 匹配 {变量名} 格式 */
    private static final Pattern TEMPLATE_VAR_PATTERN = Pattern.compile("\\{([^}]+)\\}");

    /** 默认模板（空字符串表示使用原有逻辑） */
    private static final String DEFAULT_TEMPLATE = "";

    /**
     * 根据模板和参数生成文件名
     * 如果模板为空或null，则回退到原有 StringUtil.getFileName 逻辑
     *
     * @param template   命名模板
     * @param title      标题
     * @param videoId    视频ID
     * @param author     作者（可为null）
     * @param publishDate 发布日期字符串（可为null，支持格式见 DateUtils.parseDate）
     * @param platform   平台名称（可为null）
     * @return 安全的文件名字符串
     */
    public static String resolveFileName(String template, String title, String videoId,
                                          String author, String publishDate, String platform) {
        // 模板为空则使用原有逻辑
        if (template == null || template.trim().isEmpty()) {
            return StringUtil.getFileName(title, videoId);
        }

        try {
            // 构建变量值映射
            Map<String, String> varValues = buildVariableValues(title, videoId, author, publishDate, platform);

            // 替换模板中的变量
            String result = template;
            Matcher matcher = TEMPLATE_VAR_PATTERN.matcher(template);
            while (matcher.find()) {
                String varName = matcher.group(1);
                String value = varValues.getOrDefault(varName, "");
                result = result.replace("{" + varName + "}", value);
            }

            // 清理文件名：移除不安全字符
            result = sanitizeFileName(result);

            // 如果清理后为空，回退到原有逻辑
            if (result.isEmpty()) {
                return StringUtil.getFileName(title, videoId);
            }

            // 文件名不能以数字开头
            if (result.matches("^[0-9].*")) {
                result = "ep" + result;
            }

            return result;
        } catch (Exception e) {
            logger.warn("模板解析失败，回退到默认命名: {}", e.getMessage());
            return StringUtil.getFileName(title, videoId);
        }
    }

    /**
     * 便捷方法：使用全局配置的模板生成文件名
     */
    public static String resolveFileName(String title, String videoId,
                                          String author, String publishDate, String platform) {
        return resolveFileName(Global.filenametemplate, title, videoId, author, publishDate, platform);
    }

    /**
     * 构建变量值映射
     */
    private static Map<String, String> buildVariableValues(String title, String videoId,
                                                            String author, String publishDate, String platform) {
        Map<String, String> values = new LinkedHashMap<>();

        // 解析发布日期
        Date pubDate = null;
        if (publishDate != null && !publishDate.trim().isEmpty()) {
            pubDate = DateUtils.parseDate(publishDate);
        }

        // {发布日期}
        if (pubDate != null) {
            values.put("发布日期", new SimpleDateFormat("yyyyMMdd").format(pubDate));
            values.put("年份", new SimpleDateFormat("yyyy").format(pubDate));
            values.put("月份", new SimpleDateFormat("MM").format(pubDate));
            values.put("日期", new SimpleDateFormat("dd").format(pubDate));
        } else {
            String today = DateUtils.getDate("yyyyMMdd");
            values.put("发布日期", today);
            values.put("年份", DateUtils.getYear());
            values.put("月份", DateUtils.getMonth());
            values.put("日期", DateUtils.getDay());
        }

        // {下载时间}
        values.put("下载时间", DateUtils.getDate("yyyyMMdd"));

        // {作者}
        values.put("作者", author != null ? author : "");

        // {标题}
        values.put("标题", title != null ? title : "");

        // {视频ID}
        values.put("视频ID", videoId != null ? videoId : "");

        // {平台}
        values.put("平台", platform != null ? platform : "");

        return values;
    }

    /**
     * 清理文件名，移除文件系统不安全字符
     * 保留中文、字母、数字、下划线、连字符、点号
     */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }

        // 限制长度
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }

        // 替换文件系统不安全字符为下划线 (保留中文、字母、数字、下划线、连字符、点号)
        String result = name.replaceAll("[^A-Za-z0-9\\u4e00-\\u9fa5_\\-.]", "_");

        // 合并多个下划线
        result = result.replaceAll("_+", "_");

        // 去除首尾下划线和点号
        result = result.replaceAll("^[_.]+|[_.]+$", "");

        return result;
    }
}
