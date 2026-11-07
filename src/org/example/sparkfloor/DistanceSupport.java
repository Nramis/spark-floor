package org.example.sparkfloor;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

public final class DistanceSupport implements SensorEventListener {
    public static final class Shot {
        final double h, fxAngle, fyAngle;
        final double[] up;
        final long capturedAt;

        Shot(double a, double x, double y, double[] u) {
            h = a;
            fxAngle = x;
            fyAngle = y;
            up = u;
            capturedAt = SystemClock.elapsedRealtime();
        }
    }

    static final class Frame {
        final Bitmap bitmap;
        final boolean[] floor;
        final int[] labels;
        final int mw, mh;
        final Shot shot;
        FloorPath.Result path;

        Frame(Bitmap b, boolean[] f, int[] labels, int w, int h, Shot s, FloorPath.Result p) {
            bitmap = b;
            floor = f;
            this.labels = labels;
            mw = w;
            mh = h;
            shot = s;
            path = p;
        }
    }

    final Activity activity;
    final ImageView target;
    final TextView status;
    final Consumer<String> speak;
    final android.content.SharedPreferences prefs;
    final SensorManager sensors;
    final Sensor sensor;
    final boolean filtered;
    final double[] up = new double[3];
    long last = 0, stableSince = 0;
    int samples = 0;
    Frame frame;
    Button settings;

    public DistanceSupport(Activity a, ImageView image, TextView state, Consumer<String> voice) {
        activity = a;
        target = image;
        status = state;
        speak = voice;
        prefs = a.getPreferences(0);
        sensors = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        filtered = s == null;
        if (s == null) s = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensor = s;
        installTouch(target);
    }

