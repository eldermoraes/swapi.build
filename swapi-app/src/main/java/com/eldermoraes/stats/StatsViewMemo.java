package com.eldermoraes.stats;

import com.eldermoraes.stats.RouteStats.StatsView;
import com.eldermoraes.stats.StatsPeriod.Granularity;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The last view built for each period and granularity, served again while it
 * is young enough.
 *
 * {@code GET /api/stats} is public and nothing in front of the origin caches
 * it, so without this every request rebuilt its view. A view is reused for
 * {@code swapi.stats.view-lifetime}, never longer than {@link #ADVERTISED}; and
 * the {@code max-age} of a response is what is left of those 60 seconds, so
 * the numbers a client holds are never older than that in total.
 *
 * The key is time, not "nothing was recorded since": {@code /api/stats} is a
 * counted route, so the counters move on every request for it and such a key
 * would never be hit.
 *
 * One build at a time per view: requests that arrive while it is being built
 * wait for it and get the same one. Different views do not wait for each other.
 */
@Singleton
public class StatsViewMemo {

    /** How long a client may keep a view that was just built: the {@code max-age} of {@code GET /api/stats}. */
    static final Duration ADVERTISED = Duration.ofSeconds(60);

    private final RouteStats stats;
    private final Clock clock;
    private final Duration lifetime;
    private final Slot[] slots = new Slot[StatsPeriod.values().length * Granularity.values().length];

    @Inject
    public StatsViewMemo(RouteStats stats,
                         @ConfigProperty(name = "swapi.stats.view-lifetime") Duration lifetime) {
        this(stats, Clock.systemUTC(), lifetime);
    }

    /**
     * @param lifetime how long a view is served again; zero builds one for every request, and anything
     *                 above {@link #ADVERTISED} counts as {@link #ADVERTISED}
     */
    StatsViewMemo(RouteStats stats, Clock clock, Duration lifetime) {
        this.stats = stats;
        this.clock = clock;
        this.lifetime = lifetime.compareTo(ADVERTISED) > 0 ? ADVERTISED : lifetime;
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
        }
    }

    /**
     * The view of one window, built now or within the lifetime.
     *
     * @throws IllegalArgumentException if the period does not accept the granularity
     */
    public Served view(StatsPeriod period, Granularity granularity) {
        if (!period.accepts(granularity)) {
            throw new IllegalArgumentException(period.id() + " does not accept " + granularity.id());
        }
        return slots[period.ordinal() * Granularity.values().length + granularity.ordinal()].view(period, granularity);
    }

    /**
     * @param view          the view, shared with every other request served from the same build
     * @param maxAgeSeconds how long a client may still keep it: {@link #ADVERTISED} less its age, in whole
     *                      seconds rounded down
     */
    public record Served(StatsView view, long maxAgeSeconds) {
    }

    private final class Slot {

        private StatsView view;
        private Instant built;

        synchronized Served view(StatsPeriod period, Granularity granularity) {
            Instant now = clock.instant();
            if (view != null) {
                Duration age = Duration.between(built, now);
                // A clock that stepped back gives a negative age: stale, or the view would outlive its lifetime.
                if (!age.isNegative() && age.compareTo(lifetime) < 0) {
                    return new Served(view, ADVERTISED.minus(age).toSeconds());
                }
            }
            view = stats.view(period, granularity);
            // The age counts from before the build, so it never understates how old the numbers are.
            built = now;
            return new Served(view, ADVERTISED.toSeconds());
        }
    }
}
