package com.yny.downloadlibrary;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import android.util.Log;

public class DownloadThread extends Thread {
	private static final String TAG = "DownloadThread";
	private File saveFile;
	private URL downUrl;
	private int block;
	private int threadId = -1;
	private int downloadedLength;
	private boolean finished = false;
	private FileDownloader downloader;
	
	public DownloadThread(FileDownloader downloader, URL downUrl, File saveFile, int block, int downloadedLength, int threadId) {
		this.downUrl = downUrl;
		this.saveFile = saveFile;
		this.block = block;
		this.downloader = downloader;
		this.threadId = threadId;
		this.downloadedLength = downloadedLength;
	}
	
	@Override
	public void run() {
		if(downloadedLength < block){
			try {
				HttpURLConnection http = (HttpURLConnection) downUrl.openConnection();
				http.setConnectTimeout(10 * 1000);
				http.setRequestMethod("GET");
				http.setRequestProperty("Accept", "*/*");
				http.setRequestProperty("Accept-Language", "zh-CN");
				http.setRequestProperty("Referer", downUrl.toString());
				http.setRequestProperty("Charset", "UTF-8");
				int startPos = block * (threadId - 1) + downloadedLength;
				int endPos = block * threadId -1;
				http.setRequestProperty("Range", "bytes=" + startPos + "-"+ endPos);
				//http.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 8.0; Windows NT 5.2; Trident/4.0; .NET CLR 1.1.4322; .NET CLR 2.0.50727; .NET CLR 3.0.04506.30; .NET CLR 3.0.4506.2152; .NET CLR 3.5.30729)");
				http.setRequestProperty("Connection", "Keep-Alive");
				
				InputStream inStream = http.getInputStream();
				int byteCount = 1024 * 8;
				byte[] buffer = new byte[byteCount]; //-- * 8 try
				int offset = 0;
				print("Thread " + this.threadId + " starts to download from position "+ startPos);
				RandomAccessFile threadFile = new RandomAccessFile(this.saveFile, "rwd"); 
				// 获取RandomAccessFile的FileChannel 建立缓冲 
                FileChannel outFileChannel = threadFile.getChannel(); 
                //直接移动到文件开始位置下载
                outFileChannel.position(startPos);  
				while (!downloader.getExited() && (offset = inStream.read(buffer)) != -1) { //-- * 8 try
					outFileChannel.write(ByteBuffer.wrap(buffer, 0, offset));
					downloadedLength += offset;
					//downloader.update(this.threadId, downloadedLength);	
					downloader.append(offset);
				}
  
                outFileChannel.close();  
				threadFile.close();	
				inStream.close();
				
				if(downloader.getExited())
					print("Thread " + this.threadId + " has been paused");
				else
					print("Thread " + this.threadId + " download finish");
				
				this.finished = true;
			} catch (Exception e) {
				downloader.update(this.threadId, downloadedLength);
				this.downloadedLength = -1;	
				print("Thread "+ this.threadId+ ":"+ e);
			}
		}
	}

	private static void print(String msg){
		Log.i(TAG, msg);
	}
	
	public boolean isFinished() {
		return finished;
	}

	public int getDownloadedLength() {
		return downloadedLength;
	}
	
	public int getThreadId() {
		return threadId;
	}
}
