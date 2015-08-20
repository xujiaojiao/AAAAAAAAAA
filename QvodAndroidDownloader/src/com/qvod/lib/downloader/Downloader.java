package com.qvod.lib.downloader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import android.util.Log;

import com.qvod.lib.downloader.DownloadTaskInfo.ResponseCode;
import com.qvod.lib.downloader.utils.DownloadUtils;

/**
 * [描述]
 * @author 李理
 * @date 2015年8月17日
 */
public class Downloader implements IDownloader {
	
	private final static String TAG = Downloader.class.getSimpleName();

	private DownloadParameter mDownloadParameter;
	private DownloadOption mDownloadOption;
	
	private DownloadStateChangeListener mListener;
	
	private AtomicBoolean mIsRun = new AtomicBoolean(false);
	
	private DownloadState mCurrentState;
	private int mErrorResponseCode;
	
	private long mStartDownloadPos = 0;
	private long mEndDownloadPos = 0;
	private long mCurrentDownloadSize = 0;
	
	private long mDownloadContentLength;
	
	private Thread mCurrentDownloadThread;
	
	private RandomAccessFile mWriteAccessFile;
	
	private Map<String, String> mResponseHeader = new HashMap<String, String>();
	
	/**
	 * 是否打印头信息
	 */
	private static boolean IS_PRINT_HEAD_INFO = false;

	
	public void download(String url, String saveFileDir) {
		download(url, saveFileDir, 0, 0);
	}
	
	public void download(String url, String saveFileDir, long startPos, long endPos) {
		DownloadParameter parameter = new DownloadParameter();
		parameter.url = url;
		parameter.saveFileDir = saveFileDir;
		if (startPos > 0) {
			parameter.downloadSegments = new ArrayList<DownloadSegment>();
			DownloadSegment segment = new DownloadSegment();
			segment.startPos = startPos;
			segment.endPos = endPos;
			parameter.downloadSegments.add(segment);
		}
		download(parameter);
	}
	
	@Override
	public void download(DownloadParameter parameter) {
		if (mIsRun.get()) {
			return;
		}
		mIsRun.set(true);
		mDownloadParameter = parameter;
		mCurrentDownloadThread = Thread.currentThread();
		download();
	}

	@Override
	public void stop() {
		mIsRun.set(false);
		if (mCurrentDownloadThread != null) {
			mCurrentDownloadThread.interrupt();
		}
		if (mWriteAccessFile != null) {
			try {
				mWriteAccessFile.close();
			} catch (IOException e) {
				Log.e(TAG, "stop " + e.toString());
			}
		}
	}

	@Override
	public DownloadTaskInfo getDownloadTaskInfo() {
		DownloadTaskInfo taskInfo = new DownloadTaskInfo();
		taskInfo.downloadState = mCurrentState;
		taskInfo.downloadParameter = mDownloadParameter;
		taskInfo.startPos = mStartDownloadPos;
		taskInfo.downloadPos = mCurrentDownloadSize;
		taskInfo.downloadContentLength = mDownloadContentLength;
		taskInfo.errorResponseCode = mErrorResponseCode;
		taskInfo.responseHeader = mResponseHeader;
		return taskInfo;
	}
	
	@Override
	public void setDownloadOption(DownloadOption downloadOption) {
		mDownloadOption = downloadOption;
	}
	
	@Override
	public void setDownloadStateChangeListener(DownloadStateChangeListener listener) {
		mListener = listener;
	}
	
	@Override
	public boolean isDownloading() {
		return mIsRun.get();
	}
	
