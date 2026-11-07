package org.example.sparkfloor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Describes semantic evidence in one image. No claim that unlabelled space is traversable. */
public final class SceneDescription {
    public static final class ObjectInfo {
        public final int label, area;
        public final String name, position;

        /** Optional estimate to an observed object/floor contact, never from object height. */
        public final double distance;

        ObjectInfo(int label, int area, String name, String position, double distance) {
            this.label = label;
            this.area = area;
            this.name = name;
            this.position = position;
            this.distance = distance;
        }
    }

    public static final class Result {
        public final String summary;
        public final List<ObjectInfo> objects;
        public final double centralLength, centralStart;

        Result(
                String summary,
                List<ObjectInfo> objects,
                double centralLength,
                double centralStart) {
            this.summary = summary;
            this.objects = Collections.unmodifiableList(new ArrayList<>(objects));
            this.centralLength = centralLength;
            this.centralStart = centralStart;
        }
    }

    public static Result describe(
            int[] labels,
            int width,
            int height,
            FloorPath.Result path,
            GroundProjection camera,
            String unavailableReason) {
        if (labels == null || width < 3 || height < 3 || labels.length != width * height)
            throw new IllegalArgumentException("Invalid scene labels");
        boolean[] floor = Segmentation.floorMask(labels);
        int floorCount = 0;
        for (boolean cell : floor) if (cell) floorCount++;
        boolean hasFloor = floorCount >= Math.max(8, labels.length / 200);
        List<ObjectInfo> objects = findObjects(labels, floor, width, height, camera);
        StringBuilder text =
                new StringBuilder(
                        hasFloor
                                ? "В кадре распознан предполагаемый пол. "
                                : "Пол в кадре не определён или виден слишком маленьким фрагментом. ");
        if (!objects.isEmpty()) {
            text.append("Возможные препятствия рядом с видимым полом: ");
            for (int i = 0; i < objects.size(); i++) {
                if (i > 0) text.append("; ");
                ObjectInfo object = objects.get(i);
                text.append(object.position).append(" — ").append(object.name);
                if (Double.isFinite(object.distance))
                    text.append(
                            String.format(
                                    Locale.getDefault(),
                                    ", до предполагаемого основания около %.1f м",
                                    object.distance));
            }
            text.append(". Положение указано относительно кадра. ");
        } else {
            text.append(
                    "Распознанных предметов у пола не выделено; это не означает отсутствие препятствий. ");
        }
        if (hasFloor
                && objects.stream().noneMatch(o -> "по центру".equals(o.position))
                && centralGap(floor, width, height))
            text.append(
                    "В центральной части маски пола есть разрыв. Это может быть предмет, граница поверхности или ошибка распознавания. ");

        double length = Double.NaN, start = Double.NaN;
        if (!hasFloor) {
            text.append("Длину видимого пути оценить нельзя. ");
        } else if (camera == null) {
            text.append(
                    unavailableReason == null
                            ? "Длина в метрах недоступна: нет параметров съёмки. "
                            : unavailableReason + " ");
        } else if (path != null && !path.points.isEmpty()) {
            text.append(path.summary(false)).append(' ');
        } else {
            double[] span = centralSpan(floor, width, height, camera);
            start = span[0];
            length = span[1];
            if (Double.isFinite(length)) {
                text.append(
                        String.format(
                                Locale.getDefault(),
                                "По центру виден непрерывный участок пола длиной примерно %.1f м. "
                                        + "Начало этого участка — примерно в %.1f м от точки под камерой. ",
                                length,
                                start));
                text.append(
                        "Это длина видимого отрезка по центру, без невидимого продолжения. "
                                + "Форму и изгибы дорожки здесь определить не удалось. ");
            } else {
                text.append(
                        "Непрерывного видимого участка по центру недостаточно для оценки длины. ");
            }
            if (path != null && path.end == FloorPath.End.BRANCH)
                text.append("Маска пола разделяется; продолжение неоднозначно. ");
            else if (path != null && path.end == FloorPath.End.DISCONTINUITY)
                text.append("В маске есть резкое изменение или разрыв. ");
        }
        text.append("Оценка по одному снимку; проходимость не подтверждена.");
        return new Result(text.toString(), objects, length, start);
    }

    /** A straight, 0.3 m wide observed strip; does not reconstruct a centreline from cropped edges. */
    private static double[] centralSpan(
            boolean[] floor, int width, int height, GroundProjection camera) {
        double start = Double.NaN, last = Double.NaN;
        for (int row = 1; row <= 400; row++) {
            double z = row * .025;
            boolean inside = true;
            for (int col = -3; col <= 3; col++) {
                double x = col * .05;
                double[] p = camera.toImage(x, z);
                if (p == null) {
                    inside = false;
                    break;
                }
                int mx = (int) (p[0] * width / camera.width),
                        my = (int) (p[1] * height / camera.height);
                if (mx < 1 || my < 1 || mx >= width - 1 || my >= height - 1) {
                    inside = false;
                    break;
                }
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        if (!floor[(my + dy) * width + mx + dx]) inside = false;
                if (!inside) break;
            }
            if (!inside) {
                if (Double.isFinite(start)) break;
                continue;
            }
            if (!Double.isFinite(start)) {
                if (z > 3) break;
                start = z;
            }
            last = z;
        }
        return Double.isFinite(last) && last - start >= .5
                ? new double[] {start, last - start}
                : new double[] {Double.NaN, Double.NaN};
    }

