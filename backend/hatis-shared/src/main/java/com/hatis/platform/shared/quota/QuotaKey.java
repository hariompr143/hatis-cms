package com.hatis.platform.shared.quota;

/**
 * The set of billable, limited resources. Adding a key here is a product
 * decision: it must exist in a plan definition before it can be enforced.
 */
public enum QuotaKey {

    PROJECTS("projects"),
    ENVIRONMENTS("environments"),
    APPLICATIONS("applications"),
    DEPLOYMENTS("deployments"),
    USERS("users"),
    API_KEYS("api_keys"),
    STORAGE_GIB("storage_gib"),
    /** Byte-accurate storage accounting, used for enforcement rather than display. */
    STORAGE_BYTES("storage_bytes"),
    /** Per-object ceiling. Stops one upload exhausting a plan regardless of quota headroom. */
    ASSET_MAX_BYTES("asset_max_bytes"),
    ASSET_COUNT("asset_count"),
    CONTENT_ITEMS("content_items"),
    CPU_MILLI("cpu_milli"),
    MEMORY_MIB("memory_mib"),
    BANDWIDTH_GIB("bandwidth_gib"),
    DATABASES("databases"),
    DOMAINS("domains"),
    WEBHOOKS("webhooks"),
    DASHBOARDS("dashboards"),
    API_REQUESTS_MONTH("api_requests_month"),
    BUILDS_MONTH("builds_month");

    private final String wireValue;

    QuotaKey(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static QuotaKey fromWire(String value) {
        for (QuotaKey key : values()) {
            if (key.wireValue.equals(value)) {
                return key;
            }
        }
        throw new IllegalArgumentException("Unknown quota key: " + value);
    }
}
