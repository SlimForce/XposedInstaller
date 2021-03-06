package de.robv.android.xposed.installer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import de.robv.android.xposed.installer.util.AssetUtil;
import de.robv.android.xposed.installer.util.ModuleUtil;
import de.robv.android.xposed.installer.util.NotificationUtil;
import de.robv.android.xposed.installer.util.RepoLoader;

public class XposedApp extends Application implements ActivityLifecycleCallbacks {
	public static final String TAG = "XposedInstaller";

	@SuppressLint("SdCardPath")
	public static final String BASE_DIR = "/data/data/de.robv.android.xposed.installer/";
	private static final File XPOSED_PROP_FILE = new File("/system/xposed.prop");

	private static XposedApp mInstance = null;
	private static Thread mUiThread;
	private static Handler mMainHandler;

	private boolean mIsUiLoaded = false;
	private Activity mCurrentActivity = null;
	private SharedPreferences mPref;
	private Map<String, String> mXposedProp;

	public void onCreate() {
		super.onCreate();
		mInstance = this;
		mUiThread = Thread.currentThread();
		mMainHandler = new Handler();

		mPref = PreferenceManager.getDefaultSharedPreferences(this);
		reloadXposedProp();
		createDirectories();
		cleanup();
		NotificationUtil.init();
		AssetUtil.checkStaticBusyboxAvailability();
		AssetUtil.removeBusybox();

		registerActivityLifecycleCallbacks(this);
	}

	private void createDirectories() {
		mkdirAndChmod("bin", 00771);
		mkdirAndChmod("conf", 00771);
		mkdirAndChmod("log", 00777);
	}

	private void cleanup() {
		if (!mPref.getBoolean("cleaned_up_sdcard", false)) {
			if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
				File sdcard = Environment.getExternalStorageDirectory();
				new File(sdcard, "Xposed-Disabler-CWM.zip").delete();
				new File(sdcard, "Xposed-Disabler-Recovery.zip").delete();
				new File(sdcard, "Xposed-Installer-Recovery.zip").delete();
				mPref.edit().putBoolean("cleaned_up_sdcard", true).apply();
			}
		}

		if (!mPref.getBoolean("cleaned_up_debug_log", false)) {
			new File(XposedApp.BASE_DIR + "log/debug.log").delete();
			new File(XposedApp.BASE_DIR + "log/debug.log.old").delete();
			mPref.edit().putBoolean("cleaned_up_debug_log", true).apply();
		}
	}

	private void mkdirAndChmod(String dir, int permissions) {
		dir = BASE_DIR + dir;
		new File(dir).mkdir();
		FileUtils.setPermissions(dir, permissions, -1, -1);
	}

	public static XposedApp getInstance() {
		return mInstance;
	}

	public static void runOnUiThread(Runnable action) {
		if (Thread.currentThread() != mUiThread) {
			mMainHandler.post(action);
		} else {
			action.run();
		}
	}

	// This method is hooked by XposedBridge to return the current version
	public static int getActiveXposedVersion() {
		return -1;
	}

	private void reloadXposedProp() {
		Map<String, String> map = Collections.emptyMap();
		if (XPOSED_PROP_FILE.canRead()) {
			FileInputStream is = null;
			try {
				is = new FileInputStream(XPOSED_PROP_FILE);
				map = parseXposedProp(is);
			} catch (IOException e) {
				Log.e(XposedApp.TAG, "Could not read " + XPOSED_PROP_FILE.getPath(), e);
			} finally {
				if (is != null) {
					try {
						is.close();
					} catch (IOException ignored) {}
				}
			}
		}

		synchronized (this) {
			mXposedProp = map;
		}
	}

	private Map<String, String> parseXposedProp(InputStream stream) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
		Map<String, String> map = new LinkedHashMap<String, String>();
		String line;
		while ((line = reader.readLine()) != null) {
			String[] parts = line.split("=", 2);
			if (parts.length != 2)
				continue;

			String key = parts[0].trim();
			if (key.charAt(0) == '#')
				continue;

			map.put(key, parts[1].trim());
		}
		return Collections.unmodifiableMap(map);
	}

	public static Map<String, String> getXposedProp() {
		synchronized (mInstance) {
			return mInstance.mXposedProp;
		}
	}

	public boolean areDownloadsEnabled() {
		if (!mPref.getBoolean("enable_downloads", true))
			return false;

		if (checkCallingOrSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED)
			return false;

		return true;
	}

	public static SharedPreferences getPreferences() {
		return mInstance.mPref;
	}

	public void updateProgressIndicator() {
		final boolean isLoading = RepoLoader.getInstance().isLoading() || ModuleUtil.getInstance().isLoading();
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				synchronized (XposedApp.this) {
					if (mCurrentActivity != null)
						mCurrentActivity.setProgressBarIndeterminateVisibility(isLoading);
				}
			}
		});
	}

	@Override
	public synchronized void onActivityCreated(Activity activity, Bundle savedInstanceState) {
		if (mIsUiLoaded)
			return;

		RepoLoader.getInstance().triggerFirstLoadIfNecessary();
		mIsUiLoaded = true;
	}

	@Override
	public synchronized void onActivityResumed(Activity activity) {
		mCurrentActivity = activity;
		updateProgressIndicator();
	}

	@Override
	public synchronized void onActivityPaused(Activity activity) {
		activity.setProgressBarIndeterminateVisibility(false);
		mCurrentActivity = null;
	}

	@Override public void onActivityStarted(Activity activity) {}
	@Override public void onActivityStopped(Activity activity) {}
	@Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
	@Override public void onActivityDestroyed(Activity activity) {}
}
