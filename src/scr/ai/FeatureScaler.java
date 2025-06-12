package scr.ai;

/** Centralizza la normalizzazione delle feature, incluse le derivate. */
public final class FeatureScaler {

    private FeatureScaler() {}              // blocca le istanze

    public static double normalize(String f, double raw) {
        return switch (f) {
            /* direzione su cerchio unitario */
            case "angleSin", "angleCos" -> raw;          // già [-1,1]
            case "angle"                -> raw;

            /* motore / velocità */
            case "rpm"    -> raw / 10000.0;
            case "speedX" -> (raw - 140) / 50.0;
            case "speedY" ->  raw / 50.0;
            case "gear"   -> (raw-1) / 6.0;                   // 1…6

            /* posizione laterale */
            case "trackPos" -> raw;                      // -1…1

            /* curvatura stimata */
            case "curv" -> raw / 100.0;                  // scala empirica

            case "distanceFromStart" -> raw / 5784.10; // scala empirica

            /* sensori pista / ruote */
            default -> {
            if (f.startsWith("track"))
                yield raw / 200.0;
            else if (f.startsWith("wheel"))
                yield raw / 245.0;
            else
                yield raw;                           // fallback
            }
        };
    }
}
