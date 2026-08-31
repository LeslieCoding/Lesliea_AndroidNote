package com.leslie.colorosnote.document.office;

/**
 * Supplies commercial-SDK license state (requirement 12.1/49: license from secure
 * configuration, never hardcoded in the repo). The built-in POI engine does not
 * need a license; this exists so a commercial adapter can decide between full /
 * trial / development mode at runtime.
 */
public interface LicenseProvider {

    /** License state for one vendor, e.g. "apryse" or "pspdfkit". */
    LicenseState licenseState(String vendorId);

    final class LicenseState {

        public enum Status {
            /** Valid production license present. */
            LICENSED,
            /** Trial key present; output may carry a vendor watermark. */
            TRIAL,
            /** No key configured; engine must run in a clearly-flagged dev mode. */
            DEV_MODE,
            /** The vendor SDK is not even configured; treat as unavailable. */
            MISSING
        }

        private final Status status;
        private final String vendorId;
        /** License key material; never log this. May be null in dev/missing modes. */
        private final String keyMaterial;
        /** Expiry epoch millis, or 0 when unknown/unbounded. */
        private final long expiresAtEpochMs;
        private final String message;

        public LicenseState(Status status, String vendorId, String keyMaterial,
                            long expiresAtEpochMs, String message) {
            this.status = status;
            this.vendorId = vendorId;
            this.keyMaterial = keyMaterial;
            this.expiresAtEpochMs = expiresAtEpochMs;
            this.message = message;
        }

        public static LicenseState licensed(String vendorId, String keyMaterial, long expiresAtEpochMs) {
            return new LicenseState(Status.LICENSED, vendorId, keyMaterial, expiresAtEpochMs, "licensed");
        }

        public static LicenseState trial(String vendorId, String keyMaterial, long expiresAtEpochMs) {
            return new LicenseState(Status.TRIAL, vendorId, keyMaterial, expiresAtEpochMs,
                    "trial license; output may be watermarked");
        }

        public static LicenseState devMode(String vendorId) {
            return new LicenseState(Status.DEV_MODE, vendorId, null, 0L,
                    "no license configured; development mode");
        }

        public static LicenseState missing(String vendorId) {
            return new LicenseState(Status.MISSING, vendorId, null, 0L, "vendor SDK not configured");
        }

        public Status getStatus() {
            return status;
        }

        public String getVendorId() {
            return vendorId;
        }

        public String getKeyMaterial() {
            return keyMaterial;
        }

        public long getExpiresAtEpochMs() {
            return expiresAtEpochMs;
        }

        public String getMessage() {
            return message;
        }
    }
}
