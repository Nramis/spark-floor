package org.example.sparkfloor;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.app.*;
import android.os.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.Camera;
import android.media.ExifInterface;
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
    VoiceAssistant assistant;
    boolean welcomed;
    boolean foreground;
    boolean cameraPermissionRequested, cameraPermissionPending;
    String pendingCameraNotice;
    TextView ageView;
    ResultSnapshot lastSnapshot;
    ImageQuality.Result lastQuality;
    long lastProcessingMs;
    String lastDiagnostic = "Анализ ещё не выполнялся.";
    final Handler ageHandler = new Handler(Looper.getMainLooper());
    final Runnable ageTick =
            new Runnable() {
                @Override
                public void run() {
                    updateAge();
                    if (foreground) ageHandler.postDelayed(this, 5000);
                }
            };
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
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));
        root.addView(heading("Ориентир Пол · версия 0.10"));
        TextView note = new TextView(this);
        note.setText(
                "Остановитесь, направьте телефон перед собой и немного вниз. Сделайте снимок и дождитесь ответа.");
        note.setTextSize(17);
        root.addView(note);
        ageView = new TextView(this);
        ageView.setTextSize(17);
        ageView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        ageView.setText("Обработанного снимка пока нет.");
        root.addView(ageView);
        state = new TextView(this);
        state.setTextSize(19);
        state.setText("Загрузка модели пола…");
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        assistant = new VoiceAssistant(this, this::handleVoiceCommand);
        capture = action("Сделать снимок", () -> take());
        capture.setEnabled(false);
        root.addView(capture);
        prepareButton(assistant.button());
        root.addView(assistant.button());
        root.addView(action("Повторить кратко", () -> repeatResult()));
        root.addView(action("Подробнее о снимке", () -> detailsResult()));
        root.addView(action("Остановить речь и микрофон", () -> assistant.stop()));
        root.addView(
                action("Проверить направление камеры", () -> assistant.tell(cameraReadiness())));
        root.addView(action("Помощь", () -> assistant.showHelp()));
        root.addView(heading("Результат последнего снимка"));
        root.addView(state);
        root.addView(assistant.statusView());
        root.addView(action("Отчёт о проверке", () -> showTestingReport()));
        preview = new SurfaceView(this);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        holder = preview.getHolder();
        holder.addCallback(this);
        map = new ImageView(this);
        map.setScaleType(ImageView.ScaleType.FIT_CENTER);
        map.setContentDescription(
                "Обработанный снимок. Описание доступно кнопкой «Подробнее о снимке».");
        distance = new DistanceSupport(this, map, state, text -> measurementNotice(text));
        root.addView(heading("Высота камеры"));
        Button height = distance.settingsButton();
        prepareButton(height);
        root.addView(height);
        root.addView(heading("Изображение и дополнительные проверки"));
        LinearLayout pictures = new LinearLayout(this);
        pictures.addView(preview, new LinearLayout.LayoutParams(0, -1, 1));
        pictures.addView(map, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(pictures, new LinearLayout.LayoutParams(-1, dp(220)));
        Button fullscreen = distance.fullscreenButton();
        prepareButton(fullscreen);
        root.addView(fullscreen);
        root.addView(action("Проверить на встроенном примере", () -> runDemo()));
        CheckBox trapezoidOverlay = new CheckBox(this);
        trapezoidOverlay.setText("Рисовать трапецию при следующем анализе");
        trapezoidOverlay.setMinHeight(dp(56));
        trapezoidOverlay.setTextSize(18);
        trapezoidOverlay.setChecked(getPreferences(0).getBoolean("showTrapezoid", false));
        trapezoidOverlay.setOnCheckedChangeListener(
                (button, checked) ->
                        getPreferences(0).edit().putBoolean("showTrapezoid", checked).apply());
        root.addView(trapezoidOverlay);
        TextView footer = new TextView(this);
        footer.setText(
                "Экспериментальная помощь по одному снимку. Проходимость не подтверждается. Первые проверки — с сопровождающим.");
        footer.setTextSize(16);
        root.addView(footer);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
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
                                    say(readiness());
                                });
                    } catch (Throwable e) {
                        ui(
                                () -> {
                                    spoken =
                                            "Не удалось загрузить модель пола. Анализ снимка недоступен.";
                                    lastDiagnostic =
                                            "Ошибка загрузки модели: "
                                                    + e.getClass().getSimpleName();
                                    state.setText(
                                            "Ошибка загрузки модели: "
                                                    + e.getClass().getSimpleName()
                                                    + " "
                                                    + e.getMessage());
                                    say(spoken);
                                });
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
        if (assistant != null) assistant.speak(s);
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    TextView heading(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(22);
        title.setPadding(0, dp(12), 0, dp(8));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        return title;
    }

    void prepareButton(Button button) {
        button.setMinHeight(dp(64));
        button.setMinimumHeight(dp(64));
        button.setMinWidth(dp(64));
        button.setTextSize(20);
        button.setAllCaps(false);
    }

    Button action(String label, Runnable run) {
        Button button = new Button(this);
        button.setText(label);
        prepareButton(button);
        button.setOnClickListener(v -> run.run());
        return button;
    }

    void updateAge() {
        if (ageView != null)
            ageView.setText(
                    lastSnapshot == null
                            ? "Обработанного снимка пока нет."
                            : lastSnapshot.age(SystemClock.elapsedRealtime()));
    }

    void presentSnapshot(ResultSnapshot snapshot, ImageQuality.Result quality, long processing) {
        lastSnapshot = snapshot;
        lastQuality = quality;
        lastProcessingMs = processing;
        spoken = snapshot.brief;
        String problem = assistant.speechProblem();
        state.setText(snapshot.brief + (problem == null ? "" : "\nОзвучка: " + problem));
        updateAge();
        say(snapshot.message(false, SystemClock.elapsedRealtime()));
    }

    void measurementNotice(String text) {
        spoken = text;
        if (distance != null && distance.frame == null) {
            lastSnapshot = null;
            lastQuality = null;
            lastProcessingMs = 0;
            lastDiagnostic = "Настройки изменены или измерение недоступно. Нужен новый снимок.";
        } else if (lastSnapshot != null)
            lastSnapshot =
                    new ResultSnapshot(text, text, lastSnapshot.capturedAt, lastSnapshot.demo);
        updateAge();
        say(
                lastSnapshot == null
                        ? text
                        : lastSnapshot.message(false, SystemClock.elapsedRealtime()));
    }

    void detailsResult() {
        if (busy) {
            assistant.tell("Снимок ещё обрабатывается. Дождитесь результата.");
            return;
        }
        if (lastSnapshot == null) {
            assistant.tell(spoken);
            return;
        }
        state.setText(lastSnapshot.details);
        updateAge();
        say(lastSnapshot.message(true, SystemClock.elapsedRealtime()));
    }

    void runDemo() {
        if (busy || model == null) {
            assistant.tell(readiness());
            return;
        }
        busy = true;
        distance.clear();
        lastSnapshot = null;
        lastQuality = null;
        lastProcessingMs = 0;
        lastDiagnostic = "Выполняется обработка встроенного примера.";
        spoken = "Встроенный пример обрабатывается.";
        updateAge();
        capture.setEnabled(false);
        state.setText("Обработка встроенного примера…");
        say("Обрабатываю встроенный пример. Метры на нём недоступны.");
        worker.execute(
                () -> {
                    try (InputStream in = getAssets().open("corridor.jpg");
                            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] block = new byte[8192];
                        int n;
                        while ((n = in.read(block)) != -1) out.write(block, 0, n);
                        analyze(out.toByteArray());
                    } catch (Exception e) {
                        ui(
                                () -> {
                                    busy = false;
                                    spoken = "Не удалось обработать встроенный пример.";
                                    state.setText(spoken);
                                    say(spoken);
                                    capture.setEnabled(model != null);
                                });
                    }
                });
    }

    void showTestingReport() {
        EditText text = new EditText(this);
        text.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        text.setMinLines(8);
        text.setTextSize(17);
        text.setContentDescription("Текст отчёта. Дополните, что ожидали и что произошло.");
        String result =
                lastSnapshot == null
                        ? spoken
                        : lastSnapshot.message(true, SystemClock.elapsedRealtime());
        text.setText(
                "Ориентир Пол 0.10 — проверка\nТелефон: "
                        + Build.MANUFACTURER
                        + " "
                        + Build.MODEL
                        + "\nAndroid: "
                        + Build.VERSION.RELEASE
                        + " (API "
                        + Build.VERSION.SDK_INT
                        + ")"
                        + "\nОзвучка: "
                        + (assistant.usesScreenReader() ? "служба доступности" : "TTS приложения")
                        + "; масштаб шрифта: "
                        + getResources().getConfiguration().fontScale
                        + "\nЧто сделал(а): \nОжидалось: \nПолучилось: \n\nПоследний ответ:\n"
                        + result
                        + "\n\nОбработка: "
                        + lastProcessingMs
                        + " мс\n"
                        + lastDiagnostic
                        + (lastQuality == null
                                ? ""
                                : "\nЯркость: "
                                        + Math.round(lastQuality.mean)
                                        + "; контраст: "
                                        + Math.round(lastQuality.contrast)
                                        + "; оценка резкости: "
                                        + Math.round(lastQuality.laplacianVariance))
                        + "\n\nОтчёт содержит текст и параметры устройства. Фото и аудио не приложены.");
        ScrollView body = new ScrollView(this);
        body.addView(text);
        new AlertDialog.Builder(this)
                .setTitle("Отчёт о проверке")
                .setMessage(
                        "Проверьте и дополните текст. Отправка произойдёт только через выбранное вами приложение.")
                .setView(body)
                .setPositiveButton(
                        "Поделиться",
                        (d, w) -> {
                            Intent share = new Intent(Intent.ACTION_SEND);
                            share.setType("text/plain");
                            share.putExtra(Intent.EXTRA_SUBJECT, "Ориентир Пол 0.10 — проверка");
                            share.putExtra(Intent.EXTRA_TEXT, text.getText().toString());
                            try {
                                startActivity(Intent.createChooser(share, "Отправить отчёт"));
                            } catch (android.content.ActivityNotFoundException e) {
                                assistant.tell(
                                        "Нет приложения для отправки. Откройте отчёт и выберите «Скопировать».");
                            }
                        })
                .setNeutralButton(
                        "Скопировать",
                        (d, w) -> {
                            ClipboardManager clipboard =
                                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                            "Отчёт Ориентир Пол", text.getText().toString()));
                            assistant.tell("Текст отчёта скопирован.");
                        })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    String readiness() {
        if (busy) return "Выполняется анализ снимка. Дождитесь результата.";
        if (model == null) return "Модель пола ещё готовится. " + cameraReadiness();
        return cameraReadiness();
    }

    String cameraReadiness() {
        if (camera == null)
            return "Камера недоступна. Проверьте разрешение камеры и вернитесь в приложение.";
        return distance.readiness(fovX, fovY);
    }

    void repeatResult() {
        if (busy) assistant.tell("Новый снимок ещё обрабатывается. Дождитесь результата.");
        else if (lastSnapshot != null) {
            state.setText(lastSnapshot.brief);
            updateAge();
            say(lastSnapshot.message(false, SystemClock.elapsedRealtime()));
        } else say(spoken);
    }

    void handleVoiceCommand(VoiceCommands.Command command) {
        switch (command) {
            case CAPTURE:
                take();
                break;
            case REPEAT:
                repeatResult();
                break;
            case DETAILS:
                detailsResult();
                break;
            case REPORT:
                showTestingReport();
                break;
            case HELP:
                assistant.showHelp();
                break;
            case STATUS:
                assistant.tell(readiness());
                break;
            case HEIGHT:
                if (busy)
                    assistant.tell("Дождитесь окончания анализа, затем задайте высоту камеры.");
                else {
                    distance.settings.performClick();
                    say("Введите высоту объектива над полом в сантиметрах и нажмите сохранить.");
                }
                break;
            case STOP:
                assistant.stop();
                break;
            default:
                assistant.tell(VoiceCommands.help());
        }
    }

    void startCamera() {
        if (camera != null || !holder.getSurface().isValid()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!cameraPermissionRequested && !cameraPermissionPending) {
                cameraPermissionRequested = true;
                cameraPermissionPending = true;
                requestPermissions(new String[] {Manifest.permission.CAMERA}, 3);
            }
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
            spoken = "Камера недоступна. Проверьте разрешение камеры.";
            lastDiagnostic = "Ошибка камеры: " + e.getClass().getSimpleName();
            state.setText("Камера недоступна: " + e.getMessage());
            say("Камера недоступна. Проверьте разрешение камеры.");
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
        if (busy || model == null) {
            assistant.tell(readiness());
            return;
        }
        if (camera == null) {
            // A fresh permission prompt is initiated only by the user's capture action.
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) cameraPermissionRequested = false;
            startCamera();
            if (camera == null) {
                assistant.tell(readiness());
                return;
            }
        }
        busy = true;
        if (distance != null) distance.clear();
        lastSnapshot = null;
        lastQuality = null;
        lastProcessingMs = 0;
        lastDiagnostic = "Выполняется обработка нового снимка.";
        spoken = "Новый снимок ещё обрабатывается.";
        map.setImageDrawable(null);
        updateAge();
        capture.setEnabled(false);
        state.setText("Снимаю кадр…");
        say("Снимаю. Дождитесь результата анализа.");
        final DistanceSupport.Shot[] shot = {distance.capture(fovX, fovY)};
        try {
            camera.takePicture(
                    () -> shot[0] = distance.capture(fovX, fovY),
                    null,
                    (jpeg, c) -> {
                        if (!alive) return;
                        state.setText("Снимок получен. Выполняется анализ на телефоне…");
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
            spoken = "Снимок не получен. Попробуйте ещё раз.";
            lastDiagnostic = "Ошибка съёмки: " + e.getClass().getSimpleName();
            say("Не удалось снять кадр. Попробуйте ещё раз.");
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
            final long capturedAt = shot == null ? -1 : shot.capturedAt;
            final boolean demo = shot == null;
            Bitmap thumbnail = Bitmap.createScaledBitmap(original, 128, 128, true);
            int[] qualityPixels = new int[128 * 128];
            thumbnail.getPixels(qualityPixels, 0, 128, 0, 0, 128, 128);
            final ImageQuality.Result quality = ImageQuality.evaluate(qualityPixels, 128, 128);
            if (thumbnail != original) thumbnail.recycle();
            final String diagnostic =
                    shot == null
                            ? "Источник: встроенный пример."
                            : "Высота камеры: "
                                    + shot.h
                                    + " м; FOV: "
                                    + shot.fxAngle
                                    + " / "
                                    + shot.fyAngle
                                    + "; поза: "
                                    + (shot.up == null
                                            ? "неустойчива или неизвестна"
                                            : Arrays.toString(shot.up));
            if (quality.unusable) {
                final Bitmap shown = original.copy(Bitmap.Config.ARGB_8888, false);
                final long processing = SystemClock.elapsedRealtime() - start;
                final String brief = AssistiveFeedback.brief(null, null, quality, null);
                ui(
                        () -> {
                            map.setImageBitmap(shown);
                            distance.clear();
                            lastDiagnostic = diagnostic;
                            presentSnapshot(
                                    new ResultSnapshot(
                                            brief,
                                            quality.details() + " Оценка сцены не выполнена.",
                                            capturedAt,
                                            demo),
                                    quality,
                                    processing);
                        });
                return;
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
            int[] labels;
            try (OnnxTensor tensor =
                            OnnxTensor.createTensor(env, input, new long[] {1, 3, 512, 512});
                    OrtSession.Result results =
                            model.run(
                                    Collections.singletonMap(
                                            model.getInputNames().iterator().next(), tensor))) {
                float[][][] logits = ((float[][][][]) results.get(0).getValue())[0];
                labels = Segmentation.labels(logits, mw, mh);
                floor = Segmentation.floorMask(labels);
            }

            Trapezoid trap =
                    getPreferences(0).getBoolean("showTrapezoid", false)
                            ? Trapezoid.fit(floor, mw, mh)
                            : null;
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
            }
            FloorPath.Result pathResult = distance.analyzePath(floor, mw, mh, shot, width, height);
            SceneDescription.Result description =
                    distance.describeScene(labels, mw, mh, shot, width, height, pathResult);
            Bitmap pathImage = DistanceSupport.renderPath(rendered, pathResult);
            long elapsed = SystemClock.elapsedRealtime() - start;
            String text =
                    quality.details()
                            + " "
                            + description.summary
                            + "\nАнализ ПОСЛЕДНЕГО снимка. Обработка: "
                            + String.format(Locale.getDefault(), "%.1f", elapsed / 1000f)
                            + " с. Доля маски пола: "
                            + Math.round(100f * floorCount / floor.length)
                            + "% кадра (не уверенность).";
            final String brief =
                    AssistiveFeedback.brief(
                            description, pathResult, quality, distance.metricIssue(shot));
            final boolean[] measuredMask = floor;
            ui(
                    () -> {
                        map.setImageBitmap(pathImage);
                        distance.attach(rendered, measuredMask, labels, mw, mh, shot, pathResult);
                        lastDiagnostic = diagnostic;
                        presentSnapshot(
                                new ResultSnapshot(brief, text, capturedAt, demo),
                                quality,
                                elapsed);
                    });
        } catch (Throwable e) {
            String error =
                    "Ошибка обработки: " + e.getClass().getSimpleName() + " " + e.getMessage();
            ui(
                    () -> {
                        distance.clear();
                        lastSnapshot = null;
                        updateAge();
                        map.setImageDrawable(null);
                        spoken = error;
                        lastDiagnostic = "Ошибка анализа: " + e.getClass().getSimpleName();
                        state.setText(error);
                        say("Не удалось обработать снимок. Попробуйте снять ещё раз.");
                    });
        } finally {
            if (scaled != null && scaled != original) scaled.recycle();
            if (original != null) original.recycle();
            ui(
                    () -> {
                        busy = false;
                        capture.setEnabled(model != null);
                    });
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
        foreground = true;
        if (assistant != null) assistant.resume();
        if (distance != null) distance.resume();
        if (holder != null && holder.getSurface().isValid()) startCamera();
        ageHandler.removeCallbacks(ageTick);
        ageTick.run();
        if (pendingCameraNotice != null) {
            String notice = pendingCameraNotice;
            pendingCameraNotice = null;
            say(notice);
        } else if (!welcomed && assistant != null) {
            welcomed = true;
            say(
                    "Помощник готовит описание одного снимка. Остановитесь перед съёмкой. Основные действия доступны крупными кнопками. Список команд — кнопкой «Помощь».");
        }
    }

    @Override
    public void onPause() {
        foreground = false;
        ageHandler.removeCallbacks(ageTick);
        if (distance != null) distance.pause();
        if (assistant != null) assistant.pause();
        releaseCamera();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int r, String[] p, int[] grants) {
        super.onRequestPermissionsResult(r, p, grants);
        if (r == VoiceAssistant.MICROPHONE_PERMISSION) {
            assistant.onPermissionResult(
                    grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED);
        } else if (r == 3) {
            cameraPermissionPending = false;
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
                pendingCameraNotice = "Камера разрешена. Для съёмки нажмите «Сделать снимок».";
            } else {
                state.setText("Для работы разрешите камеру в настройках приложения.");
                pendingCameraNotice =
                        "Для съёмки разрешите камеру в настройках приложения. Встроенный пример и помощь доступны без камеры.";
            }
            if (foreground && pendingCameraNotice != null) {
                String notice = pendingCameraNotice;
                pendingCameraNotice = null;
                say(notice);
            }
        }
    }

    @Override
    public void onDestroy() {
        alive = false;
        ageHandler.removeCallbacksAndMessages(null);
        releaseCamera();
        if (assistant != null) assistant.destroy();
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
