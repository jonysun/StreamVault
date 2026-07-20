package com.flower.spirit.utils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Scanner;
import java.util.Map;
import java.util.Date;
import java.util.List;
import com.alibaba.fastjson.JSONObject;

public class EmbyMetadataGenerator {

    public enum Platform {
        BILIBILI("哔哩哔哩", ""),
        DOUYIN("抖音", "抖音"),
        KUAISHOU("快手", "快手");

        private final String genreName;
        private final String actorPrefix;

        Platform(String genreName, String actorPrefix) {
            this.genreName = genreName;
            this.actorPrefix = actorPrefix;
        }

        public String getGenreName() {
            return genreName;
        }

        public String getActorPrefix() {
            return actorPrefix;
        }
    }

    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String DEFAULT_COUNTRY = "无";
    private static final String DEFAULT_LANGUAGE = "中文";
    private static final String DEFAULT_ROLE = "创作";
    private static final String NFO_FILENAME = "movie.nfo";
    private static final String DEFAULT_DATE = "2025-01-01";
    private static final String DEFAULT_DATETIME = "2025-01-01 00:00:00";

    // 生成 movie.nfo（单个视频）
    public static void generateMetadata(String title, String year, String overview, String genre, String director,
            String actor, String outputPath, String upface, String upmid, String pic) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            SimpleDateFormat premieredFormat = new SimpleDateFormat("yyyy-MM-dd");
            Date currentDate = new Date();

            StringBuilder xmlBuilder = new StringBuilder()
                    .append(XML_HEADER).append("\n")
                    .append("<movie>\n")
                    .append(createXmlTag("title", title))
                    .append(createXmlTag("originaltitle", title))
                    .append(createXmlTag("sorttitle", title))
                    .append("    <epbookmark/>\n")
                    .append(createXmlTag("year", year))
                    .append("    <ratings/>\n")
                    .append(createXmlTag("userrating", "0"))
                    .append(createXmlTag("top250", "0"))
                    .append("    <set/>\n")
                    .append(createXmlTag("plot", overview))
                    .append(createXmlTag("outline", overview))
                    .append(createXmlTag("tagline", title))
                    .append(createXmlTag("runtime", "1"))
                    .append("    <mpaa/>\n")
                    .append("    <certification/>\n")
                    .append("    <tmdbid/>\n")
                    .append(createXmlTag("country", DEFAULT_COUNTRY))
                    .append("    <status/>\n")
                    .append("    <code/>\n")
                    .append(createXmlTag("genre", genre))
                    .append(createXmlTag("premiered", premieredFormat.format(currentDate)))
                    .append(createXmlTag("watched", "false"))
                    .append(createXmlTag("playcount", "0"))
                    .append(createXmlTag("studio", actor))
                    .append("    <actor>\n")
                    .append(createXmlTag("name", actor, 8))
                    .append(createXmlTag("role", DEFAULT_ROLE, 8));
				if(genre.equals(Platform.DOUYIN.getGenreName()) && AuthorIdentityUtil.isDouyinSecUid(upmid)) {
					xmlBuilder.append(createXmlTag("profile", "https://www.douyin.com/user/"+upmid, 8));
            	}
            	if(genre.equals(Platform.BILIBILI.getGenreName())) {
            		xmlBuilder.append(createXmlTag("profile", "https://space.bilibili.com/"+upmid, 8));
            	}
	            // 只有当upface存在且不为空时才添加thumb标签
	            if (upface != null && !upface.trim().isEmpty()) {
	                xmlBuilder.append(createXmlTag("thumb", upface.replace('\\', '/'), 8));
	            }

            xmlBuilder.append("    </actor>\n")
                    .append("    <trailer/>\n")
                    .append(createXmlTag("languages", DEFAULT_LANGUAGE))
                    .append(createXmlTag("dateadded", dateFormat.format(currentDate)))
                    .append("</movie>");

