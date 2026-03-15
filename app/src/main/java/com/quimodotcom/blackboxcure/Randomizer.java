package com.quimodotcom.blackboxcure;

import java.util.concurrent.ThreadLocalRandom;

public class Randomizer {

    public Randomizer() {}

    /** Zufällige Höhe im Bereich [elevation-diff, elevation+diff] */
    public float getElevation(float elevation, float diff) {
        float min = elevation - diff;
        float max = elevation + diff;
        max -= min;
        return (int) (Math.random() * ++max) + min;
    }

    /** Zufällige Geschwindigkeit im Bereich [speed-diff, speed+diff] */
    public int getRandomSpeed(int speed, int diff) {
        float min = speed - diff;
        float max = speed + diff;
        max -= min;
        return (int) ((Math.random() * ++max) + min);
    }

    /**
     * Simulierte Geschwindigkeit im Stillstand (sehr gering, aber nicht 0).
     * Wird von FixedSpooferService genutzt.
     *
     * @param base  Basiswert (typisch 0)
     * @param range Max. Abweichung (typisch 0.2f)
     */
    public float getStaticSpeed(float base, float range) {
        return (float) ThreadLocalRandom.current().nextDouble(base, base + range);
    }

    /**
     * Accuracy mit kleiner Schwankung ±2 (1-Parameter-Version für RouteSpooferService).
     */
    public float getAccuracy(float accuracy) {
        double diff = ThreadLocalRandom.current().nextDouble(-2, 2);
        return (float) (accuracy + diff);
    }

    /**
     * Accuracy mit konfigurierbarer Schwankung (2-Parameter-Version für FixedSpooferService).
     *
     * @param accuracy  Basisgenauigkeit in Metern
     * @param diff      Max. Abweichung in Metern
     */
    public float getAccuracy(float accuracy, float diff) {
        double delta = ThreadLocalRandom.current().nextDouble(-diff, diff);
        return (float) (accuracy + delta);
    }

    /**
     * Zufälliges Bearing mit konfigurierbarer Abweichung vom Basiswert.
     *
     * @param bearing   Basis-Bearing in Grad
     * @param maxDelta  Max. Abweichung in Grad (z.B. 3 → ±3°)
     */
    public float getBearing(float bearing, float maxDelta) {
        float delta = (float) ThreadLocalRandom.current().nextDouble(-maxDelta, maxDelta);
        return bearing + delta;
    }

    /**
     * Berechnet wie viele Route-Array-Punkte pro Update-Tick übersprungen werden müssen,
     * um die gewünschte Geschwindigkeit zu erreichen.
     */
    public int getArrayRunSpeed(int speed, int updatesDelay) {
        if (updatesDelay >= 1000) {
            return (speed * 1000) / (3600 * (updatesDelay / 1000));
        } else {
            float delay          = 3600 / ((float) updatesDelay / 1000f);
            float speedInMeters  = (float) speed * 1000;
            int   calculatedSpeed = (int) (speedInMeters / delay);
            if (calculatedSpeed == 0) calculatedSpeed = 1;
            return calculatedSpeed;
        }
    }
}
