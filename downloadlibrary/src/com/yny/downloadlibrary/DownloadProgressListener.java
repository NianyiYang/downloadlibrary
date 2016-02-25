package com.yny.downloadlibrary;

public interface DownloadProgressListener {
	public void onDownloadSize(int fileSize,int downloadedSize, int percent, int time);
}