            writeToFile(outputPath + File.separator + NFO_FILENAME, xmlBuilder.toString());
        } catch (Exception e) {
            System.err.println("生成nfo文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String createXmlTag(String tagName, String value) {
        return createXmlTag(tagName, value, 4);
    }

    private static String createXmlTag(String tagName, String value, int indentSpaces) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < indentSpaces; i++) {
            indent.append(" ");
        }
        return String.format("%s<%s>%s</%s>\n", indent.toString(), tagName, value, tagName);
    }

    // 生成 tvshow.nfo（系列视频）
    public static void generateSeriesNfo(String title, String overview, String genre, String rating,
            String outputPath, List<Map<String, String>> upInfoList, String cover, String ctime, String p) {
        try {
            // 转换时间戳为年份和日期
            String year = "2025";
            String dateadded = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String premiered = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            try {
                long timestamp = Long.parseLong(ctime);
                Date date = new Date(timestamp * 1000L);
                year = new SimpleDateFormat("yyyy").format(date);
                dateadded = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
                premiered = new SimpleDateFormat("yyyy-MM-dd").format(date);
            } catch (Exception e) {
                // 使用默认值
            }

            StringBuilder nfoContent = new StringBuilder();
            nfoContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
            nfoContent.append("<!--created by spirit for KODI-->\n");
            nfoContent.append("<tvshow>\n");
            nfoContent.append("    <title>收藏夹-" + title + "</title>\n");
            nfoContent.append("    <originaltitle>" + title + "</originaltitle>\n");
            nfoContent.append("    <showtitle>收藏夹-" + title + "</showtitle>\n");
            nfoContent.append("    <sorttitle>" + title + "</sorttitle>\n");
            nfoContent.append("    <year>" + year + "</year>\n");
            nfoContent.append("    <top250>0</top250>\n");
            nfoContent.append("    <ratings/>\n");
            nfoContent.append("    <userrating>0</userrating>\n");
            nfoContent.append(
                    "    <outline>" + (overview.isEmpty() ? "收藏夹-" + title : overview) + "</outline>\n");
            nfoContent.append("    <plot>" + (overview.isEmpty() ? "收藏夹-" + title : overview) + "</plot>\n");
            nfoContent.append("    <tagline/>\n");
            nfoContent.append("    <runtime>0</runtime>\n");
            nfoContent.append("    <mpaa/>\n");
            nfoContent.append("    <certification/>\n");
            nfoContent.append("    <episodeguide>{}</episodeguide>\n");
            nfoContent.append("    <imdbid/>\n");
            nfoContent.append("    <tmdbid/>\n");
            nfoContent.append("    <premiered>" + premiered + "</premiered>\n");
            nfoContent.append("    <status>Unknown</status>\n");
            nfoContent.append("    <watched>false</watched>\n");
            nfoContent.append("    <playcount/>\n");
            nfoContent.append("    <studio>" + p + "</studio>\n");
            nfoContent.append("    <country>中国</country>\n");
            if (cover != null) {
                nfoContent.append("    <thumb aspect=\"poster\">" + cover.replace('\\', '/') + "</thumb>\n");
                nfoContent.append("    <thumb aspect=\"banner\">" + cover.replace('\\', '/') + "</thumb>\n");
                nfoContent.append("    <thumb aspect=\"landscape\">" + cover.replace('\\', '/') + "</thumb>\n");
            }

            // 只有在upInfoList不为空时才添加UP主信息
            if (upInfoList != null && !upInfoList.isEmpty()) {
                for (Map<String, String> upInfo : upInfoList) {
                    nfoContent.append("    <actor>\n");
                    nfoContent.append("        <name>" + upInfo.get("upname") + "</name>\n");
                    nfoContent.append("        <role>UP主</role>\n");
                    nfoContent.append("        <thumb>" + upInfo.get("upface").replace('\\', '/') + "</thumb>\n");
                    nfoContent.append("        <profile>" + upInfo.get("upmid") + "</profile>\n");
                    nfoContent.append("    </actor>\n");
                }
            }

            nfoContent.append("    <trailer/>\n");
            nfoContent.append("    <dateadded>" + dateadded + "</dateadded>\n");
            nfoContent.append("</tvshow>\n");

            writeToFile(outputPath + "/tvshow.nfo", nfoContent.toString());
        } catch (Exception e) {
            System.err.println("生成tvshow.nfo文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 生成单集 SxxExx.nfo
    public static void generateEpisodeNfo(String title, String overview, String upname, String upface, String upmid,
            String outputPath, String pic, String ctime, int episodeNumber) {
        try {
            // 处理时间
            String dateadded = "2025-03-24 10:13:00";
            String aired = "2025-03-24";
            try {
                long timestamp = Long.parseLong(ctime);
                Date date = new Date(timestamp * 1000L);
                dateadded = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
                aired = new SimpleDateFormat("yyyy-MM-dd").format(date);
            } catch (Exception e) {
                // 使用默认值
            }

            StringBuilder nfoContent = new StringBuilder();
            nfoContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
            nfoContent.append("<!--created by spirit for KODI-->\n");
            nfoContent.append("<episodedetails>\n");
            nfoContent.append("    <title>" + title + "</title>\n");
            nfoContent.append("    <originaltitle/>\n");
            nfoContent.append("    <showtitle>bilibili收藏夹</showtitle>\n");
            nfoContent.append("    <season>1</season>\n");
            nfoContent.append("    <episode>" + episodeNumber + "</episode>\n");
            nfoContent.append("    <ratings/>\n");
            nfoContent.append("    <userrating>0</userrating>\n");
            nfoContent.append("    <plot>" + overview + "</plot>\n");
            nfoContent.append("    <runtime>0</runtime>\n");
            nfoContent.append("    <mpaa/>\n");
            nfoContent.append("    <premiered>" + aired + "</premiered>\n");
            nfoContent.append("    <aired>" + aired + "</aired>\n");
            nfoContent.append("    <watched>false</watched>\n");
            nfoContent.append("    <playcount>0</playcount>\n");
            nfoContent.append("    <thumb>" + pic.replace('\\', '/') + "</thumb>\n");
            nfoContent.append("    <actor>\n");
            nfoContent.append("        <name>" + upname + "</name>\n");
            nfoContent.append("        <role>UP主</role>\n");
            nfoContent.append("        <thumb>" + upface.replace('\\', '/') + "</thumb>\n");
            nfoContent.append("        <profile>" + upmid + "</profile>\n");
            nfoContent.append("    </actor>\n");
            nfoContent.append("    <dateadded>" + dateadded + "</dateadded>\n");
            nfoContent.append("    <epbookmark/>\n");
            nfoContent.append("    <code/>\n");
            nfoContent.append("</episodedetails>\n");

            writeToFile(outputPath + "/" + title + ".nfo", nfoContent.toString());
        } catch (Exception e) {
            System.err.println("生成episode.nfo文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 写入文件方法
    private static void writeToFile(String filePath, String content) {
        try (FileWriter writer = new FileWriter(new File(filePath))) {
            writer.write(content);
            System.out.println("文件生成成功: " + filePath);
        } catch (IOException e) {
            System.err.println("写入文件失败: " + e.getMessage());
        }
    }

    public static void createDouNfo(String upname, String upmid,String upface, String ctime, String cid, String title,
            String desc, String pic, String out) {
    	title =StringUtil.simplifyTitle(title);
        generateMovieMeta(Platform.DOUYIN, upname, upface, upmid, ctime, cid, title, desc, pic, out);
    }

    public static void createKuaiNfo(String upname, String upmid, String ctime, String cid, String title,
            String desc, String pic, String out) {
    	title =StringUtil.simplifyTitle(title);
        generateMovieMeta(Platform.KUAISHOU, upname, null, upmid, ctime, cid, title, desc, pic, out);
    }

    public static void createBillNfo(String upname, String upface, String upmid, String ctime, String cid, String title,
            String desc, String pic, String out) {
        generateMovieMeta(Platform.BILIBILI, upname, upface, upmid, ctime, cid, title, desc, pic, out);
    }

    // 添加UP主信息到tvshow.nfo
    private static void addUpInfoToTvshowNfo(String outputPath, String upname, String upface, String upmid) {
        try {
            File nfoFile = new File(outputPath + "/tvshow.nfo");
            if (!nfoFile.exists()) {
                System.err.println("tvshow.nfo文件不存在");
                return;
            }

            // 读取文件内容
            StringBuilder content = new StringBuilder();
            try (Scanner scanner = new Scanner(nfoFile, "UTF-8")) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    // 如果找到</tvshow>标签，在其前面插入UP主信息
                    if (line.trim().equals("</tvshow>")) {
                        // 检查是否已存在该UP主
                        if (!content.toString().contains("<profile>" + upmid + "</profile>")) {
                            content.append("    <actor>\n");
                            content.append("        <name>" + upname + "</name>\n");
                            content.append("        <role>发布者</role>\n");
                            if (upface != null) {
                                content.append("        <thumb>" + upface.replace('\\', '/') + "</thumb>\n");
                            }
                            content.append("        <profile>" + upmid + "</profile>\n");
                            content.append("    </actor>\n");
                        }
                    }
                    content.append(line).append("\n");
                }
            }

            // 写回文件
            try (FileWriter writer = new FileWriter(nfoFile)) {
                writer.write(content.toString());
            }

        } catch (Exception e) {
            System.err.println("更新tvshow.nfo文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void createFavoriteNfo(String jsonData, String output) {
        try {
            JSONObject object = JSONObject.parseObject(jsonData);
            JSONObject data = object.getJSONObject("data");

            String title = data.getString("title");
            String overview = data.getString("intro");
            String genre = "哔哩哔哩";
            String rating = "0.0";
            String outputPath = output;
            String cover = data.getString("cover"); // 已经是本地路径
            String ctime = data.getString("ctime");

            // 确保输出目录存在
            new File(outputPath).mkdirs();

            // 生成初始tvshow.nfo，不包含UP主信息
            generateSeriesNfo(title, overview, genre, rating, outputPath, null, cover, ctime, "哔哩哔哩");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void createFavoriteDouNfo(String title, String output) {
        try {
            String overview = title;
            String genre = "来自抖音";
            String rating = "0.0";
            String outputPath = output;
            String ctime = DateUtils.getDate("yyyy-MM-dd HH:mm:ss");
            // 确保输出目录存在
            new File(outputPath).mkdirs();
            // 生成初始tvshow.nfo，不包含UP主信息
            generateSeriesNfo(title, overview, genre, rating, outputPath, null, null, ctime, "抖音");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 统一的剧集NFO生成方法
     * 
     * @param videoInfo     视频信息Map
     * @param outputPath    输出路径
     * @param episodeNumber 集数
     * @param tvshow        tvshow目录
     * @param platform      平台（哔哩哔哩/抖音）
     */
    public static void createEpisodeNfo(Map<String, String> videoInfo, String outputPath,
            int episodeNumber, String tvshow, Platform platform) {
        try {
            // 基本信息处理
            String episodeTitle = videoInfo.get("title");
            String episodeOverview = processEpisodeOverview(videoInfo.get("desc"), videoInfo.get("upname"), platform);

            // 时间处理
            String[] dates = getFormattedDates(videoInfo.get("ctime"));
            String airedDate = dates[0];
            String dateadded = dates[1];
            //
            if (platform == Platform.DOUYIN) {
                episodeTitle = StringUtil.simplifyTitle(episodeTitle);
            }
            //
            // 构建NFO内容
            StringBuilder nfoContent = new StringBuilder()
                    .append(XML_HEADER).append("\n")
                    .append("<episodedetails>\n")
                    .append(createXmlTag("title", episodeTitle))
                    .append(createXmlTag("season", "1"))
                    .append(createXmlTag("episode", String.valueOf(episodeNumber)))
                    .append(createXmlTag("showtitle", "第" + episodeNumber + "集"))
                    .append(createXmlTag("aired", airedDate))
                    .append(createXmlTag("plot", episodeOverview))
                    .append(createXmlTag("runtime", ""))
                    .append(createXmlTag("thumb", videoInfo.get("piclocal").replace('\\', '/')))
                    .append(createXmlTag("uniqueid type=\"" + platform.getGenreName() + "\" default=\"true\"",
                            videoInfo.get("bvid")))
                    .append(createXmlTag("director", videoInfo.get("upname")))
                    .append("    <actor>\n")
                    .append(createXmlTag("name", videoInfo.get("upname"), 8))
                    .append(createXmlTag("role", platform == Platform.BILIBILI ? "UP主" : DEFAULT_ROLE, 8));

            // 只有哔哩哔哩平台添加头像
            if (videoInfo.get("upface") != null) {
            	  nfoContent.append(createXmlTag("thumb", videoInfo.get("upface").replace('\\', '/'), 8));
            }

            nfoContent.append(createXmlTag("profile", videoInfo.get("upmid"), 8))
                    .append("    </actor>\n")
                    .append(createXmlTag("id", videoInfo.get("cid")))
                    .append(createXmlTag("source", platform.getGenreName()))
                    .append(createXmlTag("dateadded", dateadded))
                    .append("</episodedetails>");

            // 写入文件
            writeToFile(outputPath + File.separator + episodeTitle + ".nfo", nfoContent.toString());

            // 添加UP主信息到tvshow.nfo
            addUpInfoToTvshowNfo(tvshow, videoInfo.get("upname"),
                    videoInfo.get("upface"),
                    videoInfo.get("upmid"));

        } catch (Exception e) {
            System.err.println("生成单集nfo文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String processEpisodeOverview(String desc, String upname, Platform platform) {
        if (desc == null || desc.equals("-") || desc.trim().isEmpty()) {
            String creatorType = platform == Platform.BILIBILI ? "UP主" : DEFAULT_ROLE;
            return String.format("由%s【%s】上传的视频", creatorType, upname);
        }
        return desc;
    }

    private static String[] getFormattedDates(String ctime) {
        try {
            long timestamp = Long.parseLong(ctime);
            Date date = new Date(timestamp * 1000L);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return new String[] {
                    dateFormat.format(date),
                    dateTimeFormat.format(date)
            };
        } catch (Exception e) {
            return new String[] { DEFAULT_DATE, DEFAULT_DATETIME };
        }
    }

    // 原有方法改为调用新方法
    public static void createFavoriteEpisodeNfo(Map<String, String> videoInfo, String outputPath,
            int episodeNumber, String tvshow) {
        createEpisodeNfo(videoInfo, outputPath, episodeNumber, tvshow, Platform.BILIBILI);
    }

    public static void createFavoriteEpisodeDouNfo(Map<String, String> videoInfo, String outputPath,
            int episodeNumber, String tvshow) {
        createEpisodeNfo(videoInfo, outputPath, episodeNumber, tvshow, Platform.DOUYIN);
    }

    public static void generateMovieMeta(Platform platform, String upname, String upface, String upmid,
            String ctime, String cid, String title, String desc, String pic, String out) {
        try {
            // 清理标题
            String cleanTitle = title.replace("\"", "").trim();
            // 获取年份
            String year = getYearFromTimestamp(ctime);
            // 处理描述
            String overview = processDescription(desc, upname, upmid, cid);
            // 处理演员名称
            String actor = platform.getActorPrefix() + upname;
            // 生成元数据
            generateMetadata(
                    cleanTitle, // 标题
                    year, // 年份
                    overview, // 概述/描述
                    platform.getGenreName(), // 类型
                    upname, // 导演
                    actor, // 演员
                    out, // 输出路径
                    upface, // UP主头像
                    upmid, // UP主ID
                    pic // 封面图片
            );
            System.out.println("元数据生成成功: " + cleanTitle);
        } catch (Exception e) {
            System.err.println("生成元数据时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String getYearFromTimestamp(String ctime) {
        try {
            long timestamp = Long.parseLong(ctime);
            return new SimpleDateFormat("yyyy").format(new Date(timestamp * 1000L));
        } catch (Exception e) {
            return "2025"; // 默认年份
        }
    }

    private static String processDescription(String desc, String upname, String upmid, String cid) {
        if (desc == null || desc.equals("-") || desc.trim().isEmpty()) {
            desc = "由【" + upname + "】上传的视频内容";
        }
        return desc + "\r\n发布者：" + upname + "\r\nUID：" + upmid + "\r\n视频ID：" + cid;
    }

}
