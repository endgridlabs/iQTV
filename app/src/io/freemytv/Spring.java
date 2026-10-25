package io.freemytv;

/** Damped spring, like macOS animations: response (s) and damping (1 = no bounce). */
final class Spring {
    float value, velocity, target;
    private final float response, damping, tolerance;

    Spring(float response, float damping, float initial) {
        this(response, damping, initial, 1);
    }

    /** `tolerance`: the units' size against 0 to 1 (a spring in pixels settles within half a pixel, not 0.001 px). */
    Spring(float response, float damping, float initial, float tolerance) {
        this.response = response;
        this.damping = damping;
        this.tolerance = tolerance;
        value = target = initial;
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;
    }

    /** Advances `dt` seconds; returns true while still moving. */
    boolean step(float dt) {
        if (settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        float omega = (float) (2 * Math.PI / response);
        float stiffness = omega * omega, friction = 2 * damping * omega;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -stiffness * (value - target) - friction * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < 0.0008f * tolerance && Math.abs(velocity) < 0.004f * tolerance;
    }
}
