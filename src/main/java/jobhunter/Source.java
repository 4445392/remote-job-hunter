package jobhunter;

import java.util.List;

/** A place that publishes jobs. Each source turns its own format into plain Job objects. */
interface Source {
    String name();

    /** Do not fetch more often than this (be polite, respect rate limits). */
    int minIntervalMinutes();

    default boolean enabled(Settings s) { return true; }

    List<Job> fetch(Http http, Settings s, Profile p) throws Exception;
}
