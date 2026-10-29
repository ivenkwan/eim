package io.eim.plancompiler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Plan DSL (FR-401/402): a plan is a set of typed components. Components
 * reference each other with {@code @name}, parameters and standard facts
 * with {@code $name}; the type checker validates the whole graph before
 * anything is generated or published.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanModel(String planName, List<Component> components) {

    public enum Kind { FORMULA, TIER_TABLE, WINDOW, DRAW, CAP, CLAWBACK, ROLLUP }

    /** Result types (FR-402.2): the type lattice the checker enforces. */
    public enum OutputType { CURRENCY, PERCENT, DECIMAL, BOOLEAN, DATE }

    /** Input types: parameters and standard facts carry these. */
    public enum InputType { CURRENCY, PERCENT, DECIMAL, BOOLEAN, DATE }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Component(String name, Kind kind, OutputType outputType,
                            String formula, List<Parameter> parameters,
                            TierTable tiers, Protections protections,
                            List<String> dependsOn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parameter(String name, InputType type, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TierTable(TierMode mode, TierBasis basis, List<Bracket> brackets) {
    }

    public enum TierMode { MARGINAL, CLIFF }

    public enum TierBasis { AMOUNT, PERCENT }

    /** upTo == null means the catch-all bracket (must be last and unique). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bracket(String upTo, String rate) {
    }

    /**
     * Protections (FR-405): draws/caps/clawback windows. Values arrive as
     * strings from the no-code grid and are validated here, not trusted.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Protections(String drawKind, String capScope, String capBasis,
                              String capValue, String clawbackWindowDays) {

        public static Protections none() {
            return new Protections(null, null, null, null, null);
        }
    }

    public Component component(String name) {
        return components == null ? null
                : components.stream().filter(c -> name.equals(c.name())).findFirst().orElse(null);
    }
}
