package io.eim.connectors.ingestion;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field mapping + type coercion (FR-202): source records map to the EIM
 * canonical transaction shape against a mapping definition loaded from the
 * configuration registry (entity `connector.<name>.mapping`). Type coercion
 * validates BEFORE any row commits; failures carry per-field reasons.
 */
@ApplicationScoped
public class FieldMappingService {

    /** Canonical, validated transaction ready for insertion. */
    public record MappedTransaction(String externalDealId, LocalDate transactionDate,
                                    BigDecimal bookingAmount, String currencyCode,
                                    Map<String, String> customAttributes, String payeeEmail,
                                    BigDecimal splitPct) {
    }

    /** Result of mapping one source record: either mapped or rejected w/ errors. */
    public record MappingResult(MappedTransaction mapped, Map<String, Object> raw, List<String> errors) {
        public boolean accepted() {
            return errors.isEmpty();
        }
    }

    /**
     * Map one raw source record. The mapping keys are source field names; the
     * special key {@code __splits} is a list of {email, pct} for credit
     * assignment (FR-301). Missing/invalid fields produce error entries —
     * never exceptions — so batches can quarantine per-row (FR-203).
     */
    public MappingResult map(Map<String, Object> record, Map<String, String> mapping) {
        List<String> errors = new ArrayList<>();
        Map<String, String> custom = new LinkedHashMap<>();

        String extId = required(record, resolve(record, mapping.get("external_deal_id"), "external_deal_id"),
                errors, "external_deal_id");
        LocalDate date = dateField(record, resolve(record, mapping.get("transaction_date"), "transaction_date"), errors);
        BigDecimal amount = decimalField(record, resolve(record, mapping.get("booking_amount"), "booking_amount"), errors);
        String currency = required(record,
                resolve(record, mapping.getOrDefault("currency_code", "currency"), "currency_code"),
                errors, "currency_code");
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            errors.add("currency_code must be ISO 4217 (3 uppercase letters)");
            currency = null;
        }
        String payeeEmail = optional(record, resolve(record, mapping.get("payee_email"), "payee_email"));
        BigDecimal splitPct = pctField(record, resolve(record, mapping.get("split_pct"), "split_pct"), payeeEmail != null);

        // Unmapped source fields flow into custom_attributes (FR-202.2).
        for (Map.Entry<String, Object> e : record.entrySet()) {
            if (!mapping.containsValue(e.getKey()) && !CANONICAL.contains(e.getKey()) && e.getValue() != null) {
                custom.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        MappedTransaction tx = errors.isEmpty()
                ? new MappedTransaction(extId, date, amount, currency, custom, payeeEmail, splitPct)
                : null;
        return new MappingResult(tx, record, errors);
    }

    /** Canonical names a source record may use directly, without a mapping entry. */
    private static final java.util.Set<String> CANONICAL = java.util.Set.of(
            "external_deal_id", "transaction_date", "booking_amount", "currency_code", "payee_email", "split_pct");

    /**
     * Resolve the source field for a canonical name. Sources that already
     * emit the canonical name (e.g. a SOQL/JDBC column alias) need no
     * mapping entry — fall back to the canonical key when the mapped
     * column is absent from the record.
     */
    private static String resolve(Map<String, Object> record, String mapped, String canonical) {
        if (mapped == null || !record.containsKey(mapped)) {
            return record.containsKey(canonical) ? canonical : mapped;
        }
        return mapped;
    }

    private String required(Map<String, Object> record, String field, List<String> errors, String canonical) {
        Object v = field == null ? null : record.get(field);
        if (v == null || String.valueOf(v).isBlank()) {
            errors.add(canonical + " is required (source field: " + field + ")");
            return null;
        }
        return String.valueOf(v);
    }

    private String optional(Map<String, Object> record, String field) {
        if (field == null) {
            return null;
        }
        Object v = record.get(field);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private LocalDate dateField(Map<String, Object> record, String field, List<String> errors) {
        String v = required(record, field, errors, "transaction_date");
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(v, DateTimeFormatter.ofPattern("yyyy/M/d"));
            } catch (RuntimeException e2) {
                errors.add("transaction_date is not a valid date: " + v);
                return null;
            }
        }
    }

    private BigDecimal decimalField(Map<String, Object> record, String field, List<String> errors) {
        String v = required(record, field, errors, "booking_amount");
        if (v == null) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(v.replace(",", ""));
            if (d.scale() > 4) {
                errors.add("booking_amount exceeds NUMERIC(18,4) scale");
                return null;
            }
            return d;
        } catch (NumberFormatException e) {
            errors.add("booking_amount is not a decimal: " + v);
            return null;
        }
    }

    private BigDecimal pctField(Map<String, Object> record, String field, boolean hasPayee) {
        if (field == null) {
            return hasPayee ? BigDecimal.ONE : null;
        }
        Object v = record.get(field);
        if (v == null || String.valueOf(v).isBlank()) {
            return hasPayee ? BigDecimal.ONE : null;
        }
        try {
            String s = String.valueOf(v).replace("%", "");
            BigDecimal pct = new BigDecimal(s);
            return pct.scale() > 6 ? pct.setScale(6, java.math.RoundingMode.UNNECESSARY) : pct;
        } catch (NumberFormatException e) {
            return hasPayee ? BigDecimal.ONE : null;
        }
    }
}
