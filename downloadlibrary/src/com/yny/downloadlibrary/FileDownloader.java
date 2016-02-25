package com.yny.downloadlibrary;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.Context;
import android.util.Log;

public class FileDownloader {
	private static final String TAG = "FileDownloader";
	private Context context;
	private FileService fileService;
	private boolean exited;
	//已下载字节数
	private int downloadedSize = 0;
	private int downloadPercent = 0;  
	private int downloadTime = 0;
	private int fileSize = 0;
	private DownloadThread[] threads;
	private File saveFile;
	private Map<Integer, Integer> data = new ConcurrentHashMap<Integer, Integer>();
	//分块大小
	private int block;
	private String downloadUrl;

	public int getThreadSize() {
		return threads.length;
	}

	public void exit() {
		//退出的时候保存下载进度，后面继续下载
		for(int i = 0,len = threads.length; i < len; i++) {
			update(threads[i].getThreadId(), threads[i].getDownloadedLength());
		}
		this.exited = true;
	}

	public boolean getExited() {
		return this.exited;
	}

	public int getFileSize() {
		return fileSize;
	}

	public int getDownloadPercent(){  
		return downloadPercent;  
	}  

	protected synchronized void append(int size) {
		downloadedSize += size;
	}

	protected synchronized void update(int threadId, int pos) {
		this.data.put(threadId, pos);
		this.fileService.update_tyc(this.downloadUrl, threadId, pos);
		//this.fileService.update(this.downloadUrl, this.data);
	}

