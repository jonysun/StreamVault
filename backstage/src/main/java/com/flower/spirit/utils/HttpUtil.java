package com.flower.spirit.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONObject;
import com.flower.spirit.config.Global;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 这个方法中有大量遗弃方法不再调用
 */
@SuppressWarnings("deprecation")
public class HttpUtil {

    private static final Logger logger = LoggerFactory.getLogger(HttpUtil.class);
    
    private static final HttpClient httpClient = new HttpClient();
    
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public static String getPage(String url,String cookie,String referer) {
    	 Request.Builder requestBuilder = new Request.Builder().url(url);
    	 if(null!= cookie && !"".equals(cookie)) {
        	 requestBuilder.addHeader("Cookie", cookie);
    	 }
    	 if(null!= referer && !"".equals(referer)) {
        	 requestBuilder.addHeader("referer", referer);
    	 }
    	 requestBuilder.addHeader("User-Agent", Global.useragent != null?Global.useragent:"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36");
    	 Request request = requestBuilder.build();
    	 try (Response response = client.newCall(request).execute()) {
    		 String responseBody = response.body().string();
             return responseBody;
    	 } catch (Exception e) {

         } finally {
          
         }
		 return null;
    }
    
    
    /**
     * web 端
     * 
     * @param url
     * @param param
     * @return
     */
    public static String httpGet(String url, String param) {
        httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
        GetMethod getMethod = new GetMethod(url);
        getMethod.getParams().setParameter(HttpMethodParams.SO_TIMEOUT, 5000);
        getMethod.getParams().setParameter("user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36");
        getMethod.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler());
        String response = "";
        try {
            int statusCode = httpClient.executeMethod(getMethod);
            if (statusCode != HttpStatus.SC_OK) {
                System.err.println("请求出错: " + getMethod.getStatusLine());
            }
            byte[] responseBody = getMethod.getResponseBody();
            response = new String(responseBody, param);
        } catch (HttpException e) {
            System.out.println("请检查输入的URL!");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        } finally {
            /* 6 .释放连接 */
            getMethod.releaseConnection();
        }
        return response;
    }

    /**
     * 用于验证bili登录接口 并取出cookie
     * 
     * @param url
     * @param param
     * @return
     */
    public static Map<String, String> httpGetBypoll(String url, String param) {
    	Map<String, String> res = new HashMap<String, String>();
        httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
        GetMethod getMethod = new GetMethod(url);
        getMethod.getParams().setParameter(HttpMethodParams.SO_TIMEOUT, 5000);
        getMethod.getParams().setParameter("user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36");
        getMethod.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler());
        String response = "";
        try {
            int statusCode = httpClient.executeMethod(getMethod);
            if (statusCode != HttpStatus.SC_OK) {
                return null;
            }
            byte[] responseBody = getMethod.getResponseBody();
            response = new String(responseBody, param);
            String code = JSONObject.parseObject(response).getJSONObject("data").getString("code");
            if (code.equals("0")) {
                // 登录成功
                Header[] headers = getMethod.getResponseHeaders();
                String cookie = "";
                for (Header h : headers) {
                    if (h.getName().equals("Set-Cookie")) {
                        String str = h.getValue().split(";")[0];
                        cookie = cookie + ";" + str;
                    }
                }
                JSONObject object = JSONObject.parseObject(response);
                if(object.getString("code").equals("0")) {
                	String refresh_token = object.getJSONObject("data").getString("refresh_token");
                	res.put("refresh_token", refresh_token);
                }
                
                res.put("cookie", cookie.substring(1, cookie.length()));
                return res;
            } else {
                return null;
            }

        } catch (HttpException e) {
            System.out.println("请检查输入的URL!");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        } finally {
            /* 6 .释放连接 */
            getMethod.releaseConnection();
        }
        return null;
    }
    
    
    public static String httpPost(String url, Map<String, String> params, String cook) {
        Map<String, String> res = new HashMap<String, String>();
        PostMethod postMethod = new PostMethod(url);
        postMethod.getParams().setParameter(HttpMethodParams.SO_TIMEOUT, 5000);
        postMethod.getParams().setParameter("user-agent", 
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36");
        postMethod.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler());
        if (null != cook && !cook.equals("")) {
            postMethod.addRequestHeader("cookie", cook);
        }
        NameValuePair[] data = new NameValuePair[params.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            data[i++] = new NameValuePair(entry.getKey(), entry.getValue());
        }
        postMethod.setRequestBody(data);
        String response = "";
        try {
            int statusCode = httpClient.executeMethod(postMethod);
            if (statusCode != HttpStatus.SC_OK) {
                return null;
            }

            byte[] responseBody = postMethod.getResponseBody();
            response = new String(responseBody, "UTF-8");

            return response;

        } catch (HttpException e) {
            System.out.println("请检查输入的URL!");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        } finally {
            // 释放连接
            postMethod.releaseConnection();
        }

        return null;
    }
    
    
    public static Map<String, String> httpPostBypoll(String url, Map<String, String> params, String cook) {
        Map<String, String> res = new HashMap<String, String>();
        PostMethod postMethod = new PostMethod(url);
        postMethod.getParams().setParameter(HttpMethodParams.SO_TIMEOUT, 5000);
        postMethod.getParams().setParameter("user-agent", 
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36");
        postMethod.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler());
        if (null != cook && !cook.equals("")) {
            postMethod.addRequestHeader("cookie", cook);
        }
        NameValuePair[] data = new NameValuePair[params.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            data[i++] = new NameValuePair(entry.getKey(), entry.getValue());
        }
        postMethod.setRequestBody(data);
        String response = "";
        try {
            int statusCode = httpClient.executeMethod(postMethod);
            if (statusCode != HttpStatus.SC_OK) {
                return null;
            }

            byte[] responseBody = postMethod.getResponseBody();
            response = new String(responseBody, "UTF-8");

            String code = JSONObject.parseObject(response).getString("code");
            if (code.equals("0")) {
                Header[] headers = postMethod.getResponseHeaders();
                String cookie = "";
                for (Header h : headers) {
                    if (h.getName().equals("Set-Cookie")) {
                        String str = h.getValue().split(";")[0];
                        cookie = cookie + ";" + str;
                    }
                }
                JSONObject object = JSONObject.parseObject(response);
                if(object.getString("code").equals("0")) {
                    String refresh_token = object.getJSONObject("data").getString("refresh_token");
                    res.put("refresh_token", refresh_token);
                }

                res.put("cookie", cookie.substring(1, cookie.length()));
                return res;
            } else {
                return null;
            }

        } catch (HttpException e) {
            System.out.println("请检查输入的URL!");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        } finally {
            // 释放连接
            postMethod.releaseConnection();
        }

        return null;
    }
    
    
    public static Map<String, String> httpGetBypoll(String url, String param,String cook) {
    	Map<String, String> res = new HashMap<String, String>();
        httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
        GetMethod getMethod = new GetMethod(url);
        getMethod.getParams().setParameter(HttpMethodParams.SO_TIMEOUT, 5000);
        getMethod.getParams().setParameter("user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36");
        getMethod.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler());
        if (null != cook && !cook.equals("")) {
            getMethod.addRequestHeader("cookie", cook);
        }

        String response = "";
        try {
            int statusCode = httpClient.executeMethod(getMethod);
            if (statusCode != HttpStatus.SC_OK) {
                return null;
            }
            byte[] responseBody = getMethod.getResponseBody();
            response = new String(responseBody, param);
            String code = JSONObject.parseObject(response).getJSONObject("data").getString("code");
            if (code.equals("0")) {
                // 登录成功
                Header[] headers = getMethod.getResponseHeaders();
                String cookie = "";
                for (Header h : headers) {
                    if (h.getName().equals("Set-Cookie")) {
                        String str = h.getValue().split(";")[0];
                        cookie = cookie + ";" + str;
                    }
                }
                JSONObject object = JSONObject.parseObject(response);
                if(object.getString("code").equals("0")) {
                	String refresh_token = object.getJSONObject("data").getString("refresh_token");
                	res.put("refresh_token", refresh_token);
                }
                
                res.put("cookie", cookie.substring(1, cookie.length()));
                return res;
            } else {
                return null;
            }

        } catch (HttpException e) {
            System.out.println("请检查输入的URL!");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        } finally {
            /* 6 .释放连接 */
            getMethod.releaseConnection();
        }
        return null;
    }

    /**
     * json 字符串
     * 
     * @param url
     * @param param
     * @return
     */
    public static String getSerchPersion(String url, String param) {
        httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
        GetMethod getMethod = new GetMethod(url);
        getMethod.getParams().setParameter(HttpMethodParams.SO_TIMEOUT, 5000);
        getMethod.getParams().setParameter("user-agent",Global.configInfo.getSerchPersion.getValue());
        getMethod.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler());
        StringBuilder response = new StringBuilder();
        try {
            int statusCode = httpClient.executeMethod(getMethod);
            if (statusCode != HttpStatus.SC_OK) {
                System.err.println("请求出错: " + getMethod.getStatusLine());
            } else {
                try (InputStream is = getMethod.getResponseBodyAsStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
            }
        } catch (HttpException e) {
            System.out.println("请检查输入的URL!");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        } finally {
            getMethod.releaseConnection();
        }
        return response.toString();
    }

    public static String httpGetBili(String url, String param, String cookie) {
        httpClient.getParams().setCookiePolicy(CookiePolicy.BROWSER_COMPATIBILITY);
        httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(5000);
        GetMethod getMethod = new GetMethod(url);

        getMethod.getParams().setParameter(HttpMethodParams.SO_TIMEOUT, 5000);
        getMethod.getParams().setParameter("user-agent",Global.configInfo.getSerchPersion.getValue());
        getMethod.addRequestHeader("user-agent",Global.configInfo.getSerchPersion.getValue());
        if (null != cookie && !cookie.equals("")) {
            getMethod.addRequestHeader("cookie", cookie);
        }

        getMethod.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler());
        StringBuilder response = new StringBuilder();
        try {
            int statusCode = httpClient.executeMethod(getMethod);
            if (statusCode != HttpStatus.SC_OK) {
                System.err.println("请求出错: " + getMethod.getStatusLine());
            } else {
                try (InputStream is = getMethod.getResponseBodyAsStream();
                     InputStreamReader isr = new InputStreamReader(is, "UTF-8");
                     BufferedReader reader = new BufferedReader(isr)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
            }
        } catch (HttpException e) {
            System.out.println("请检查输入的URL!");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        } finally {
            getMethod.releaseConnection();
        }
        return response.toString();
    }
    
    public static String httpGetBili(String url, String cookie,String origin,String referer ) {
    	String ua = null!=Global.useragent && !"".equals(Global.useragent)?Global.useragent:"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", ua);
        if (origin != null && !origin.isEmpty()) {
            requestBuilder.header("Origin", origin);
        }
        if (referer != null && !referer.isEmpty()) {
            requestBuilder.header("Referer", referer);
        }
        if (cookie != null && !cookie.isEmpty()) {
            requestBuilder.header("Cookie", cookie);
        }
        Request request = requestBuilder.build();
        String responseStr = "";
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("请求出错: " + response.code() + " - " + response.message());
            } else {
                ResponseBody body = response.body();
                if (body != null) {
                    byte[] bytes = body.bytes(); 
                    responseStr = new String(bytes, Charset.forName("UTF-8"));
                }
            }
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        }
        return responseStr;
    }
    
    public static byte[] httpGetBiliBytes(String url, String cookie, String origin, String referer) {
        String ua = (Global.useragent != null && !"".equals(Global.useragent))
                ? Global.useragent
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", ua);

        if (origin != null && !origin.isEmpty()) {
            requestBuilder.header("Origin", origin);
        }
        if (referer != null && !referer.isEmpty()) {
            requestBuilder.header("Referer", referer);
        }
        if (cookie != null && !cookie.isEmpty()) {
            requestBuilder.header("Cookie", cookie);
        }

        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("请求出错: " + response.code() + " - " + response.message());
                return null;
            }
            ResponseBody body = response.body();
            if (body != null) {
                return body.bytes(); // 直接返回原始字节
            }
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        }
        return null;
    }

    
    
    

    /**
     * post请求
     * 
     * @param url
     * @param json
     * @return
     */
    public static JSONObject doPost(String url, JSONObject json) {
        DefaultHttpClient client = new DefaultHttpClient();
        HttpPost post = new HttpPost(url);
        JSONObject response = null;
        try {
            StringEntity s = new StringEntity(json.toString());
            s.setContentEncoding("UTF-8");
            s.setContentType("application/json;charset=UTF-8");// 发送json数据需要设置contentType
            post.setEntity(s);
            HttpResponse res = client.execute(post);
            if (res.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                String result = EntityUtils.toString(res.getEntity());// 返回json格式：
                result = new String(result.getBytes("ISO-8859-1"), "utf-8");
                response = JSONObject.parseObject(result);
            }
        } catch (Exception e) {
            client.close();
        }
        return response;
    }

    public static JSONObject doPostNew(String url, JSONObject json) {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost post = new HttpPost(url);
        post.addHeader("Content-Type", "application/json");
        JSONObject response = null;
        try {
            post.setEntity(new StringEntity(json.toString(), "UTF-8"));
            HttpResponse res = httpClient.execute(post);
            if (res.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                String result = EntityUtils.toString(res.getEntity());
                result = new String(result.getBytes("ISO-8859-1"), "utf-8");
                response = JSONObject.parseObject(result);
            }
        } catch (Exception e) {

        } finally {
            try {
                httpClient.close();
            } catch (Exception e2) {
            }
        }
        return response;
    }

    public static String downBiliFromUrl(String urlStr, String fileName, String savePath) throws Exception {
        return downBiliFromUrl(urlStr, fileName, savePath, null);
    }

	public static String downBiliFromUrl(String urlStr, String fileName, String savePath, String cookie)
			throws Exception {
		if (urlStr == null || urlStr.isEmpty() || fileName == null || fileName.isEmpty() || savePath == null
				|| savePath.isEmpty()) {
			throw new IllegalArgumentException("urlStr, fileName, savePath 不能为空");
		}

		int maxRetries = 3;
		int retryCount = 0;
		long retryDelay = 5000;

		while (retryCount < maxRetries) {
			File saveDir = new File(savePath);
			File finalFile = new File(saveDir, fileName);
			File tmpFile = new File(saveDir, fileName + ".downloading");
			long downloaded = 0;
			boolean needRetry = false;

			try {
				if (!saveDir.exists()) {
					saveDir.mkdirs();
				}

				Request.Builder requestBuilder = new Request.Builder().url(urlStr)
						.addHeader("User-Agent", Global.configInfo.BiliDroid.getValue())
						.addHeader("referer", "https://www.bilibili.com");

				if (cookie != null && !cookie.isEmpty()) {
					requestBuilder.addHeader("Cookie", cookie);
				}

				Request request = requestBuilder.build();

				try (Response response = client.newCall(request).execute()) {
					if (!response.isSuccessful()) {
						logger.info("下载失败: " + response.code());
						logger.info(urlStr);
						logger.info(fileName);
						needRetry = true;
					} else {
						long fileLength = response.body().contentLength();
						if (finalFile.exists() && fileLength > 0 && finalFile.length() == fileLength) {
							logger.info("文件已存在且大小一致，跳过: {}", fileName);
							return "0";
						}

						try (BufferedInputStream bis = new BufferedInputStream(response.body().byteStream());
								BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tmpFile))) {

							byte[] buffer = new byte[32 * 1024];
							int len;
							long startTime = System.currentTimeMillis();
							long lastLogTime = startTime;

							while ((len = bis.read(buffer)) != -1) {
								bos.write(buffer, 0, len);
								downloaded += len;

								long now = System.currentTimeMillis();
								if (fileLength > 0 && now - lastLogTime >= 1000) {
									int progress = (int) (downloaded * 100 / fileLength);
									if (progress % 10 == 0) {
										double speed = (downloaded / 1024.0) / ((now - startTime) / 1000.0);
										if (logger.isDebugEnabled()) {
											logger.debug("下载进度: {}%, 速度: {:.2f} KB/s, 文件: {}", progress, speed,
													fileName);
										}
									}
									lastLogTime = now;
								}
							}
							bos.flush();
						}

						// 文件大小校验
						if (fileLength > 0 && tmpFile.length() != fileLength) {
							logger.info("文件大小不匹配，下载失败");
							needRetry = true;
						}
						// 下载成功后尝试重命名
						if (!needRetry) {
							Files.move(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
							logger.info("文件下载完成: {}", finalFile.getAbsolutePath());
							return "0";
						}

					}

				}

			} catch (SocketTimeoutException e) {
				logger.warn("下载超时: 第{}次: {}", retryCount + 1, fileName);
				needRetry = true;
			} catch (IOException e) {
				logger.error("下载异常: {}", e.getMessage(), e);
			} finally {
				if (needRetry && tmpFile.exists()) {
					tmpFile.delete();
				}
			}

			retryCount++;
			if (retryCount < maxRetries) {
				Thread.sleep(retryDelay);
			}
		}

		logger.error("下载失败，重试多次: {}", fileName);
		return "1";
	}


    @SuppressWarnings("unused")
    private static class InputStreamWithProgress extends FilterInputStream {
        private long totalBytes;
        private long bytesRead;

        protected InputStreamWithProgress(InputStream in, long totalBytes) {
            super(in);
            this.totalBytes = totalBytes;
            this.bytesRead = 0;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int bytesRead = super.read(b, off, len);
            if (bytesRead != -1) {
                this.bytesRead += bytesRead;
            }
            return bytesRead;
        }

        public int getProgress() {
            if (totalBytes > 0) {
                return (int) ((bytesRead * 100) / totalBytes);
            } else {
                return 0;
            }
        }
    }

    public static void downDouFromUrl(String urlStr, String fileName, String savePath, String cookie) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5 * 1000);
            conn.setRequestProperty("User-Agent", DouUtil.ua);
            if (cookie != null) {
                conn.setRequestProperty("cookie", cookie);
            }
            InputStream input = conn.getInputStream();
            byte[] getData = readInputStream(input);
            File saveDir = new File(savePath);
            if (!saveDir.exists()) {
                FileUtils.createDirectory(savePath);
            }
            File file = new File(saveDir + File.separator + fileName);
            FileOutputStream output = new FileOutputStream(file);
            output.write(getData);
            if (output != null) {
                output.close();
            }
            if (input != null) {
                input.close();
            }
        } catch (Exception e) {

        }
    }



    public static byte[] readInputStream(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[10240];
        int len = 0;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while ((len = inputStream.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        bos.close();
        return bos.toByteArray();
    }

	public static void downloadFile(String urlStr, String fileName, String savePath, Map<String, String> headers)
			throws IOException {
		if (urlStr == null || urlStr.isEmpty() || fileName == null || fileName.isEmpty() || savePath == null
				|| savePath.isEmpty()) {
			throw new IllegalArgumentException("urlStr, fileName, savePath 不能为空");
		}
		int maxRetries = 3;
		int retryCount = 0;
		long retryDelay = 5000;
		while (retryCount < maxRetries) {
			boolean needRetry = false;
			File saveDir = new File(savePath);
			File finalFile = new File(saveDir, fileName);
			File tmpFile = new File(saveDir, fileName + ".tmp"); // 使用临时文件
			HttpURLConnection conn = null;
			InputStream is = null;
			OutputStream os = null;
			try {
				if (!saveDir.exists()) {
					saveDir.mkdirs();
				}
				URL url = new URL(urlStr);
				conn = (HttpURLConnection) url.openConnection();
				conn.setConnectTimeout(20000);
				conn.setReadTimeout(60000);
				// 设置 Header
				if (headers != null) {
					for (Map.Entry<String, String> entry : headers.entrySet()) {
						conn.setRequestProperty(entry.getKey(), entry.getValue());
					}
				}
				int responseCode = conn.getResponseCode();
				if (responseCode != HttpURLConnection.HTTP_OK) {
					throw new IOException("服务器响应错误: " + responseCode);
				}
				long fileLength = conn.getContentLengthLong();
				if (finalFile.exists() && fileLength > 0 && finalFile.length() == fileLength) {
					logger.info("文件已存在且大小匹配，跳过: {}", fileName);
					return;
				}
				is = new BufferedInputStream(conn.getInputStream());
				os = new BufferedOutputStream(new FileOutputStream(tmpFile));
				byte[] buffer = new byte[32 * 1024];
				int len;
				long downloaded = 0;
				long startTime = System.currentTimeMillis();
				long lastProgressTime = startTime;
				long lastDataTime = startTime;
				while ((len = is.read(buffer)) != -1) {
					os.write(buffer, 0, len);
					downloaded += len;
					long currentTime = System.currentTimeMillis();
					if (len > 0) {
						lastDataTime = currentTime;
					}
					if (currentTime - lastDataTime > 30000) {
						throw new SocketTimeoutException("读取数据流停滞超时");
					}
					if (fileLength > 0 && currentTime - lastProgressTime >= 15000) {
						int progress = (int) (downloaded * 100 / fileLength);
						double speed = (downloaded / 1024.0) / ((currentTime - startTime) / 1000.0);
						logger.info("下载进度: {}%, 速度: {:.2f} KB/s, 文件: {}", progress, speed, fileName);
						lastProgressTime = currentTime;
					}
				}
				os.flush();
				os.close(); 
				if (fileLength > 0 && tmpFile.length() != fileLength) {
					throw new IOException("下载不完整: 预期 " + fileLength + "，实际 " + tmpFile.length());
				}
				Files.move(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				logger.info("文件下载完成: {}", fileName);
				return; 

			} catch (Exception e) {
				logger.warn("第 {} 次尝试失败 ({}): {}", retryCount + 1, e.getClass().getSimpleName(), e.getMessage());
				needRetry = true;
			} finally {
				closeQuietly(is);
				closeQuietly(os);
				if (conn != null)
					conn.disconnect();
				if (needRetry && tmpFile.exists()) {
					tmpFile.delete();
				}
			}
			retryCount++;
			if (retryCount < maxRetries) {
				try {
					Thread.sleep(retryDelay);
				} catch (InterruptedException ignored) {
				}
			}
		}
		throw new IOException("下载失败，重试 " + maxRetries + " 次后依然出错: " + fileName);
	}

    private static void closeQuietly(Closeable c) {
		if (c != null) {
			try {
				c.close();
			} catch (IOException ignored) {
			}
		}
	}

	public static String downloadFileWithOkHttp(String urlStr, String fileName, String savePath,
			Map<String, String> headers) throws IOException {
		if (urlStr == null || urlStr.isEmpty() || fileName == null || fileName.isEmpty() || savePath == null
				|| savePath.isEmpty()) {
			logger.info("----------------打印调试参数-------------------");
			logger.info(urlStr);
			logger.info(fileName);
			logger.info("----------------打印调试参数-------------------");
			throw new IllegalArgumentException("urlStr, fileName, savePath 不能为空");
		}
		int maxRetries = 3;
		int retryCount = 0;
		long retryDelay = 5000;
		while (retryCount < maxRetries) {
			File saveDir = new File(savePath);
			File finalFile = new File(saveDir, fileName);
			File tmpFile = new File(saveDir, fileName + ".downloading");
			long downloaded = 0;
			boolean needRetry = false;
			try {
				if (!saveDir.exists()) {
					saveDir.mkdirs();
				}
				Request.Builder requestBuilder = new Request.Builder().url(urlStr).addHeader("Connection",
						"keep-alive");
				if (headers != null) {
					for (Map.Entry<String, String> entry : headers.entrySet()) {
						requestBuilder.addHeader(entry.getKey(), entry.getValue());
					}
				}
				try (Response response = client.newCall(requestBuilder.build()).execute()) {
					if (!response.isSuccessful()) {
						logger.info("下载失败: " + response.code());
						logger.info("----------------打印调试参数-------------------");
						logger.info(urlStr);
						logger.info(fileName);
						logger.info("----------------打印调试参数-------------------");
						needRetry = true;
					} else {
						long fileLength = response.body().contentLength();
						if (finalFile.exists() && fileLength > 0 && finalFile.length() == fileLength) {
							logger.info("文件已存在且大小相同,跳过下载: {}", fileName);
							return "0";
						}
						long startTime = System.currentTimeMillis();
						long lastProgressTime = startTime;
						long lastBytesRead = 0;
						boolean downloadSuccess = false;
						try (BufferedInputStream bis = new BufferedInputStream(response.body().byteStream());
								BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tmpFile))) {
							byte[] buffer = new byte[16 * 1024];
							int len;
							while ((len = bis.read(buffer)) != -1) {
								bos.write(buffer, 0, len);
								downloaded += len;
								long currentTime = System.currentTimeMillis();
								if (fileLength > 0 && currentTime - lastProgressTime >= 15000) {
									int progress = (int) (downloaded * 100 / fileLength);
									double averageSpeed = (downloaded / 1024.0)
											/ Math.max(1, (currentTime - startTime) / 1000.0);
									double instantSpeed = ((downloaded - lastBytesRead) / 1024.0)
											/ Math.max(1, (currentTime - lastProgressTime) / 1000.0);
									long remainingBytes = fileLength - downloaded;
									long remainingTime = averageSpeed > 0
											? (long) (remainingBytes / (averageSpeed * 1024))
											: 0;
									logger.info("下载进度: {}%, 平均速度: {} KB/s, 实时速度: {} KB/s, 剩余时间: {} 秒, 文件: {}", progress,
											String.format("%.2f", averageSpeed), String.format("%.2f", instantSpeed),
											remainingTime, fileName);
									lastProgressTime = currentTime;
									lastBytesRead = downloaded;
								}
							}
							bos.flush();
							if (fileLength > 0 && tmpFile.length() != fileLength) {
								logger.info("文件下载不完整");
								needRetry = true;
							}else {
								downloadSuccess = true;
							}
						}
						if (downloadSuccess) {
							Files.move(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
							logger.info("文件下载完成: {}", fileName);
							return "0";
						}
					}

				}
			} catch (SocketTimeoutException e) {
				logger.warn("下载超时(第 {} 次重试): {}", retryCount + 1, fileName);
				needRetry = true;
			} catch (IOException e) {
				needRetry = true;
				logger.error("下载出错: {}", e.getMessage(), e);
				logger.info("----------------打印调试参数-------------------");
				logger.info(urlStr);
				logger.info(fileName);
				logger.info("----------------打印调试参数-------------------");
			} finally {
				if (needRetry && tmpFile.exists()) {
					tmpFile.delete();
				}
			}
			retryCount++;
			if (retryCount < maxRetries) {
				try {
					Thread.sleep(retryDelay);
				} catch (InterruptedException ignored) {
				}
			}
		}
		logger.info("----------------打印调试参数-------------------");
		logger.info(urlStr);
		logger.info(fileName);
		logger.info("----------------打印调试参数-------------------");
		logger.error("下载失败，已重试多次: " + fileName);
		return "1";
	}


	public static String downloadFileWithOkHttp(String urlStr, String fileName, String savePath,
			Map<String, String> headers, int threadCount) throws IOException, InterruptedException {
		if (urlStr == null || urlStr.isEmpty() || fileName == null || fileName.isEmpty() || savePath == null
				|| savePath.isEmpty()) {
			logger.info("----------------打印调试参数-------------------");
			logger.info("URL: " + urlStr);
			logger.info("File: " + fileName);
			logger.info("---------------------------------------------");
			throw new IllegalArgumentException("urlStr, fileName, savePath 不能为空");
		}
		File saveDir = new File(savePath);
		if (!saveDir.exists())
			saveDir.mkdirs();
		File tmpFile = new File(saveDir, fileName + ".tmp");
		File finalFile = new File(saveDir, fileName);
		long totalLength = -1;
		int probeRetry = 0;
		while (probeRetry < 3) {
			Request.Builder rangeBuilder = new Request.Builder().url(urlStr).addHeader("Range", "bytes=0-0")
					.addHeader("Connection", "keep-alive");

			if (headers != null) {
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					rangeBuilder.addHeader(entry.getKey(), entry.getValue());
				}
			}
			try (Response rangeResponse = client.newCall(rangeBuilder.build()).execute()) {
				if (rangeResponse.isSuccessful()) {
					String contentRange = rangeResponse.header("Content-Range");
					if (contentRange != null && contentRange.contains("/")) {
						totalLength = Long.parseLong(contentRange.split("/")[1]);
						break;
					}
				}
				logger.warn("探测文件大小第 {} 次失败: {}", probeRetry + 1, rangeResponse.code());
			} catch (Exception e) {
				logger.warn("探测异常: {}", e.getMessage());
			}
			probeRetry++;
			if (totalLength <= 0)
				Thread.sleep(2000);
		}
		if (totalLength <= 0) {
			logger.error("无法获取文件长度，任务终止");
			return "1";
		}
		if (finalFile.exists() && finalFile.length() == totalLength) {
			logger.info("文件已存在且大小一致，跳过下载: {}", fileName);
			return "0";
		}
		try (RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw")) {
			raf.setLength(totalLength);
		}
		long partSize = totalLength / threadCount;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		ScheduledExecutorService progressExecutor = Executors.newSingleThreadScheduledExecutor();
		CountDownLatch latch = new CountDownLatch(threadCount);

		AtomicBoolean allPartsSuccess = new AtomicBoolean(true);
		AtomicLong[] downloadedBytes = new AtomicLong[threadCount];
		boolean[] finished = new boolean[threadCount];
		for (int i = 0; i < threadCount; i++) {
			downloadedBytes[i] = new AtomicLong(0);
			finished[i] = false;
		}
		final long finalTotalLength = totalLength;
		final long[] lastBytes = new long[threadCount];
		progressExecutor.scheduleAtFixedRate(() -> {
			StringBuilder sb = new StringBuilder("\n--- 下载进度报告 ---\n");
			for (int i = 0; i < threadCount; i++) {
				if (finished[i]) {
					sb.append(String.format("分片 %d: 已完成; ", i));
					continue;
				}
				long current = downloadedBytes[i].get();
				long thisPartSize = (i == threadCount - 1) ? (finalTotalLength - partSize * i) : partSize;
				double progress = (current * 100.0) / thisPartSize;
				double speed = (current - lastBytes[i]) / 1024.0 / 15.0;
				lastBytes[i] = current;
				sb.append(String.format("分片 %d: %.2f%%, 速度: %.2f KB/s; ", i, Math.min(progress, 100.0), speed));
			}
			logger.info(sb.toString());
		}, 15, 15, TimeUnit.SECONDS);
		for (int i = 0; i < threadCount; i++) {
			final int part = i;
			final long start = part * partSize;
			final long end = (part == threadCount - 1) ? totalLength - 1 : (start + partSize - 1);
			executor.execute(() -> {
				try {
					Thread.sleep(part * 2000L);
				} catch (InterruptedException ignored) {
				}

				int retry = 0;
				boolean success = false;
				while (retry < 5 && !success) {
					downloadedBytes[part].set(0);
					try {
						Request.Builder requestBuilder = new Request.Builder().url(urlStr).addHeader("Range",
								"bytes=" + start + "-" + end);

						if (headers != null) {
							for (Map.Entry<String, String> entry : headers.entrySet()) {
								requestBuilder.addHeader(entry.getKey(), entry.getValue());
							}
						}
						try (Response response = client.newCall(requestBuilder.build()).execute()) {
							if (!response.isSuccessful()) {
								throw new IOException("HTTP Error: " + response.code());
							}
							try (BufferedInputStream bis = new BufferedInputStream(response.body().byteStream());
									RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw")) {
								raf.seek(start);
								byte[] buffer = new byte[128 * 1024];
								int len;
								while ((len = bis.read(buffer)) != -1) {
									raf.write(buffer, 0, len);
									downloadedBytes[part].addAndGet(len);
								}
							}
							success = true;
							finished[part] = true;
							logger.info("分片 {} 下载成功", part);
						}
					} catch (Exception e) {
						retry++;
						logger.warn("分片 {} 第 {} 次异常: {}", part, retry, e.getMessage());
						try {
							Thread.sleep(5000);
						} catch (InterruptedException ignored) {
						}
					}
				}

				if (!success) {
					allPartsSuccess.set(false);
				}
				latch.countDown();
			});
		}
		latch.await();
		executor.shutdown();
		progressExecutor.shutdownNow();
		if (!allPartsSuccess.get()) {
			logger.error("多线程下载失败：部分分片未能完成任务");
			return "1";
		}
		if (tmpFile.length() != totalLength) {
			logger.error("文件长度校验异常");
			return "1";
		}
		try {
			Files.move(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			logger.info("多线程下载成功: {}", fileName);
			return "0";
		} catch (IOException e) {
			logger.error("移动文件失败: {}", e.getMessage());
			return "1";
		}
	}
    
    public String decompressGzip(byte[] compressedData) throws IOException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(compressedData);
        GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream);
        InputStreamReader reader = new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8);
        BufferedReader bufferedReader = new BufferedReader(reader);

        StringBuilder stringBuilder = new StringBuilder();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            stringBuilder.append(line);
        }
        return stringBuilder.toString();
    }
    
    
    public static String httpGetBili(String url, String cookie) {
        GetMethod getMethod = new GetMethod(url);
        getMethod.getParams().setParameter("user-agent", Global.configInfo.getSerchPersion.getValue());

        if (cookie != null && !cookie.isEmpty()) {
            getMethod.addRequestHeader("cookie", cookie);
        }

        getMethod.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new DefaultHttpMethodRetryHandler());
        StringBuilder response = new StringBuilder();

        try {
            int statusCode = httpClient.executeMethod(getMethod);
            if (statusCode != HttpStatus.SC_OK) {
                System.err.println("请求出错: " + getMethod.getStatusLine());
            } else {
                // 获取 Content-Encoding 头，检查是否是 gzip 压缩
                String contentEncoding = getMethod.getResponseHeader("Content-Encoding") != null
                        ? getMethod.getResponseHeader("Content-Encoding").getValue()
                        : "";

                InputStream is = getMethod.getResponseBodyAsStream();

                // 如果是 gzip 压缩，则解压
                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    is = new GZIPInputStream(is); // 解压 gzip 数据
                }

                try (InputStreamReader isr = new InputStreamReader(is, "UTF-8");
                     BufferedReader reader = new BufferedReader(isr)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
            }
        } catch (HttpException e) {
            System.out.println("请检查输入的URL!");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("发生网络异常!");
            e.printStackTrace();
        } finally {
            getMethod.releaseConnection();
        }

        // 解析 HTML 获取 1-name 元素的文本
        Document document = Jsoup.parse(response.toString());
        Element nameElement = document.getElementById("1-name"); // 获取 id="1-name" 的元素
        return (nameElement != null) ? nameElement.text() : null; // 返回元素的文本内容
    }

    

}
