package io.eim.calcengine.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Plan model as consumed by the engine (mirrors the plan-compiler DSL).
 * The engine re-derives the component order from dependencies so the same
 * plan JSON drives both the generated rules and the numeric core.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineModel(String planName, List<Component> components) {

    public enum Kind { FORMULA, TIER_TABLE, WINDOW, DRAW, CAP, CLAWBACK, ROLLUP }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Component(String name, Kind kind, String formula,
                            List<Parameter> parameters, TierTable tiers,
                            Protections protections, List<String> dependsOn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parameter(String name, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TierTable(String mode, String basis, List<Bracket> brackets) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bracket(String upTo, String rate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Protections(String drawKind, String capScope, String capBasis,
                              String capValue, String clawbackWindowDays) {
    }

    public Component component(String name) {
        return components == null ? null
                : components.stream().filter(c -> name.equals(c.name())).findFirst().orElse(null);
    }
}
