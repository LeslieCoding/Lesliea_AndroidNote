package com.leslie.colorosnote.document.office;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps {@link OfficeType} to the active {@link OfficeImportEngine}. Defaults to the
 * built-in {@link PoiOfficeEngine} for all types.
 *
 * <p>Commercial SDK integration point (requirement 12.1 + 49): a vendor adapter —
 * e.g. {@code class ApryseOfficeEngineAdapter implements OfficeImportEngine},
 * living in its own module so the main app never couples to the vendor — is
 * registered here at app start, typically gated on
 * {@link LicenseProvider#licenseState(String)}:
 *
 * <pre>{@code
 * LicenseState lic = licenseProvider.licenseState("apryse");
 * if (lic.getStatus() == Status.LICENSED || lic.getStatus() == Status.TRIAL) {
 *     OfficeImportEngine adapter = new ApryseOfficeEngineAdapter(lic);
 *     for (OfficeType t : OfficeType.values()) {
 *         OfficeEngineRegistry.registerEngine(t, adapter);
 *     }
 * } // else keep the built-in POI engine; dev mode is surfaced in the UI.
 * }</pre>
 *
 * <p>The vendor adapter must add a visible warning to every result while running
 * on a trial/dev license so watermark output can never masquerade as done.
 */
public final class OfficeEngineRegistry {

    private static final Map<OfficeType, OfficeImportEngine> ENGINES =
            new EnumMap<>(OfficeType.class);

    static {
        resetToDefaults();
    }

    private OfficeEngineRegistry() {
    }

    /** Registers/replaces the engine for one type. Call from app startup on the main thread. */
    public static synchronized void registerEngine(OfficeType type, OfficeImportEngine engine) {
        if (type == null || engine == null) {
            throw new IllegalArgumentException("type and engine are required");
        }
        ENGINES.put(type, engine);
    }

    /** Returns the engine for the type; never null while defaults are installed. */
    public static synchronized OfficeImportEngine engineFor(OfficeType type) {
        return ENGINES.get(type);
    }

    /** Restores the built-in POI engine for every type. Also useful in tests. */
    public static synchronized void resetToDefaults() {
        PoiOfficeEngine poi = new PoiOfficeEngine();
        for (OfficeType type : OfficeType.values()) {
            ENGINES.put(type, poi);
        }
    }
}