	public FileDownloader(Context context, String downloadUrl, File fileSaveDir, int threadNum) {
		try {
			this.context = context;
			this.downloadUrl = downloadUrl;
			fileService = new FileService(this.context);
			URL url = new URL(this.downloadUrl);
			if (!fileSaveDir.exists())
				fileSaveDir.mkdirs();
			this.threads = new DownloadThread[threadNum];

			//http协议头
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(10 * 1000);
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Accept","*/*");
			conn.setRequestProperty("Accept-Language", "zh-CN");
			conn.setRequestProperty("Referer", downloadUrl);
			conn.setRequestProperty("Charset", "UTF-8");
			//conn.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 8.0; Windows NT 5.2; Trident/4.0; .NET CLR 1.1.4322; .NET CLR 2.0.50727; .NET CLR 3.0.04506.30; .NET CLR 3.0.4506.2152; .NET CLR 3.5.30729)");
			conn.setRequestProperty("Connection", "Keep-Alive");
			conn.connect();
			printResponseHeader(conn);

			if (conn.getResponseCode() == 200) {
				//获取文件总大小
				this.fileSize = conn.getContentLength();
				if (this.fileSize <= 0)
					throw new RuntimeException("Unknown file size ");

				//查询对应线程中已下载文件长度
				String filename = getFileName(conn);
				this.saveFile = new File(fileSaveDir, filename);
				Map<Integer, Integer> logdata = fileService.getData(downloadUrl);

				if (logdata.size() > 0) {
					for (Map.Entry<Integer, Integer> entry : logdata.entrySet())
						data.put(entry.getKey(), entry.getValue());
				}

				//当线程数和查询到数据库的条数相同时
				if (this.data.size() == this.threads.length) {
					for (int i = 0; i < this.threads.length; i++) {
						this.downloadedSize += this.data.get(i + 1);
						this.downloadPercent = (downloadedSize * 100) / fileSize;
					}
					print("已经下载的长度" + this.downloadedSize + "个字节");
				}

				//如果文件大小能被线程数量整除，分成对应大小。否则文件块加一
				this.block = (this.fileSize % this.threads.length) == 0 
						? this.fileSize / this.threads.length 
								: this.fileSize / this.threads.length + 1;
			} else {
				print("服务器响应错误:" + conn.getResponseCode() + conn.getResponseMessage());
				throw new RuntimeException("server response error ");
			}
		} catch (Exception e) {
			print(e.toString());
			throw new RuntimeException("Can't connection this url");
		}
	}

	private String getFileName(HttpURLConnection conn) {
		String filename = this.downloadUrl.substring(this.downloadUrl.lastIndexOf('/') + 1);

		if (filename == null || "".equals(filename.trim())) {
			for (int i = 0;; i++) {
				String mine = conn.getHeaderField(i);
				if (mine == null)
					break;
				if ("content-disposition".equals(conn.getHeaderFieldKey(i).toLowerCase(Locale.getDefault()))) {
					Matcher m = Pattern.compile(".*filename=(.*)").matcher(mine.toLowerCase(Locale.getDefault()));
					if (m.find())
						return m.group(1);
				}
			}
			filename = UUID.randomUUID() + ".tmp";
		}
		return filename;
	}

	public int download(DownloadProgressListener listener) throws Exception {
		try {
			RandomAccessFile randOut = new RandomAccessFile(this.saveFile, "rwd");
			if (this.fileSize > 0)
				randOut.setLength(this.fileSize);
			randOut.close();
			URL url = new URL(this.downloadUrl);

			if (this.data.size() != this.threads.length) {
				this.data.clear();
				for (int i = 0; i < this.threads.length; i++) {
					this.data.put(i + 1, 0);
				}
				this.downloadedSize = 0;
			}
			for (int i = 0; i < this.threads.length; i++) {
				int downloadedLength = this.data.get(i + 1);
				if (downloadedLength < this.block && this.downloadedSize < this.fileSize) {
					this.threads[i] = new DownloadThread(this, url, this.saveFile, this.block, this.data.get(i + 1), i + 1);
					this.threads[i].setPriority(7);
					this.threads[i].start();
				} else {
					this.threads[i] = null;
				}
			}
			this.fileService.delete(this.downloadUrl);
			this.fileService.save(this.downloadUrl, this.data);
			boolean notFinished = true;
			//开始时间
            long startTime = System.currentTimeMillis(); 
            long curTime = System.currentTimeMillis();
			while (notFinished) {
				//当没有处理完时，休眠并处理
				Thread.sleep(1000);
				notFinished = false;
				for (int i = 0; i < this.threads.length; i++) {
					if (this.threads[i] != null && !this.threads[i].isFinished()) {
						notFinished = true;
						if (this.threads[i].getDownloadedLength() == -1) {
							this.threads[i] = new DownloadThread(this, url, this.saveFile, this.block, this.data.get(i + 1), i + 1);
							this.threads[i].setPriority(7);
							this.threads[i].start();
						}
					}
				}

				curTime = System.currentTimeMillis();
				
				//执行当前任务所用的时间
				this.downloadTime = (int) (curTime - startTime) / 1000;
				
				if (listener != null)
					listener.onDownloadSize(this.fileSize, this.downloadedSize,this.downloadPercent,this.downloadTime);
			}
			if (downloadedSize == this.fileSize)
				this.fileService.delete(this.downloadUrl);
		} catch (Exception e) {
			print(e.toString());
			throw new Exception("File downloads error");
		}
		return this.downloadedSize;
	}

	public static Map<String, String> getHttpResponseHeader(HttpURLConnection http) {
		Map<String, String> header = new LinkedHashMap<String, String>();
		for (int i = 0;; i++) {
			String mine = http.getHeaderField(i);
			if (mine == null)
				break;
			header.put(http.getHeaderFieldKey(i), mine);
		}
		return header;
	}

	public static void printResponseHeader(HttpURLConnection http) {
		Map<String, String> header = getHttpResponseHeader(http);
		for (Map.Entry<String, String> entry : header.entrySet()) {
			String key = entry.getKey() != null ? entry.getKey() + ":" : "";
			print(key + entry.getValue());
		}
	}

	private static void print(String msg) {
		Log.i(TAG, msg);
	}
}
