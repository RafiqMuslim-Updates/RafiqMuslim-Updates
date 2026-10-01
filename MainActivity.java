package app.rafiq.muslim;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One app: WebView hosting the bundled web UI (assets/www) + native adhan bridge + self-updater. */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    private static final String UPDATE_JSON_URL = "https://raw.githubusercontent.com/RafiqMuslim-Updates/RafiqMuslim-Updates/refs/heads/main/update.json";
    private static final int RQ_NOTIF = 11, RQ_LOC = 12;

    private WebView web;
    private String pendingNotifId;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private ProgressDialog downloadDialog;
    private File downloadedApk;
    private boolean waitingForInstallPermission;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AdhanService.channels(this);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return loader.shouldInterceptRequest(r.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (HOST.equals(r.getUrl().getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) cb.invoke(origin, true, false);
                else { geoCb = cb; geoOrigin = origin; requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }, RQ_LOC); }
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidNative");
        web.loadUrl("https://" + HOST + "/assets/www/index.html");

        // Check for an update in the background without blocking app startup.
        web.postDelayed(this::checkForUpdate, 1200);
    }

    @Override public void onResume() {
        super.onResume();
        if (waitingForInstallPermission && downloadedApk != null && canInstallPackages()) {
            waitingForInstallPermission = false;
            installApk(downloadedApk);
        }
    }

    @Override public void onDestroy() {
        updateExecutor.shutdownNow();
        if (downloadDialog != null && downloadDialog.isShowing()) downloadDialog.dismiss();
        super.onDestroy();
    }

    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    @Override
    public void onRequestPermissionsResult(int rq, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rq, perms, res);
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (rq == RQ_LOC && geoCb != null) { geoCb.invoke(geoOrigin, ok, false); geoCb = null; }
        if (rq == RQ_NOTIF && pendingNotifId != null) { resolve(pendingNotifId, status()); pendingNotifId = null; }
    }

    private void checkForUpdate() {
        updateExecutor.execute(() -> {
            HttpURLConnection c = null;
            try {
                URL url = new URL(UPDATE_JSON_URL);
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestMethod("GET");
                c.setRequestProperty("Accept", "application/json");
                c.setUseCaches(false);
                if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) return;
                InputStream in = new BufferedInputStream(c.getInputStream());
                StringBuilder out = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) out.append(new String(buf, 0, n, "UTF-8"));
                in.close();
                JSONObject update = new JSONObject(out.toString());
                int remoteCode = update.optInt("versionCode", BuildConfig.VERSION_CODE);
                if (remoteCode <= BuildConfig.VERSION_CODE) return;
                String apkUrl = update.optString("apkUrl", "").trim();
                if (!apkUrl.startsWith("https://")) return;
                String versionName = update.optString("versionName", "").trim();
                String message = update.optString("message", "يتوفر إصدار جديد من تطبيق رفيق المسلم.").trim();
                runOnUiThread(() -> showUpdateDialog(remoteCode, versionName, message, apkUrl));
            } catch (Exception ignored) {
                // Update checks are optional; the app continues normally if the network is unavailable.
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void showUpdateDialog(int code, String versionName, String message, String apkUrl) {
        String title = "يتوفر تحديث جديد" + (versionName.isEmpty() ? "" : " — إصدار " + versionName);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message + "\n\nهل تريد تنزيل التحديث الآن؟")
            .setPositiveButton("تحديث الآن", (d, w) -> downloadApk(apkUrl, code))
            .setNegativeButton("لاحقًا", null)
            .setCancelable(true)
            .show();
    }

    private void downloadApk(String apkUrl, int remoteCode) {
        if (apkUrl.contains("ضع-رابط-APK-هنا")) {
            new AlertDialog.Builder(this)
                .setTitle("رابط التحديث غير جاهز")
                .setMessage("ملف update.json موجود، لكن رابط الـAPK الحقيقي لم تتم إضافته بعد. ارفع نسخة APK جديدة ثم ضع رابط تحميلها في apkUrl.")
                .setPositiveButton("حسنًا", null)
                .show();
            return;
        }

        downloadDialog = new ProgressDialog(this);
        downloadDialog.setTitle("جاري تنزيل التحديث");
        downloadDialog.setMessage("يرجى الانتظار…");
        downloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        downloadDialog.setIndeterminate(true);
        downloadDialog.setCancelable(false);
        downloadDialog.show();

        updateExecutor.execute(() -> {
            HttpURLConnection c = null;
            File target = null;
            try {
                URL url = new URL(apkUrl);
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestMethod("GET");
                int response = c.getResponseCode();
                if (response < 200 || response >= 300) throw new Exception("HTTP " + response);

                int total = c.getContentLength();
                File dir = getExternalFilesDir("updates");
                if (dir == null) dir = new File(getFilesDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create update directory");
                target = new File(dir, "rafiq-muslim-update-" + remoteCode + ".apk");

                try (InputStream in = new BufferedInputStream(c.getInputStream()); FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int progress = (int) Math.min(100, done * 100L / total);
                            runOnUiThread(() -> {
                                if (downloadDialog != null) { downloadDialog.setIndeterminate(false); downloadDialog.setProgress(progress); }
                            });
                        }
                    }
                }

                File finalTarget = target;
                runOnUiThread(() -> {
                    if (downloadDialog != null && downloadDialog.isShowing()) downloadDialog.dismiss();
                    downloadedApk = finalTarget;
                    if (!canInstallPackages()) {
                        waitingForInstallPermission = true;
                        openUnknownSourcesSettings();
                    } else {
                        installApk(finalTarget);
                    }
                });
            } catch (Exception e) {
                if (target != null && target.exists()) target.delete();
                String reason = e.getMessage() == null ? "تعذر تنزيل التحديث." : e.getMessage();
                runOnUiThread(() -> {
                    if (downloadDialog != null && downloadDialog.isShowing()) downloadDialog.dismiss();
                    new AlertDialog.Builder(this)
                        .setTitle("فشل التحديث")
                        .setMessage("تعذر تنزيل التحديث.\n\n" + reason)
                        .setPositiveButton("حسنًا", null)
                        .show();
                });
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void openUnknownSourcesSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignored) {
            try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception ignored2) { }
        }
    }

    private void installApk(File apk) {
        if (apk == null || !apk.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                .setTitle("تعذر فتح المثبّت")
                .setMessage("لم يتمكن Android من فتح ملف التحديث. جرّب تنزيل التحديث مرة أخرى.")
                .setPositiveButton("حسنًا", null)
                .show();
        }
    }

    class Bridge {
        @JavascriptInterface
        public void call(final String id, final String m, final String json) { runOnUiThread(() -> handle(id, m, json)); }
    }

    void resolve(String id, JSONObject o) {
        final String js = "window.__nb(" + JSONObject.quote(id) + "," + o + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    void handle(String id, String m, String json) {
        try {
            JSONObject a = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            switch (m) {
                case "schedule":
                    AdhanScheduler.saveAdhan(this, a.optJSONArray("items") == null ? new JSONArray() : a.optJSONArray("items"));
                    AdhanScheduler.scheduleNext(this); break;
                case "cancelAll":
                    AdhanScheduler.saveAdhan(this, new JSONArray()); AdhanScheduler.scheduleNext(this); break;
                case "notesSchedule":
                    if (a.optJSONArray("items") != null) AdhanScheduler.notesSchedule(this, a.getJSONArray("items"));
                    AdhanScheduler.scheduleNext(this); break;
                case "notesCancel":
                    if (a.optJSONArray("ids") != null) AdhanScheduler.notesCancel(this, a.getJSONArray("ids"));
                    AdhanScheduler.scheduleNext(this); break;
                case "requestNotifPermission":
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pendingNotifId = id; requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, RQ_NOTIF); return;
                    }
                    break;
                case "openExactAlarmSettings":
                    if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
                    break;
                case "openBatterySettings":
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); break;
                case "stopAdhan":
                    startService(new Intent(this, AdhanService.class).setAction(AdhanService.ACT_STOP)); break;
                default: break;
            }
        } catch (Exception ignored) { }
        resolve(id, status());
    }

    JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("native", true);
            o.put("notif", NotificationManagerCompat.from(this).areNotificationsEnabled());
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            o.put("exact", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
            o.put("battery", ((PowerManager) getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()));
            o.put("armed", AdhanScheduler.prefs(this).getInt(AdhanScheduler.K_ARMED, -1) >= 0);
        } catch (Exception ignored) { }
        return o;
    }
}
