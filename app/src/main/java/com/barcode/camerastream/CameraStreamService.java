package com.barcode.camerastream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.*;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.*;
import android.util.Log;
import android.util.Size;
import androidx.core.app.NotificationCompat;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class CameraStreamService extends Service {

    private static final String TAG         = "CameraStream";
    private static final String SOCKET_NAME = "camerastream";
    private static final String CHANNEL_ID  = "CameraStreamChannel";

    private static final int TARGET_WIDTH  = 640;
    private static final int TARGET_HEIGHT = 480;
    private static final int JPEG_QUALITY  = 80;

    // ── Camera2 ──────────────────────────────────────────
    private CameraManager          mCameraManager;
    private CameraDevice           mCameraDevice;
    private CameraCaptureSession   mCaptureSession;
    private CaptureRequest.Builder mPreviewBuilder;
    private ImageReader            mImageReader;
    private String                 mCameraId;
    private Rect                   mSensorArraySize;

    // İki ayrı handler — kesinlikle birbirini bloklamasın
    // imageHandler  : sadece YUV→JPEG işlemi
    // zoomHandler   : sadece setRepeatingRequest (zoom uygula)
    private HandlerThread mImageThread;
    private Handler       mImageHandler;
    private HandlerThread mZoomThread;
    private Handler       mZoomHandler;

    // ── Zoom state ────────────────────────────────────────
    private volatile float mCurrentZoom = 1.0f;
    private volatile float mZoomCenterX = 0.5f;
    private volatile float mZoomCenterY = 0.5f;

    // ── Frame paylaşımı ───────────────────────────────────
    // mLatestJpeg ASLA null'a çekilmez → stream hiç beklemez
    private volatile byte[] mLatestJpeg  = null;
    private volatile long   mFrameSerial = 0;   // her yeni frame'de artar
    private final Object    mFrameLock   = new Object();

    // ── Stream ────────────────────────────────────────────
    private volatile boolean mRunning = false;

    //
    // LIFECYCLE
    //

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, buildNotification());
        startHandlerThreads();
        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        openCamera();
        startSocketServer();
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        mRunning = false;
        closeCamera();
        stopHandlerThreads();
        super.onDestroy();
    }

    //
    // HANDLER THREADS
    //

    private void startHandlerThreads() {
        mImageThread = new HandlerThread("ImageThread");
        mImageThread.start();
        mImageHandler = new Handler(mImageThread.getLooper());

        mZoomThread = new HandlerThread("ZoomThread");
        mZoomThread.start();
        mZoomHandler = new Handler(mZoomThread.getLooper());
    }

    private void stopHandlerThreads() {
        if (mImageThread != null) { mImageThread.quitSafely(); try { mImageThread.join(1000); } catch (Exception ignored) {} }
        if (mZoomThread  != null) { mZoomThread.quitSafely();  try { mZoomThread.join(1000);  } catch (Exception ignored) {} }
    }

    //
    // KAMERA AÇ / KAPAT
    //

    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        try {
            for (String id : mCameraManager.getCameraIdList()) {
                CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    mCameraId        = id;
                    mSensorArraySize = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    Log.d(TAG, "Sensor: " + mSensorArraySize);
                    break;
                }
            }
            if (mCameraId == null) { Log.e(TAG, "Kamera bulunamadi"); return; }

            StreamConfigurationMap map = mCameraManager
                    .getCameraCharacteristics(mCameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = chooseBestSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            Log.d(TAG, "Cozunurluk: " + best.getWidth() + "x" + best.getHeight());

            // ImageReader → imageHandler üzerinde callback alır
            mImageReader = ImageReader.newInstance(best.getWidth(), best.getHeight(),
                    ImageFormat.YUV_420_888, 3);
            mImageReader.setOnImageAvailableListener(mOnImageAvailable, mImageHandler);

            mCameraManager.openCamera(mCameraId, mStateCallback, mImageHandler);

        } catch (Exception e) { Log.e(TAG, "openCamera: " + e.getMessage()); }
    }

    private Size chooseBestSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() <= TARGET_WIDTH && s.getHeight() <= TARGET_HEIGHT) {
                if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight())
                    best = s;
            }
        }
        return best != null ? best : sizes[sizes.length - 1];
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice cam) {
            mCameraDevice = cam;
            createCaptureSession();
        }
        @Override public void onDisconnected(CameraDevice cam) { cam.close(); mCameraDevice = null; }
        @Override public void onError(CameraDevice cam, int err) {
            cam.close(); mCameraDevice = null;
            Log.e(TAG, "Kamera hatasi: " + err);
        }
    };

    private void createCaptureSession() {
        try {
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewBuilder.addTarget(mImageReader.getSurface());
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            mCameraDevice.createCaptureSession(
                    Arrays.asList(mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            mCaptureSession = s;
                            Log.d(TAG, "Session hazir");
                            // İlk repeating request — zoomHandler üzerinden
                            mZoomHandler.post(() -> applyZoom());
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            Log.e(TAG, "Session configure failed");
                        }
                    },
                    mZoomHandler);   // session callback da zoomHandler'da — tutarlı
        } catch (Exception e) { Log.e(TAG, "createCaptureSession: " + e.getMessage()); }
    }

    private void closeCamera() {
        try {
            if (mCaptureSession != null) { mCaptureSession.close(); mCaptureSession = null; }
            if (mCameraDevice   != null) { mCameraDevice.close();   mCameraDevice   = null; }
            if (mImageReader    != null) { mImageReader.close();    mImageReader    = null; }
        } catch (Exception e) { Log.e(TAG, "closeCamera: " + e.getMessage()); }
    }

    //
    // IMAGE CALLBACK — imageHandler thread'inde çalışır
    // zoomHandler'a HİÇ dokunmaz
    //

    private final ImageReader.OnImageAvailableListener mOnImageAvailable = reader -> {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            byte[] jpeg = yuvToJpegRotated(image);
            if (jpeg == null) return;

            synchronized (mFrameLock) {
                mLatestJpeg = jpeg;
                mFrameSerial++;
                mFrameLock.notifyAll();
            }
        } catch (Exception e) {
            Log.e(TAG, "Image callback: " + e.getMessage());
        } finally {
            if (image != null) image.close();
        }
    };

    private byte[] yuvToJpegRotated(Image image) {
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            Image.Plane[] planes = image.getPlanes();

            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            int ySize = yBuf.remaining();

            byte[] nv21 = new byte[ySize + w * h / 2];
            yBuf.get(nv21, 0, ySize);
            vBuf.get(nv21, ySize, Math.min(vBuf.remaining(), w * h / 2));

            android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                    nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream b1 = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), JPEG_QUALITY, b1);

            byte[] raw = b1.toByteArray();
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            Matrix m = new Matrix(); m.postRotate(90);
            Bitmap rot = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, false);
            bmp.recycle();

            ByteArrayOutputStream b2 = new ByteArrayOutputStream();
            rot.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, b2);
            rot.recycle();
            return b2.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "yuvToJpeg: " + e.getMessage());
            return null;
        }
    }

    //
    // ZOOM — zoomHandler thread'inde çalışır
    // imageHandler'a HİÇ dokunmaz
    //

    private void applyZoom() {
        if (mCaptureSession == null || mPreviewBuilder == null || mSensorArraySize == null) return;
        try {
            int sW = mSensorArraySize.width();
            int sH = mSensorArraySize.height();

            if (mCurrentZoom <= 1.05f) {
                mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(0, 0, sW, sH));
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null);
                mPreviewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, null);
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                mCaptureSession.setRepeatingRequest(mPreviewBuilder.build(), null, null);
                Log.d(TAG, "Zoom: 1.0x");
            } else {
                float sCx = mZoomCenterY * sW;
                float sCy = (1.0f - mZoomCenterX) * sH;

                int cropW = (int)(sW / mCurrentZoom);
                int cropH = (int)(sH / mCurrentZoom);

                // Minimum crop boyutu — çok küçük crop kamerayı kilitliyor
                // 4640/8=580 güvenli alt sınır olarak 600px seçildi
                final int MIN_CROP = 600;
                if (cropW < MIN_CROP) cropW = MIN_CROP;
                if (cropH < MIN_CROP) cropH = MIN_CROP;

                int left = Math.max(0, Math.min(sW - cropW, (int)(sCx - cropW / 2f)));
                int top  = Math.max(0, Math.min(sH - cropH, (int)(sCy - cropH / 2f)));
                Rect crop = new Rect(left, top, left + cropW, top + cropH);

                int mx = left + cropW / 2;
                int my = top  + cropH / 2;
                int mr = Math.max(10, Math.min(100, Math.min(cropW, cropH) / 6));
                MeteringRectangle meter = new MeteringRectangle(
                        new Rect(Math.max(0, mx-mr), Math.max(0, my-mr),
                                Math.min(sW, mx+mr), Math.min(sH, my+mr)),
                        MeteringRectangle.METERING_WEIGHT_MAX);

                mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, crop);
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{meter});
                mPreviewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{meter});
                mCaptureSession.setRepeatingRequest(mPreviewBuilder.build(), null, null);
                Log.d(TAG, "Zoom: " + mCurrentZoom + "x crop=" + crop);
            }

            // Zoom sonrası frame gelip gelmediğini kontrol et
            long serialBefore;
            synchronized (mFrameLock) { serialBefore = mFrameSerial; }
            synchronized (mFrameLock) {
                long t0 = System.currentTimeMillis();
                while (mFrameSerial == serialBefore) {
                    mFrameLock.wait(100);
                    if (System.currentTimeMillis() - t0 > 2000) {
                        Log.w(TAG, "Zoom sonrasi frame gelmedi! Session yenileniyor...");
                        mZoomHandler.post(() -> recoverCamera());
                        return;
                    }
                }
            }
            Log.d(TAG, "Zoom sonrasi frame onaylandi");

        } catch (Exception e) { Log.e(TAG, "applyZoom: " + e.getMessage()); }
    }

    private void recoverCamera() {
        Log.w(TAG, "Camera recovery basliyor — tam yeniden acilis...");
        try {
            // Önce her şeyi kapat
            if (mCaptureSession != null) {
                try { mCaptureSession.close(); } catch (Exception ignored) {}
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                try { mCameraDevice.close(); } catch (Exception ignored) {}
                mCameraDevice = null;
            }
            if (mImageReader != null) {
                try { mImageReader.close(); } catch (Exception ignored) {}
                mImageReader = null;
            }
            Thread.sleep(1000);  // pipeline tamamen boşalsın
            // Yeniden aç
            openCamera();
            Log.w(TAG, "Camera recovery: openCamera cagirildi");
        } catch (Exception e) {
            Log.e(TAG, "recoverCamera: " + e.getMessage());
        }
    }

    //
    // SOCKET SERVER
    //

    // Aktif client — yeni bağlantı gelince eskisi kapatılır
    private volatile LocalSocket mActiveClient = null;

    private void startSocketServer() {
        mRunning = true;
        new Thread(() -> {
            LocalServerSocket server = null;
            try {
                server = new LocalServerSocket(SOCKET_NAME);
                Log.d(TAG, "Server baslatildi: " + SOCKET_NAME);
                while (mRunning) {
                    LocalSocket client = server.accept();
                    Log.d(TAG, "Client baglandi");
                    // Eski bağlantıyı kapat — zombie stream önle
                    LocalSocket old = mActiveClient;
                    if (old != null) {
                        try { old.close(); } catch (Exception ignored) {}
                    }
                    mActiveClient = client;
                    handleClient(client);
                }
            } catch (Exception e) {
                if (mRunning) Log.e(TAG, "Server hatasi: " + e.getMessage());
            } finally {
                if (server != null) try { server.close(); } catch (Exception ignored) {}
            }
        }, "SocketServer").start();
    }

    private void handleClient(LocalSocket client) {
        new Thread(() -> {
            try {
                BufferedReader   in  = new BufferedReader(new InputStreamReader(client.getInputStream()));
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("PING")) {
                        out.writeBytes("PONG\n"); out.flush();
                    } else if (line.equals("STREAM_START")) {
                        streamFrames(out, in);
                        return;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Client ayrildi: " + e.getMessage());
            } finally {
                try { client.close(); } catch (Exception ignored) {}
            }
        }, "ClientHandler").start();
    }

    private void streamFrames(DataOutputStream out, BufferedReader cmdIn) {
        Log.d(TAG, "Stream basladi");

        // ZOOM komutlarını oku
        // Zoom uygulandıktan sonra "ZOOMED\n" gönderir → Python senkronize bekler
        new Thread(() -> {
            try {
                String cmd;
                while ((cmd = cmdIn.readLine()) != null) {
                    final String c = cmd.trim();
                    if (c.startsWith("ZOOM")) {
                        String[] p = c.split(" ");
                        if (p.length == 4) {
                            mCurrentZoom = Float.parseFloat(p[1]);
                            mZoomCenterX = Float.parseFloat(p[2]);
                            mZoomCenterY = Float.parseFloat(p[3]);
                            // Hard limit — bu telefonun güvenli max zoom'u
                            if (mCurrentZoom > 7.5f) {
                                Log.w(TAG, "Zoom " + mCurrentZoom + "x reddedildi (max 7.5x)");
                                mCurrentZoom = 7.5f;
                            }
                            Log.d(TAG, "ZOOM komutu alindi: " + mCurrentZoom + "x");
                            mZoomHandler.post(() -> {
                                Log.d(TAG, "applyZoom basliyor: " + mCurrentZoom + "x");
                                applyZoom();
                                Log.d(TAG, "applyZoom bitti: " + mCurrentZoom + "x");
                            });
                        }
                    }
                }
                Log.d(TAG, "ZoomReader bitti (cmdIn kapandi)");
            } catch (Exception e) {
                Log.d(TAG, "ZoomReader exception: " + e.getMessage());
            }
        }, "ZoomReader").start();

        // Frame gönder
        long lastSent = -1;
        long frameCount = 0;
        while (mRunning) {
            try {
                byte[] jpeg;
                synchronized (mFrameLock) {
                    while (mFrameSerial == lastSent && mRunning) {
                        mFrameLock.wait(200);
                    }
                    if (mLatestJpeg == null) continue;
                    jpeg     = mLatestJpeg;
                    lastSent = mFrameSerial;
                }
                out.writeInt(jpeg.length);
                out.write(jpeg);
                out.flush();
                frameCount++;
                if (frameCount % 30 == 0) Log.d(TAG, "Frame gonderildi: " + frameCount);
            } catch (Exception e) {
                Log.d(TAG, "Stream bitti: " + e.getMessage());
                return;
            }
        }
    }

    //
    // YARDIMCI
    //

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Camera Stream", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CameraStream")
                .setContentText("Aktif")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }
}