    public void resume() {
        last = 0;
        samples = 0;
        stableSince = SystemClock.elapsedRealtime();
        if (sensor != null) sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    public void pause() {
        sensors.unregisterListener(this);
        last = 0;
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = SystemClock.elapsedRealtime();
        double norm = 0;
        for (int i = 0; i < 3; i++) norm += event.values[i] * event.values[i];
        norm = Math.sqrt(norm);
        if (norm < 7.5 || norm > 12) {
            stableSince = now;
            samples = 0;
            return;
        }
        double diff = 0;
        for (int i = 0; i < 3; i++) {
            double next = event.values[i] / norm;
            double old = up[i];
            up[i] = (filtered && samples > 0) ? old * .8 + next * .2 : next;
            diff += (up[i] - old) * (up[i] - old);
        }
        if (Math.sqrt(diff) > .025) stableSince = now;
        samples++;
        last = now;
    }

    public Shot capture(double fovX, double fovY) {
        long now = SystemClock.elapsedRealtime();
        double[] vector =
                (samples >= 20 && now - last < 500 && now - stableSince >= 400) ? up.clone() : null;
        return new Shot(prefs.getFloat("height", 0), fovX, fovY, vector);
    }

    private void announce(String text) {
        status.setText(text);
        speak.accept(text);
    }

    public String readiness(double fovX, double fovY) {
        Shot shot = capture(fovX, fovY);
        if (sensor == null)
            return "Датчик наклона недоступен. Можно описать снимок, но метры не будут определены.";
        if (CameraGuidance.state(shot.up) != CameraGuidance.State.READY)
            return CameraGuidance.hint(shot.up);
        if (shot.h <= 0) return "Задайте высоту объектива над полом. Команда: высота.";
        if (shot.up == null)
            return "Держите телефон неподвижно несколько секунд: наклон ещё не готов.";
        if (!Double.isFinite(fovX)
                || !Double.isFinite(fovY)
                || fovX < 15
                || fovX > 150
                || fovY < 15
                || fovY > 150)
            return "Камера не сообщила пригодные углы обзора. Можно выделить пол, но метры недоступны.";
        return "Готов к съёмке. Скажите снимок после нажатия кнопки голосовой команды. Оценка требует ровного пола.";
    }

    public Button settingsButton() {
        settings = new Button(activity);
        refreshSettings();
        settings.setOnClickListener(
                v -> {
                    EditText input = new EditText(activity);
                    input.setInputType(
                            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    float height = prefs.getFloat("height", 0);
                    if (height > 0) input.setText(String.valueOf(Math.round(height * 100)));
                    input.setHint("Высота объектива над полом, см");
                    input.setContentDescription(
                            "Высота объектива над полом, в сантиметрах. От 40 до 250.");
                    input.setMinHeight(
                            Math.round(56 * activity.getResources().getDisplayMetrics().density));
                    input.setSelectAllOnFocus(true);
                    new AlertDialog.Builder(activity)
                            .setTitle("Высота камеры")
                            .setMessage(
                                    "Измерьте расстояние от объектива до ровного пола. Это не рост человека. После изменения сделайте новый снимок.")
                            .setView(input)
                            .setPositiveButton(
                                    "Сохранить",
                                    (d, w) -> {
                                        try {
                                            double cm =
                                                    Double.parseDouble(
                                                            input.getText()
                                                                    .toString()
                                                                    .replace(',', '.'));
                                            if (!Double.isFinite(cm) || cm < 40 || cm > 250)
                                                throw new Exception();
                                            prefs.edit()
                                                    .putFloat("height", (float) (cm / 100))
                                                    .apply();
                                            frame = null;
                                            refreshSettings();
                                            announce(
                                                    "Высота сохранена. Держите телефон неподвижно несколько секунд и сделайте новый снимок.");
                                        } catch (Exception e) {
                                            announce("Введите высоту от 40 до 250 см.");
                                        }
                                    })
                            .setNeutralButton("Сброс калибровки", (d, w) -> resetCalibration())
                            .setNegativeButton("Отмена", null)
                            .show();
                });
        return settings;
    }

    void refreshSettings() {
        float h = prefs.getFloat("height", 0);
        settings.setText(
                h > 0 ? "Высота камеры: " + Math.round(h * 100) + " см" : "Задать высоту камеры");
    }

    public Button fullscreenButton() {
        Button b = new Button(activity);
        b.setText("Открыть снимок для измерений");
        b.setOnClickListener(
                v -> {
                    if (frame == null) {
                        announce("Сначала выполните анализ снимка.");
                        return;
                    }
                    ImageView full = new ImageView(activity);
                    full.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    full.setImageDrawable(target.getDrawable());
                    installTouch(full);
                    AlertDialog dialog =
                            new AlertDialog.Builder(activity)
                                    .setTitle("Нажмите на пол. Удержание — калибровка")
                                    .setView(full)
                                    .setPositiveButton("Закрыть", null)
                                    .create();
                    dialog.show();
                    if (dialog.getWindow() != null)
                        dialog.getWindow()
                                .setLayout(
                                        -1,
                                        (int)
                                                (activity.getResources()
                                                                .getDisplayMetrics()
                                                                .heightPixels
                                                        * .92));
                });
        return b;
    }

    public void clear() {
        frame = null;
    }

    void resetCalibration() {
        prefs.edit().remove("focalScale").apply();
        String text = "Калибровка сброшена. Используются приблизительные углы обзора камеры.";
        Frame f = frame;
        if (f != null) {
            f.path =
                    analyzePath(
                            f.floor, f.mw, f.mh, f.shot, f.bitmap.getWidth(), f.bitmap.getHeight());
            target.setImageBitmap(renderPath(f.bitmap, f.path));
            text += "\n" + frameDescription(f);
        }
        announce(text);
    }

    public void attach(
            Bitmap image,
            boolean[] mask,
            int[] labels,
            int w,
            int h,
            Shot shot,
            FloorPath.Result path) {
        frame = new Frame(image, mask, labels, w, h, shot, path);
    }

    public FloorPath.Result analyzePath(
            boolean[] mask, int mw, int mh, Shot shot, int width, int height) {
        return FloorPath.analyze(mask, mw, mh, projection(shot, width, height));
    }

    private GroundProjection projection(Shot shot, int width, int height) {
        GroundProjection projection = null;
        if (shot != null
                && shot.up != null
                && shot.h >= .4
                && shot.h <= 2.5
                && Double.isFinite(shot.fxAngle)
                && Double.isFinite(shot.fyAngle)
                && shot.fxAngle >= 15
                && shot.fxAngle <= 150
                && shot.fyAngle >= 15
                && shot.fyAngle <= 150) {
            double fx = width / (2 * Math.tan(Math.toRadians(shot.fxAngle / 2))),
                    fy = height / (2 * Math.tan(Math.toRadians(shot.fyAngle / 2)));
            try {
                projection =
                        new GroundProjection(
                                width, height, fx, fy, shot.h, shot.up, scaleFor(shot));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return projection;
    }

    public SceneDescription.Result describeScene(
            int[] labels, int mw, int mh, Shot shot, int width, int height, FloorPath.Result path) {
        String reason;
        if (shot == null)
            reason =
                    "Длину в метрах для встроенного фото определить нельзя: неизвестны высота и положение его камеры.";
        else if (shot.h < .4 || shot.h > 2.5)
            reason =
                    "Для длины в метрах задайте высоту объектива над полом и сделайте новый снимок.";
        else if (shot.up == null)
            reason =
                    sensor == null
                            ? "Для метров нужен датчик наклона; он недоступен."
                            : "Длина в метрах недоступна: при съёмке наклон не был устойчивым. Подержите телефон неподвижно и переснимите.";
        else
            reason =
                    "Длина в метрах недоступна: параметры камеры или направление съёмки не подходят для расчёта.";
        return SceneDescription.describe(
                labels, mw, mh, path, projection(shot, width, height), reason);
    }

    public String metricIssue(Shot shot) {
        if (shot == null) return "Для встроенного примера метры неизвестны.";
        if (shot.h < .4 || shot.h > 2.5) return "Для метров задайте высоту камеры и переснимите.";
        if (shot.up == null && sensor == null) return "Без датчика наклона метры недоступны.";
        if (shot.up == null) return "Для метров держите телефон неподвижно и переснимите.";
        if (!Double.isFinite(shot.fxAngle)
                || !Double.isFinite(shot.fyAngle)
                || shot.fxAngle < 15
                || shot.fxAngle > 150
                || shot.fyAngle < 15
                || shot.fyAngle > 150) return "Камера не сообщила пригодные параметры для метров.";
        return null;
    }

    private String frameDescription(Frame f) {
        return describeScene(
                        f.labels,
                        f.mw,
                        f.mh,
                        f.shot,
                        f.bitmap.getWidth(),
                        f.bitmap.getHeight(),
                        f.path)
                .summary;
    }

    public static Bitmap renderPath(Bitmap base, FloorPath.Result result) {
        Bitmap image = base.copy(Bitmap.Config.ARGB_8888, true);
        if (result.points.isEmpty()) return image;
        Canvas canvas = new Canvas(image);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path line = new Path();
        for (int i = 0; i < result.points.size(); i++) {
            FloorPath.Point point = result.points.get(i);
            if (i == 0) line.moveTo((float) point.u, (float) point.v);
            else line.lineTo((float) point.u, (float) point.v);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(5, image.getWidth() / 100f));
        p.setColor(Color.BLACK);
        canvas.drawPath(line, p);
        p.setStrokeWidth(Math.max(3, image.getWidth() / 170f));
        p.setColor(Color.MAGENTA);
        canvas.drawPath(line, p);
        p.setStyle(Paint.Style.FILL);
        FloorPath.Point first = result.points.get(0),
                last = result.points.get(result.points.size() - 1);
        canvas.drawCircle(
                (float) first.u, (float) first.v, Math.max(5, image.getWidth() / 100f), p);
        canvas.drawCircle((float) last.u, (float) last.v, Math.max(5, image.getWidth() / 100f), p);
        return image;
    }

    void installTouch(ImageView image) {
        GestureDetector detector =
                new GestureDetector(
                        activity,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent e) {
                                return true;
                            }

                            @Override
                            public boolean onSingleTapUp(MotionEvent e) {
                                select(image, e.getX(), e.getY(), false);
                                return true;
                            }

                            @Override
                            public void onLongPress(MotionEvent e) {
                                select(image, e.getX(), e.getY(), true);
                            }
                        });
        image.setOnTouchListener((v, e) -> detector.onTouchEvent(e));
    }

    double scaleFor(Shot s) {
        if (Math.abs(prefs.getFloat("fovX", 0) - s.fxAngle) > .1
                || Math.abs(prefs.getFloat("fovY", 0) - s.fyAngle) > .1) return 1;
        return prefs.getFloat("focalScale", 1);
    }

    void select(ImageView image, float tx, float ty, boolean calibration) {
        Frame f = frame;
        if (f == null) {
            announce("Нужен новый обработанный снимок.");
            return;
        }
        Shot shot = f.shot;
        if (shot == null) {
            announce("На встроенном фото метры отключены: параметры и поза его камеры неизвестны.");
            return;
        }
        if (shot.h <= 0) {
            announce("Сначала задайте высоту камеры и сделайте новый снимок.");
            return;
        }
        if (shot.up == null) {
            announce(
                    "При съёмке не было свежей устойчивой оценки наклона. Держите телефон неподвижно несколько секунд и переснимите.");
            return;
        }
        if (!Double.isFinite(shot.fxAngle)
                || !Double.isFinite(shot.fyAngle)
                || shot.fxAngle < 15
                || shot.fxAngle > 150
                || shot.fyAngle < 15
                || shot.fyAngle > 150) {
            announce("Камера не сообщила пригодные углы обзора. Метры недоступны.");
            return;
        }
        int width = f.bitmap.getWidth(), height = f.bitmap.getHeight();
        double display =
                Math.min(
                        (image.getWidth() - image.getPaddingLeft() - image.getPaddingRight())
                                / (double) width,
                        (image.getHeight() - image.getPaddingTop() - image.getPaddingBottom())
                                / (double) height);
        double
                ox =
                        image.getPaddingLeft()
                                + ((image.getWidth()
                                                        - image.getPaddingLeft()
                                                        - image.getPaddingRight())
                                                - width * display)
                                        / 2,
                oy =
                        image.getPaddingTop()
                                + ((image.getHeight()
                                                        - image.getPaddingTop()
                                                        - image.getPaddingBottom())
                                                - height * display)
                                        / 2;
        if (display <= 0) return;
        double u = (tx - ox) / display, v = (ty - oy) / display;
        if (u < 0 || v < 0 || u >= width || v >= height) return;
        int mx = Math.min(f.mw - 1, (int) (u * f.mw / width)),
                my = Math.min(f.mh - 1, (int) (v * f.mh / height));
        if (!f.floor[my * f.mw + mx]) {
            announce("Эта точка не распознана как пол. Расстояние не вычисляется.");
            return;
        }
        double fx = width / (2 * Math.tan(Math.toRadians(shot.fxAngle / 2))),
                fy = height / (2 * Math.tan(Math.toRadians(shot.fyAngle / 2)));
        if (calibration) {
            EditText known = new EditText(activity);
            known.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            known.setHint("Расстояние по рулетке, метры");
            new AlertDialog.Builder(activity)
                    .setTitle("Калибровка по точке")
                    .setMessage(
                            "Введите расстояние ПО ПОЛУ от точки прямо под камерой до выбранной точки (0,5–6 м). Не наклонную дальность от объектива. После калибровки проверьте другие точки.")
                    .setView(known)
                    .setPositiveButton(
                            "Калибровать",
                            (d, w) -> {
                                if (frame != f) return;
                                try {
                                    double distance =
                                            Double.parseDouble(
                                                    known.getText().toString().replace(',', '.'));
                                    double scale =
                                            FloorDistance.calibrate(
                                                    u, v, width, height, fx, fy, shot.h, shot.up,
                                                    distance);
                                    if (!Double.isFinite(scale)) throw new Exception();
                                    prefs.edit()
                                            .putFloat("focalScale", (float) scale)
                                            .putFloat("fovX", (float) shot.fxAngle)
                                            .putFloat("fovY", (float) shot.fyAngle)
                                            .apply();
                                    showMeasurement(image, f, u, v, fx, fy, scale, true);
                                } catch (Exception e) {
                                    announce(
                                            "Калибровка не определилась однозначно. Выберите другую точку вдали от середины кадра и проверьте высоту/рулетку.");
                                }
                            })
                    .setNegativeButton("Отмена", null)
                    .show();
        } else showMeasurement(image, f, u, v, fx, fy, scaleFor(shot), false);
    }

    void showMeasurement(
            ImageView image,
            Frame f,
            double u,
            double v,
            double fx,
            double fy,
            double scale,
            boolean calibrated) {
        double distance =
                FloorDistance.measure(
                        u,
                        v,
                        f.bitmap.getWidth(),
                        f.bitmap.getHeight(),
                        fx,
                        fy,
                        f.shot.h,
                        f.shot.up,
                        scale);
        if (!Double.isFinite(distance)) {
            announce(
                    "Луч слишком близок к горизонту, не пересекает пол впереди или оценка дальше 10 м. Метры не выдаются.");
            return;
        }
        if (calibrated)
            f.path =
                    analyzePath(
                            f.floor, f.mw, f.mh, f.shot, f.bitmap.getWidth(), f.bitmap.getHeight());
        Bitmap marked = renderPath(f.bitmap, f.path);
        Canvas canvas = new Canvas(marked);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.YELLOW);
        p.setStrokeWidth(4);
        canvas.drawCircle((float) u, (float) v, 7, p);
        p.setTextSize(Math.max(18, marked.getWidth() / 22f));
        String label = String.format(Locale.getDefault(), "≈ %.1f м", distance);
        float
                lx =
                        (float)
                                Math.max(
                                        0,
                                        Math.min(
                                                u + 10,
                                                marked.getWidth() - p.measureText(label) - 4)),
                ly = (float) Math.max(p.getTextSize() + 4, v - 12);
        p.setColor(Color.BLACK);
        canvas.drawRect(lx - 3, ly - p.getTextSize() - 2, lx + p.measureText(label) + 3, ly + 4, p);
        p.setColor(Color.YELLOW);
        canvas.drawText(label, lx, ly, p);
        image.setImageBitmap(marked);
        target.setImageBitmap(marked);
        String text =
                (calibrated ? "Калибровка по этой точке сохранена. " : "")
                        + label
                        + " по ровному полу от точки под камерой. Высота "
                        + String.format(Locale.getDefault(), "%.2f", f.shot.h)
                        + " м.\nНаклон зафиксирован при снимке. "
                        + ((prefs.contains("focalScale")
                                        && Math.abs(prefs.getFloat("fovX", 0) - f.shot.fxAngle) < .1
                                        && Math.abs(prefs.getFloat("fovY", 0) - f.shot.fyAngle)
                                                < .1)
                                ? "Используется поправка по рулетке; проверьте другие точки."
                                : "Углы обзора взяты из камеры и пока не проверены.")
                        + "\n"
                        + frameDescription(f);
        announce(text);
    }
}
