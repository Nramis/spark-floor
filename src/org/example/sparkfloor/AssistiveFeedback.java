package org.example.sparkfloor;

import java.util.Locale;

/** A short observation first; the full scene description remains available on request. */
public final class AssistiveFeedback {
    public static String brief(
            SceneDescription.Result scene,
            FloorPath.Result path,
            ImageQuality.Result quality,
            String metricIssue) {
        if (quality != null && quality.unusable)
            return quality.hint() + " Оценка сцены не выполнена.";
        StringBuilder text = new StringBuilder();
        if (quality != null && !quality.hint().isEmpty()) text.append(quality.hint()).append(' ');
        boolean gapWarning =
                scene.centralGap
                        && scene.objects.stream().noneMatch(o -> "по центру".equals(o.position));
        if (gapWarning) text.append("По центру виден разрыв пола; причина неизвестна. ");
        if (!scene.objects.isEmpty()) {
            text.append("Возможные предметы у пола: ");
            for (int i = 0; i < Math.min(2, scene.objects.size()); i++) {
                if (i > 0) text.append("; ");
                SceneDescription.ObjectInfo object = scene.objects.get(i);
                text.append(
                                "по центру".equals(object.position)
                                        ? "перед камерой"
                                        : object.position + " от направления камеры")
                        .append(" — ")
                        .append(object.name);
            }
            text.append(". ");
        } else if (scene.floorDetected && !gapWarning) {
            text.append("Распознан предполагаемый пол. ");
        } else if (!scene.floorDetected) {
            text.append("Пол надёжно не определён. ");
        }
        if (scene.floorDetected && path != null && !path.points.isEmpty()) {
            text.append(
                    String.format(
                            Locale.getDefault(),
                            "Видимая часть дорожки около %.1f м. ",
                            path.length));
            if (!path.bends.isEmpty())
                text.append(
                        path.bends.get(0).degrees > 0
                                ? "Предполагается изгиб вправо. "
                                : "Предполагается изгиб влево. ");
        } else if (scene.floorDetected && Double.isFinite(scene.centralLength)) {
            text.append(
                    String.format(
                            Locale.getDefault(),
                            "Видимый отрезок пола по центру около %.1f м. ",
                            scene.centralLength));
        } else {
            text.append(metricIssue == null ? "Длину оценить не удалось. " : metricIssue + " ");
        }
        return text.append(
                        "Проходимость не подтверждена. Подробности — кнопкой или командой «Подробнее».")
                .toString();
    }
}