	private void download() {
		HttpURLConnection conn = null;
		OutputStream out = null;
		
		String url = mDownloadParameter.url;
		String method = mDownloadParameter.method;
		Map<String, String> httpHead = mDownloadParameter.httpHeader;
		int connectTimeout = mDownloadParameter.connectTimeout;
		int readTimeout = mDownloadParameter.readTimeout;
		byte[] postContent = mDownloadParameter.postContent;
		String saveDir = mDownloadParameter.saveFileDir;
		String saveFileName = mDownloadParameter.saveFileName;
		if (mDownloadParameter.downloadSegments != null && mDownloadParameter.downloadSegments.size() > 0) {
			DownloadSegment segment = mDownloadParameter.downloadSegments.get(0);
			mStartDownloadPos = segment.startPos;
			mEndDownloadPos = segment.endPos;
			mCurrentDownloadSize = mStartDownloadPos;
		}
		if (! mIsRun.get()) {
			Log.v(TAG, "download 准备下载 - 任务被暂停 currentDownloadSize:" + mCurrentDownloadSize);
			updateDownloadTaskInfo(DownloadState.STATE_STOP, 0);
			return;
		}
		
		if (DownloadUtils.isEmpty(saveDir)) {
			updateDownloadTaskInfo(DownloadState.STATE_ERROR, ResponseCode.EMPTY_PARAM_SAVE_PATH_ERROR);
			return;
		}
		
		File dirFile = new File(saveDir);
		if (!dirFile.exists()) {
			boolean suc = dirFile.mkdirs();
			if (! suc) {
				//sdcard未挂载
				Log.w(TAG, "download sdcard未挂载");
				updateDownloadTaskInfo(DownloadState.STATE_ERROR, ResponseCode.SDCARD_NOT_MOUNTED_ERROR);
				return;
			}
		}
		
		Log.v(TAG, "download prepare"
				+ " startPos:" + mStartDownloadPos 
				+ " endPos:" + mEndDownloadPos  
				+ " url:" + url);
		updateDownloadTaskInfo(DownloadState.STATE_PREPARE, 0);
		try 
		{
			if (! mIsRun.get()) {
				Log.v(TAG, "download 准备下载 - 任务被暂停 currentDownloadSize:" + mCurrentDownloadSize);
				updateDownloadTaskInfo(DownloadState.STATE_STOP, 0);
				return;
			}
			
			Log.w(TAG, "download 连接中... currentDownloadSize:" + mCurrentDownloadSize);
			conn = getConnection(new URL(url), method, httpHead, connectTimeout, 
					readTimeout, mStartDownloadPos, mEndDownloadPos);	
			Log.v(TAG, "download 连接上 - currentDownloadSize:" + mCurrentDownloadSize  + " - url:" + url);

			if (method.equals(METHOD_POST) && postContent != null) {
				//Post参数
				conn.setDoOutput(true);
				out = conn.getOutputStream();
				if (out != null) {
					out.write(postContent);
				}
			}
			
			int responseCode = conn.getResponseCode();
			if (! mIsRun.get()) {
				Log.i(TAG, "download 任务被暂停 - currentDownloadSize:" + mCurrentDownloadSize + " - url:" + url);
				updateDownloadTaskInfo(DownloadState.STATE_STOP, 0);
				return;
			}
			
			writeHttpHeadMap(conn, mResponseHeader);
//			Log.w(TAG, "download 连接成功 currentDownloadSize:" + currentDownloadSize);
			Log.i(TAG, "connection - responseCode: " + responseCode + " - url:" + url);
			if (! isResponseSuc(responseCode)) {
				//网络连接失败
				Log.i(TAG, "download 连接异常 - currentDownloadSize:" + mCurrentDownloadSize);
				updateDownloadTaskInfo(DownloadState.STATE_ERROR, responseCode);
				return;
			}
			
			if (IS_PRINT_HEAD_INFO) {
				Map<String, List<String>> map = conn.getHeaderFields();
				if (map != null) {
					Iterator<Entry<String, List<String>>> it = map.entrySet().iterator();
					while(it.hasNext()) {
						Entry<String, List<String>> entry = it.next();
						String key = entry.getKey();
						List<String> list = entry.getValue();
						StringBuilder builder = new StringBuilder();
						for(int i = 0;i < list.size();i++) {
							if (i != 0) {
								builder.append(",");
							}
							String value = list.get(i);
							builder.append(value);
						}
						Log.v(TAG, "KEY:" + key + " - VALUE:" + builder.toString());
					}
				}
			}
			
			long contentLength = -1;
			String len = conn.getHeaderField("Content-Length");
			if (DownloadUtils.isNumber(len)) {
				contentLength = Long.parseLong(len.toString());
			}
			if (contentLength <= 0) {
				Log.i(TAG, "download 远程文件长度异常 - currentDownloadSize:" + mCurrentDownloadSize + " - contentLength:" + contentLength);
				updateDownloadTaskInfo(DownloadState.STATE_ERROR, ResponseCode.RESPONSE_CONTENT_LENGTH_ERROR);
				return;
			}
			//对参数的长度矫正
//			Log.v(TAG, "start downloading currentDownloadSize: " + currentDownloadSize + " - contentLength:" + contentLength);
			mDownloadContentLength = contentLength;
			Log.v(TAG, "download - 开始下载 - endDownloadPos:" + mEndDownloadPos  + " - url:" + url);
			long availableSpace = DownloadUtils.getAvailaleSize(dirFile.getParentFile().getPath());
			if(availableSpace <= 0){
				//磁盘空间不足
				Log.e(TAG, "download 磁盘空间不足!!! - currentDownloadSize:" + mCurrentDownloadSize 
						+ " - availableSpace:" + availableSpace + " - contentLength:" + contentLength);
				updateDownloadTaskInfo(DownloadState.STATE_ERROR, ResponseCode.NOT_ENOUGH_SPACE_ERROR);
				return;
			}
			String encoding = conn.getContentEncoding();
			InputStream inputStream;
			if(encoding != null && encoding.contains(GZIP_ENCODING)){
				inputStream= new GZIPInputStream(conn.getInputStream());
			}else{
				inputStream = conn.getInputStream();
			}
			
			if (! mIsRun.get()) {
				Log.i(TAG, "download 任务被暂停2 - currentDownloadSize:" + mCurrentDownloadSize);
				updateDownloadTaskInfo(DownloadState.STATE_STOP, 0);
				return;
			}
			
			if (DownloadUtils.isEmpty(saveFileName)) {
				saveFileName = getFileName();
			}
			String saveFilePath = saveDir + "/" + saveFileName; 
			String tempFilePath = getDownladTempFileName(saveFilePath);
			File file = new File(tempFilePath);
			if (! file.exists()) {
				file.createNewFile();
				Log.v(TAG, "download 创建新文件");
			}
			downloadStream(saveFilePath, mStartDownloadPos, inputStream);
			
			if (mIsRun.get()) {
				updateDownloadTaskInfo(DownloadState.STATE_COMPLETED, ResponseCode.RESULT_SUC);
			} else {
				updateDownloadTaskInfo(DownloadState.STATE_STOP, 0);
			}
			
		} catch (InterruptedIOException e) {
			Log.v(TAG, "download InterruptedIOException " + e.toString());
			updateDownloadTaskInfo(DownloadState.STATE_ERROR, ResponseCode.NETWORK_TIME_OUT_ERROR);
		} catch(IOException e){
			Log.e(TAG, "download IOException " + e.toString());
			updateDownloadTaskInfo(DownloadState.STATE_ERROR, ResponseCode.NETWORK_ERROR);
		} catch (Exception e) {
			Log.e(TAG, "download Exception " + e.toString());
			updateDownloadTaskInfo(DownloadState.STATE_ERROR, ResponseCode.OTHER_ERROR);
		}
		finally {
			if (out != null) {
				try {
					out.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (conn != null) {
				conn.disconnect();
			}
		}
	}
	
	private void downloadStream(String downloadPath, long startPos, InputStream inputStream) throws IOException {
		Log.w(TAG, "downloadStream 下载数据流 downloadPath:" + downloadPath 
				+ " - startDownloadPos:" + startPos + " - fileSize:" + mEndDownloadPos  
				+ " - url:" + mDownloadParameter.url);
		String tempFilePath = getDownladTempFileName(downloadPath);
		File file = new File(tempFilePath);
		if (! file.exists()) {
			throw new RuntimeException("下载文件中途被删除");
		}

		updateDownloadTaskInfo(DownloadState.STATE_DOWNLOAD, 0);

		int offset = 0;
		try {
			mWriteAccessFile = new RandomAccessFile(file, "rw");
			mWriteAccessFile.seek(startPos);
			Log.v(TAG, "downloadStream seek:" + startPos);
			
			int buffereSize = DOWNLOAD_BUFFER_SIZE;
			if (mDownloadOption != null) {
				buffereSize = mDownloadOption.downloadBuffer > 0 ? mDownloadOption.downloadBuffer : buffereSize;
			}
			BufferedInputStream bis = new BufferedInputStream(inputStream, buffereSize);
			byte[] buffer = new byte[buffereSize];
			
			offset = bis.read(buffer, 0, buffereSize);
			if (offset != -1) {
				do {
					if (! mIsRun.get()) {
						Log.i(TAG, "downloadStream 任务被暂停 1 - currentDownloadSize:" + mCurrentDownloadSize);
						break;
					}
					mWriteAccessFile.write(buffer, 0, offset);
					mCurrentDownloadSize += offset;
//					Log.v(TAG, "downloadStream currentDownloadSize:" + mCurrentDownloadSize  + " - url:" + mDownloadParameter.url);
				} while((offset = bis.read(buffer, 0, buffereSize)) != -1);
			}
			Log.v(TAG, "downloadStream 下载结束，当前下载大小：" + mCurrentDownloadSize);
			
			mWriteAccessFile.close();
			if (offset == -1) {
				//下载完成
				File tempFile = new File(tempFilePath);
				File endFile = new File(downloadPath);
				tempFile.renameTo(endFile);
			}
		} finally {
			mWriteAccessFile.close();
			inputStream.close();
		}
	}
	
	private HttpURLConnection getConnection(URL url, String method, 
			Map<String, String> httpHead, int connectTimeOut, int readTimeout, 
			long startPos, long endPos) throws IOException {
		
		HttpURLConnection conn = null;
		
		conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(method);
		conn.setDoInput(true);
		conn.setConnectTimeout(connectTimeOut);
		conn.setReadTimeout(readTimeout);
		conn.setRequestProperty("Accept", "*/*");
		conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=" + DEFAULT_CHARSET);
		conn.setRequestProperty("connection", "Keep-Alive");
 		if (startPos > 0 && endPos > 0) {
			//不能设置end范围，部分服务器不支持end参数会导致请求错误
			conn.setRequestProperty("Range", "bytes=" + startPos + "-" + endPos);
		} else if (startPos > 0) {
			conn.setRequestProperty("Range", "bytes=" + startPos + "-");
		}
		
		if (httpHead != null) {
			Iterator<String> it = httpHead.keySet().iterator();
			while(it.hasNext()) {
				String key = it.next();
				String value = httpHead.get(key);
				
				if (key != null && value != null) {
					conn.setRequestProperty(key, value);
//					Log.v(TAG, "头信息 " + key +"=" + value);
				}
			}
		}
		
		return conn;
	}
	
	private void updateDownloadTaskInfo(DownloadState state, int errorReason) {
		mCurrentState = state;
		mErrorResponseCode = errorReason;
		DownloadTaskInfo taskInfo = getDownloadTaskInfo();
		if (mListener == null) {
			return;
		}
		if (state == DownloadState.STATE_STOP) {
			mListener.onDownloadStateChanged(taskInfo);
		} else
		if (mIsRun.get()) {
			Log.v(TAG, "updateDownloadTaskInfo state:" + state 
					+ " - url:" + mDownloadParameter.url
					+ " - ThreadID:" + Thread.currentThread().getId());
			mListener.onDownloadStateChanged(taskInfo);
		} 
	}
	
	private void writeHttpHeadMap(HttpURLConnection conn, Map<String, String> map) {
		if (map == null) {
			return;
		}
		Map<String, List<String>> headmap = conn.getHeaderFields();
		if (headmap == null) {
			return;
		}
		Iterator<Entry<String, List<String>>> it = headmap.entrySet().iterator();
		while(it.hasNext()) {
			Entry<String, List<String>> entry = it.next();
			String headKey = entry.getKey();
			List<String> headValueList = entry.getValue();
			String headValue = null;
			if (headValueList != null && headValueList.size() > 0) {
				headValue = headValueList.get(0);
				map.put(headKey, headValue);
			}
		}
	}
	
	private String getFileName() {
		String fileName = null;
		String disposition = mResponseHeader.get("Content-Disposition");
		if (disposition != null) {
			String[] pairs = disposition.split(";");
			for(String str : pairs) {
				String pair[] = str.split("=");
				if (pair.length == 2) {
					String key = pair[0];
					if ("filename".equals(key.toLowerCase())) {
						fileName = pair[1];
						if (fileName.startsWith("\"") && fileName.endsWith("\"")
								|| fileName.startsWith("'") && fileName.endsWith("'")) {
							fileName = fileName.substring(1, fileName.length()-1);
						}
					}
				}
			}
		}
		if (fileName == null) {
			fileName = DownloadUtils.getFileName(mDownloadParameter.url);
		}
		return fileName;
	}
	
	protected boolean isResponseSuc(int responseCode) {
		if (responseCode != HttpURLConnection.HTTP_OK && responseCode != 206) {
			return false;
		}
		return true;
	}
	
	private String getDownladTempFileName(String filePath) {
		String tempFilePath = filePath+SUFFIX_TEMP;
		return tempFilePath;
	}
	
	private static int DOWNLOAD_BUFFER_SIZE = 1024;
	
	public static final String DEFAULT_CHARSET = "utf-8";
	public static final String METHOD_POST = "POST";
	public static final String METHOD_GET = "GET";
	public static final String MAP_KEY_RESULT = "result";
	public static final String GZIP_ENCODING = "gzip";//gzip的encode名称
	
	public static final String SUFFIX_TEMP = ".temp";
	
	public static final String CONTENT_TYPE_HTML = "text/html";

}

