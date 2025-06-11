
package scr.ai;

import java.io.Serializable;

/**
 * Centralized feature scaling utility. The rules are
 * the same between offline dataset building and online inference.
 * If you change a rule here, both phases automatically share it.
 *
 * All values are approximately mapped in the range [-1, 1].
 */
public class FeatureScaler implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Normalise a raw value for the given feature name. */
    public static double normalize(String feature, double val) {
        return switch (feature) {
            /* Angle already ~[-π,π] but in TORCS it's small. Map directly. */
            case "angle" -> val;

            /* Lap time centred around 22 s with spread ≈ 15 s */
            case "curLapTime" -> (val - 22.0) / 15.0;

            /* Longitudinal speed (km/h). Expect 0‑300. Centre at 140 */
            case "speedX" -> (val - 140.0) / 50.0;

            /* Lateral speed. */
            case "speedY" -> val / 50.0;

            /* Track position already in [‑1,1] */
            case "trackPos" -> val;

            /* Gear 1‑6 -> map to [0,1] */
            case "gear" -> (val - 1.0) / 5.0;

            /* RPM up to 10k. */
            case "rpm" -> val / 10000.0;

            /* Damage can reach a few thousands. Scale conservatively. */
            case "damage" -> val / 1000.0;

            /* Last lap time seconds. */
            case "lastLapTime" -> val / 60.0;

            /* Track sensors (0‑200) */
            default -> {
                if (feature.startsWith("track")) {
                    yield val / 200.0;
                } else if (feature.startsWith("wheel")) {
                    yield val / 200.0;
                } else {
                    /* Fallback: leave unchanged */
                    yield val;
                }
            }
        };
    }
}
