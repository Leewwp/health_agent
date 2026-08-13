package com.diet.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SlotBundle {
    private List<String> mealTime;
    private List<String> mood;
    private List<String> scene;
    private List<String> healthGoal;
    private List<String> cuisine;
    private List<String> taste;
    private List<String> convenience;

    public SlotBundle(List<String> mealTime, List<String> mood, List<String> scene, List<String> healthGoal, List<String> cuisine, List<String> taste, List<String> convenience) {
        this.mealTime = normalize(mealTime);
        this.mood = normalize(mood);
        this.scene = normalize(scene);
        this.healthGoal = normalize(healthGoal);
        this.cuisine = normalize(cuisine);
        this.taste = normalize(taste);
        this.convenience = normalize(convenience);
    }

    public static SlotBundle empty() {
        return new SlotBundle(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 健康链七维餐食槽位 Map → 旧饮食链槽位对象。 */
    public static SlotBundle fromHealthSlots(Map<String, List<String>> healthSlots) {
        Map<String, List<String>> safe = healthSlots == null ? Map.of() : healthSlots;
        return new SlotBundle(
                safe.getOrDefault("mealTime", List.of()),
                safe.getOrDefault("mood", List.of()),
                safe.getOrDefault("scene", List.of()),
                safe.getOrDefault("healthGoal", List.of()),
                safe.getOrDefault("cuisine", List.of()),
                safe.getOrDefault("taste", List.of()),
                safe.getOrDefault("convenience", List.of())
        );
    }

    public boolean isEmpty() {
        return mealTime.isEmpty()
                && mood.isEmpty()
                && scene.isEmpty()
                && healthGoal.isEmpty()
                && cuisine.isEmpty()
                && taste.isEmpty()
                && convenience.isEmpty();
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}




