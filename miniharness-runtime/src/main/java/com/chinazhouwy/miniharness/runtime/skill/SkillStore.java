package com.chinazhouwy.miniharness.runtime.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Skill 仓储。 */
public class SkillStore {

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public synchronized void save(Skill skill) {
        skills.put(skill.id(), skill);
    }

    public synchronized Optional<Skill> find(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public synchronized List<Skill> all() {
        return List.copyOf(skills.values());
    }

    public synchronized boolean existsByName(String name) {
        return skills.values().stream().anyMatch(s -> s.name().equals(name));
    }
}