    private static boolean centralGap(boolean[] floor, int width, int height) {
        boolean nearFloor = false;
        int gap = 0;
        for (int y = height * 19 / 20; y >= height * 7 / 20; y--) {
            int count = 0, left = width * 9 / 20, right = width * 11 / 20;
            for (int x = left; x <= right; x++) if (floor[y * width + x]) count++;
            if (count >= (right - left + 1) * .6) {
                if (nearFloor && gap >= Math.max(3, height / 25)) return true;
                nearFloor = true;
                gap = 0;
            } else if (nearFloor) gap++;
        }
        return false;
    }

    private static List<ObjectInfo> findObjects(
            int[] labels, boolean[] floor, int width, int height, GroundProjection camera) {
        boolean[] visited = new boolean[labels.length];
        int[] queue = new int[labels.length];
        List<ObjectInfo> objects = new ArrayList<>();
        for (int seed = 0; seed < labels.length; seed++) {
            int label = labels[seed];
            String name = objectName(label);
            if (visited[seed] || name == null) continue;
            int head = 0, tail = 1, bottom = -1;
            long sumX = 0;
            boolean touchesFloor = false;
            List<double[]> contacts = new ArrayList<>();
            queue[0] = seed;
            visited[seed] = true;
            while (head < tail) {
                int index = queue[head++], x = index % width, y = index / width;
                sumX += x;
                bottom = Math.max(bottom, y);
                // Ground support must be visible just below the component, not merely its side.
                if (y + 1 < height && floor[index + width])
                    contacts.add(new double[] {x + .5, y + 1.5});
                for (int dy = -2; dy <= 2 && !touchesFloor; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        if (x + dx >= 0
                                && x + dx < width
                                && y + dy >= 0
                                && y + dy < height
                                && floor[(y + dy) * width + x + dx]) touchesFloor = true;
                int[] neighbors = {
                    x > 0 ? index - 1 : -1,
                    x + 1 < width ? index + 1 : -1,
                    y > 0 ? index - width : -1,
                    y + 1 < height ? index + width : -1
                };
                for (int n : neighbors)
                    if (n >= 0 && !visited[n] && labels[n] == label) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
            }
            if (tail < Math.max(6, labels.length / 1000) || bottom < height * .35 || !touchesFloor)
                continue;
            double centre = sumX / (double) tail / width;
            String position = centre < .36 ? "слева" : centre > .64 ? "справа" : "по центру";
            double distance = Double.NaN;
            if (camera != null && bottom < height - 2) {
                List<Double> ranges = new ArrayList<>();
                for (double[] p : contacts) {
                    if (p[1] < bottom - 1) continue;
                    double[] point =
                            camera.toGround(
                                    p[0] * camera.width / width, p[1] * camera.height / height);
                    if (point != null && point[1] > 0) ranges.add(Math.hypot(point[0], point[1]));
                }
                if (ranges.size() >= 3) {
                    Collections.sort(ranges);
                    double median = ranges.get(ranges.size() / 2);
                    if (ranges.get(ranges.size() - 1) - ranges.get(0) < Math.max(.4, median * .25))
                        distance = median;
                }
            }
            objects.add(new ObjectInfo(label, tail, name, position, distance));
        }
        objects.sort(
                Comparator.comparingInt((ObjectInfo o) -> "по центру".equals(o.position) ? 0 : 1)
                        .thenComparing(
                                Comparator.comparingInt((ObjectInfo o) -> o.area).reversed()));
        List<ObjectInfo> unique = new ArrayList<>();
        for (ObjectInfo object : objects) {
            boolean duplicate = false;
            for (ObjectInfo previous : unique)
                if (previous.label == object.label && previous.position.equals(object.position))
                    duplicate = true;
            if (!duplicate) unique.add(object);
            if (unique.size() == 3) break;
        }
        return unique;
    }

    /** Selected ADE20K labels from assets/model-config.json; wall/sky are not object detections. */
    private static String objectName(int label) {
        switch (label) {
            case 7:
                return "кровать";
            case 10:
                return "шкаф";
            case 12:
                return "человек";
            case 14:
                return "дверь или её граница";
            case 15:
            case 64:
                return "стол";
            case 17:
                return "растение";
            case 67:
                return "книга";
            case 19:
            case 30:
            case 75:
            case 110:
                return "стул или кресло";
            case 20:
                return "автомобиль";
            case 23:
                return "диван";
            case 24:
            case 62:
                return "стеллаж или полка";
            case 32:
            case 38:
            case 95:
                return "ограждение";
            case 34:
                return "камень";
            case 35:
                return "шкаф";
            case 39:
                return "подушка";
            case 41:
                return "коробка";
            case 42:
                return "колонна";
            case 44:
                return "комод";
            case 52:
                return null;
            case 53:
            case 59:
            case 121:
                return "ступени или лестница";
            case 69:
                return "скамья";
            case 76:
                return "лодка";
            case 80:
                return "автобус";
            case 90:
                return "самолёт";
            case 102:
                return "фургон";
            case 103:
                return "корабль";
            case 104:
                return "фонтан";
            case 108:
                return "игрушка";
            case 115:
                return "сумка";
            case 116:
                return "мотоцикл";
            case 119:
                return "мяч";
            case 126:
                return "животное";
            case 127:
                return "велосипед";
            case 133:
                return "крышка или кожух";
            case 138:
                return "мусорная корзина";
            default:
                return null;
        }
    }
}
