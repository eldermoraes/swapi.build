package com.eldermoraes.stats;

import com.eldermoraes.stats.RouteStats.StatsView;
import com.eldermoraes.stats.StatsPeriod.Granularity;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

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
 * would never be hit. The age is measured on the monotonic clock: a wall clock
 * that is stepped back or forth neither stretches nor cuts a lifetime.
 *
 * One build at a time per view: requests that arrive while it is being built
 * wait for it and get the same one. Different views do not wait for each other.
 */
@Singleton
public class StatsViewMemo {

    /** How long a client may keep a view that was just built: the {@code max-age} of {@code GET /api/stats}. */
    static final Duration ADVERTISED = Duration.ofSeconds(60);

    private final RouteStats stats;
    private final LongSupplier nanoTime;
    private final long lifetimeNanos;
    /** One slot per window a period accepts; an unaccepted pair has none. */
    private final Map<StatsPeriod, Map<Granularity, Slot>> slots = new EnumMap<>(StatsPeriod.class);

    @Inject
    public StatsViewMemo(RouteStats stats,
                         @ConfigProperty(name = "swapi.stats.view-lifetime") Duration lifetime) {
        this(stats, System::nanoTime, lifetime);
    }

    /**
     * @param nanoTime a clock that only moves forward, in nanoseconds from any origin
     * @param lifetime how long a view is served again; zero builds one for every request, and anything
     *                 above {@link #ADVERTISED} counts as {@link #ADVERTISED}
     */
    StatsViewMemo(RouteStats stats, LongSupplier nanoTime, Duration lifetime) {
        this.stats = stats;
        this.nanoTime = nanoTime;
        this.lifetimeNanos = (lifetime.compareTo(ADVERTISED) > 0 ? ADVERTISED : lifetime).toNanos();
        for (StatsPeriod period : StatsPeriod.values()) {
            Map<Granularity, Slot> ofPeriod = new EnumMap<>(Granularity.class);
            for (Granularity granularity : Granularity.values()) {
                if (period.accepts(granularity)) {
                    ofPeriod.put(granularity, new Slot(period, granularity));
                }
            }
            slots.put(period, ofPeriod);
        }
    }

    /**
     * The view of one window, built now or within the lifetime.
     *
     * @throws IllegalArgumentException if the period does not accept the granularity
     */
    public Served view(StatsPeriod period, Granularity granularity) {
        Slot slot = slots.get(period).get(granularity);
        if (slot == null) {
            throw new IllegalArgumentException(period.id() + " does not accept " + granularity.id());
        }
        return slot.view();
    }

    /**
     * @param view          the view, shared with every other request served from the same build
     * @param maxAgeSeconds how long a client may still keep it: {@link #ADVERTISED} less its age, in whole
     *                      seconds rounded down
     */
    public record Served(StatsView view, long maxAgeSeconds) {
    }

    private final class Slot {

        private final StatsPeriod period;
        private final Granularity granularity;
        private StatsView view;
        private long builtNanos;

        Slot(StatsPeriod period, Granularity granularity) {
            this.period = period;
            this.granularity = granularity;
        }

        synchronized Served view() {
            long now = nanoTime.getAsLong();
            if (view != null) {
                long age = now - builtNanos;
                if (age < lifetimeNanos) {
                    return new Served(view, ADVERTISED.minusNanos(age).toSeconds());
                }
            }
            view = stats.view(period, granularity);
            // The age counts from before the build, so it never understates how old the numbers are.
            builtNanos = now;
            return new Served(view, ADVERTISED.toSeconds());
        }
    }
}
