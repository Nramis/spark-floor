package org.example.sparkfloor;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.widget.*;
import ai.onnxruntime.*;
import java.nio.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    SurfaceView preview;
    SurfaceHolder holder;
    Camera camera;
    int cameraId = -1;
    TextView state;
    ImageView map;
    Button capture;
    TextToSpeech tts;
    boolean voice = false;
    volatile boolean alive = true, busy = false;
    volatile OrtSession model;
    OrtEnvironment env;
    String spoken = "Оценки пока нет";
    DistanceSupport distance;
    double fovX = Double.NaN, fovY = Double.NaN;
    ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(16, 28, 16, 16);
        TextView title = new TextView(this);
        title.setText("Ориентир Пол · версия 0.7");
        title.setTextSize(23);
        root.addView(title);
        TextView note = new TextView(this);
        note.setText(
                "Зелёный — предполагаемый пол, голубой — трапеция, розовый — ось видимой дорожки. Для длины и изгибов задайте высоту камеры и снимите неподвижным телефоном.");
        root.addView(note);
        LinearLayout pictures = new LinearLayout(this);
        preview = new SurfaceView(this);
        holder = preview.getHolder();
        holder.addCallback(this);
        pictures.addView(preview, new LinearLayout.LayoutParams(0, -1, 1));
        map = new ImageView(this);
        map.setScaleType(ImageView.ScaleType.FIT_CENTER);
        map.setContentDescription("Маска пола и трапеция на последнем снимке");
        pictures.addView(map, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(pictures, new LinearLayout.LayoutParams(-1, 0, 1));
        map.setContentDescription(
                "Маска пола, трапеция и предполагаемая ось видимой дорожки на последнем снимке");
        state = new TextView(this);
        state.setTextSize(17);
        state.setMaxLines(6);
        state.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        state.setText("Загрузка встроенной модели…");
        root.addView(state);
        capture = new Button(this);
        capture.setText("Сделать снимок и проанализировать");
        capture.setEnabled(false);
        capture.setOnClickListener(v -> take());
        root.addView(capture);
        Button demo = new Button(this);
        demo.setText("Проверить на встроенном коридоре");
        demo.setOnClickListener(
                v -> {
                    if (!busy && model != null) {
                        busy = true;
                        if (distance != null) distance.clear();
                        capture.setEnabled(false);
                        state.setText("Обработка примера…");
                        worker.execute(
                                () -> {
                                    try {
                                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                                        try (InputStream in = getAssets().open("corridor.jpg")) {
                                            byte[] block = new byte[8192];
                                            int n;
                                            while ((n = in.read(block)) != -1)
                                                out.write(block, 0, n);
                                        }
                                        analyze(out.toByteArray());
                                    } catch (Exception e) {
                                        busy = false;
                                        ui(
                                                () -> {
                                                    state.setText(
                                                            "Ошибка примера: " + e.getMessage());
                                                    capture.setEnabled(true);
                                                });
                                    }
                                });
                    }
                });
        root.addView(demo);
        distance =
                new DistanceSupport(
                        this,
                        map,
                        state,
                        text -> {
                            spoken = text;
                            say(text);
                        });
        root.addView(distance.settingsButton());
        root.addView(distance.fullscreenButton());
        Button repeat = new Button(this);
        repeat.setText("Повторить результат голосом");
        repeat.setOnClickListener(v -> say(spoken));
        root.addView(repeat);
        TextView footer = new TextView(this);
        footer.setText(
                "Эксперимент. Маска и трапеция не подтверждают безопасность прохода. Испытывайте с сопровождающим.");
        footer.setTextSize(12);
        root.addView(footer);
        setContentView(root);
        tts =
                new TextToSpeech(
                        this,
                        s -> {
                            if (s == TextToSpeech.SUCCESS)
                                voice = tts.setLanguage(new Locale("ru", "RU")) >= 0;
                        });
        worker.execute(
                () -> {
                    try {
                        File file = new File(getFilesDir(), "floor.onnx");
                        // Always copy the bundled model so application updates cannot reuse stale
                        // weights.
                        try (InputStream in = getAssets().open("floor.onnx");
                                FileOutputStream out = new FileOutputStream(file)) {
                            byte[] block = new byte[65536];
                            int n;
                            while ((n = in.read(block)) != -1) out.write(block, 0, n);
                        }
                        env = OrtEnvironment.getEnvironment();
                        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                            options.setIntraOpNumThreads(2);
                            options.setInterOpNumThreads(1);
                            model = env.createSession(file.getAbsolutePath(), options);
                        }
                        ui(
                                () -> {
                                    state.setText(
                                            "Модель пола готова. Сделайте снимок или запустите встроенный пример. Обработка на Spark 8 может быть медленной.");
                                    capture.setEnabled(true);
                                });
                    } catch (Throwable e) {
                        ui(
                                () ->
                                        state.setText(
                                                "Ошибка загрузки модели: "
                                                        + e.getClass().getSimpleName()
                                                        + " "
                                                        + e.getMessage()));
                    }
                });
    }

    void ui(Runnable r) {
        if (alive)
            runOnUiThread(
                    () -> {
                        if (alive) r.run();
                    });
    }

    void say(String s) {
        if (voice) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "result");
    }

    void startCamera() {
        if (camera != null || !holder.getSurface().isValid()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, 3);
            return;
        }
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    cameraId = i;
                    break;
                }
            }
            if (cameraId < 0) throw new IOException("Нет задней камеры");
            camera = Camera.open(cameraId);
            Camera.getCameraInfo(cameraId, info);
            int rot = getWindowManager().getDefaultDisplay().getRotation() * 90;
            int angle = (info.orientation - rot + 360) % 360;
            camera.setDisplayOrientation(angle);
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPictureSizes())
                if (s.width >= 640
                        && (best == null || s.width * s.height < best.width * best.height))
                    best = s;
            if (best != null) p.setPictureSize(best.width, best.height);
            p.setRotation(angle);
            float fh = p.getHorizontalViewAngle(), fv = p.getVerticalViewAngle();
            fovX = (angle == 90 || angle == 270) ? fv : fh;
            fovY = (angle == 90 || angle == 270) ? fh : fv;
            List<String> modes = p.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(p);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (Exception e) {
            releaseCamera();
            state.setText("Камера недоступна: " + e.getMessage());
        }
    }

    void releaseCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception e) {
            }
            camera.release();
            camera = null;
        }
    }

    void take() {
        if (busy || model == null) return;
        if (camera == null) {
            startCamera();
            return;
        }
        busy = true;
        if (distance != null) distance.clear();
        capture.setEnabled(false);
        state.setText("Снимок получен. Выполняется анализ на телефоне…");
        final DistanceSupport.Shot[] shot = {distance.capture(fovX, fovY)};
        try {
            camera.takePicture(
                    () -> shot[0] = distance.capture(fovX, fovY),
                    null,
                    (jpeg, c) -> {
                        if (!alive) return;
                        try {
                            c.startPreview();
                        } catch (Exception e) {
                        }
                        worker.execute(() -> analyze(jpeg, shot[0]));
                    });
        } catch (Exception e) {
            busy = false;
            capture.setEnabled(true);
            state.setText("Не удалось снять кадр: " + e.getMessage());
        }
    }

    void analyze(byte[] jpeg) {
        analyze(jpeg, null);
    }

    void analyze(byte[] jpeg, DistanceSupport.Shot shot) {
        Bitmap original = null, scaled = null;
        try {
            long start = SystemClock.elapsedRealtime();
            original = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (original == null) throw new IOException("Ошибка изображения");
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            int orientation =
                    exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees =
                    orientation == ExifInterface.ORIENTATION_ROTATE_90
                            ? 90
                            : orientation == ExifInterface.ORIENTATION_ROTATE_180
                                    ? 180
                                    : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (degrees != 0) {
                Matrix rotate = new Matrix();
                rotate.postRotate(degrees);
                Bitmap next =
                        Bitmap.createBitmap(
                                original,
                                0,
                                0,
                                original.getWidth(),
                                original.getHeight(),
                                rotate,
                                true);
                if (next != original) original.recycle();
                original = next;
            }
            scaled = Bitmap.createScaledBitmap(original, 512, 512, true);
            int plane = 512 * 512;
            int[] rgb = new int[plane];
            scaled.getPixels(rgb, 0, 512, 0, 0, 512, 512);
            FloatBuffer input = Segmentation.preprocess(rgb);
            int width = original.getWidth(), height = original.getHeight();
            float scale = 160f / Math.max(width, height);
            int mw = Math.max(8, Math.round(width * scale)),
                    mh = Math.max(20, Math.round(height * scale));
            boolean[] floor = new boolean[mw * mh];
            try (OnnxTensor tensor =
                            OnnxTensor.createTensor(env, input, new long[] {1, 3, 512, 512});
                    OrtSession.Result results =
                            model.run(
                                    Collections.singletonMap(
                                            model.getInputNames().iterator().next(), tensor))) {
                float[][][] logits = ((float[][][][]) results.get(0).getValue())[0];
                floor = Segmentation.floor(logits, mw, mh);
            }

            Trapezoid trap = Trapezoid.fit(floor, mw, mh);
            int[] maskColors = new int[floor.length];
            int floorCount = 0;
            for (int i = 0; i < floor.length; i++)
                if (floor[i]) {
                    maskColors[i] = Color.argb(90, 0, 230, 80);
                    floorCount++;
                }
            Bitmap smallMask = Bitmap.createBitmap(maskColors, mw, mh, Bitmap.Config.ARGB_8888);
            Bitmap rendered = original.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(rendered);
            Paint paint = new Paint();
            paint.setFilterBitmap(false);
            canvas.drawBitmap(smallMask, null, new Rect(0, 0, width, height), paint);
            smallMask.recycle();
            String message;
            if (trap != null) {
                float xs = (float) width / mw, ys = (float) height / mh;
                Path path = new Path();
                path.moveTo((float) trap.lt * xs, trap.top * ys);
                path.lineTo((float) trap.rt * xs, trap.top * ys);
                path.lineTo((float) trap.rb * xs, trap.bottom * ys);
                path.lineTo((float) trap.lb * xs, trap.bottom * ys);
                path.close();
                paint.setAntiAlias(true);
                paint.setColor(Color.CYAN);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(3, width / 150f));
                canvas.drawPath(path, paint);
                message =
                        "Пол выделен. Внутри маски найдена трапеция. Безопасность прохода не определена.";
            } else
                message =
                        "Подходящая трапеция не найдена. Зелёным показан предполагаемый пол, если модель его обнаружила.";
            FloorPath.Result pathResult = distance.analyzePath(floor, mw, mh, shot, width, height);
            Bitmap pathImage = DistanceSupport.renderPath(rendered, pathResult);
            long elapsed = SystemClock.elapsedRealtime() - start;
            String text =
                    pathResult.summary()
                            + "\n"
                            + message
                            + "\nАнализ ПОСЛЕДНЕГО снимка. Обработка: "
                            + String.format(Locale.getDefault(), "%.1f", elapsed / 1000f)
                            + " с. Доля маски пола: "
                            + Math.round(100f * floorCount / floor.length)
                            + "% кадра (не уверенность).";
            final String announcement = pathResult.summary();
            final boolean[] measuredMask = floor;
            ui(
                    () -> {
                        map.setImageBitmap(pathImage);
                        distance.attach(rendered, measuredMask, mw, mh, shot, pathResult);
                        spoken = text;
                        state.setText(text);
                        say(announcement);
                    });
        } catch (Throwable e) {
            String error =
                    "Ошибка обработки: " + e.getClass().getSimpleName() + " " + e.getMessage();
            ui(
                    () -> {
                        distance.clear();
                        map.setImageDrawable(null);
                        spoken = error;
                        state.setText(error);
                    });
        } finally {
            if (scaled != null && scaled != original) scaled.recycle();
            if (original != null) original.recycle();
            busy = false;
            ui(() -> capture.setEnabled(model != null));
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        startCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int t) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        releaseCamera();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (distance != null) distance.resume();
        if (holder != null && holder.getSurface().isValid()) startCamera();
    }

    @Override
    public void onPause() {
        if (distance != null) distance.pause();
        if (tts != null) tts.stop();
        releaseCamera();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] grants) {
        super.onRequestPermissionsResult(r, p, grants);
        if (r == 3 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else state.setText("Для работы разрешите камеру в настройках приложения.");
    }

    @Override
    public void onDestroy() {
        alive = false;
        releaseCamera();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        worker.execute(
                () -> {
                    if (model != null)
                        try {
                            model.close();
                        } catch (Exception ignored) {
                        }
                });
        worker.shutdown();
        super.onDestroy();
    }
}
