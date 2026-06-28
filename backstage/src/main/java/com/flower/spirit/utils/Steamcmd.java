package com.flower.spirit.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Steamcmd {
	
	private static Logger logger = LoggerFactory.getLogger(Steamcmd.class);
	
	private static String workshoplog ="/root/.steam/logs/workshop_log.txt";
	
	private static String workdown ="/root/.steam/SteamApps/workshop/content/431960/";
	
	public static boolean exec(String wallpaper) {		
		try {
			//清除挂掉的screen会话
			ProcessBuilder wipe = new ProcessBuilder("screen", "-wipe");
			wipe.redirectErrorStream(true);
			Process wipeProcess = wipe.start();
			wipeProcess.waitFor(10, TimeUnit.SECONDS);
			//向指定名称的screen 会话执行
            List<String> command = new ArrayList<>();
            command.add("screen");
            command.add("-x");
            command.add("steamcmd");
            command.add("-X");
            command.add("stuff");
            command.add("workshop_download_item 431960 "+wallpaper+"\r");
            ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(true);
			Process process = processBuilder.start();
			try (InputStream inputStream = process.getInputStream();
					BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				String line;
				while ((line = reader.readLine()) != null) {
					System.out.println(line);
					if(line.contains("No screen")) {
						process.waitFor(10, TimeUnit.SECONDS);
						return false;
					}
				}
			}
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}
	        return true;
		} catch (Exception e) {
			return false;
		}
	}
	
	public static String execAndListening(String wallpepr) throws InterruptedException {
		boolean exec = exec(wallpepr);
		if(exec) {
			//先判断文件存不存在 超时2分钟不存在认为 失败
			int checklog=6;
			int i =0;
			boolean checkfile =  false;
			while(i <checklog) {
				File file = new File(workshoplog);
				if(file.exists() && file.length() >10) {
					checkfile = true;
					break;
				}
				Thread.sleep(1000*30);
				i++;
			}
			if(checkfile) {
				//文件存在读日志 并监听 文件超过15分钟未完成 认为失败
				int checkstatus = 60;
				int y = 0;
				boolean success = false;
				while(y<checkstatus) {
					File file = new File(workshoplog);
					String a = "Download item "+wallpepr+" result : OK";
//					String b ="Finished Workshop download job : No Error";
					BufferedReader reader = null;
			        try {
			            reader = new BufferedReader(new FileReader(file));
			            String readStr;
			            while ((readStr = reader.readLine()) != null) {
//			            	logger.info("-----------------------------------start");
//			            	logger.info(readStr);
//			            	logger.info(a);
//			            	logger.info(Boolean.toString(readStr.contains(a)));
//			            	logger.info("-----------------------------------end");
			            	if(readStr.contains(a)) {
			            		success = true;
			            		//清空log
			            		FileWriter fileWriter =new FileWriter(file);
			                    fileWriter.write("");  //写入空
			                    fileWriter.flush();
			                    fileWriter.close();
			            		break;
			            	}
			            }
			            reader.close();

			        } catch (IOException e) { e.printStackTrace();
			        } finally {if (reader != null) {try {reader.close(); } catch (IOException e1) {e1.printStackTrace();}}
			        }
			        if(success) {
			        	break;
			        }
					Thread.sleep(1000*30);
					y++;
				}
				if(success) {
					//静止十秒 等待IO完成
					Thread.sleep(1000*10);
					//文件路径为
					String path = workdown+wallpepr;
//					FileUtils.deleteFile(workshoplog);
					logger.info("处理完成---前端处理");
					return path;
				}else {
					logger.info("30分钟都没下完 一定出问题了");
					return null;
				}
			}else {
				logger.info("workshop_log 不存在 请exec 进入容器查看steamcmd 并已经登录");
			}
			
			return null;
		}
		logger.info("no session 请查看文档新建会话");
		return null;
	}
	
	
	public static void main(String[] args) {
		File file = new File("D://home//spirit//1.txt");
		System.out.println(file.length());
		if (file.exists() && file.isFile() && file.length() == 0) {
			System.out.println("The txt file is empty.");
		} else {
			System.out.println("The txt file is not empty.");
		}
	}
	

}
