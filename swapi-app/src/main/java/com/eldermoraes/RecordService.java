package com.eldermoraes;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The records of one type and the four ways to read them (issue #62: this was
 * six copies, one per type). A service of a type extends it and says only what
 * differs: the segment (people, films, ...), which names both the data file
 * (data/&lt;segment&gt;.json) and the end of a record's address
 * (/&lt;segment&gt;/&lt;id&gt;), the JSON-B type of the list, the text a search
 * matches (the title of a film, the name of anything else) and the address.
 *
 * Not a bean itself: the six services stay the @ApplicationScoped beans and
 * the types that are injected.
 */
public abstract class RecordService<T extends SWObject> {

    /**
     * The records of each segment, read once per JVM: the client proxy of a
     * bean and every instance a test builds share them, as they shared the
     * static list of each service before. Filled by the constructor, never by
     * a static initializer, so the files are read when the application runs.
     */
    private static final Map<String, List<? extends SWObject>> LOADED = new ConcurrentHashMap<>();

    private final String suffixStart;
    private final List<T> records;
    private final Function<T, String> searchText;
    private final Function<T, String> address;

    /**
     * Reads data/&lt;segment&gt;.json unless it was read already. A file that
     * cannot be loaded throws from here (DataFile, #49) and nothing is kept,
     * so the application does not start with a type it cannot serve.
     *
     * @param listType the List&lt;T&gt; that JSON-B reads the file as
     * @param searchText the text of a record that a search matches
     * @param address  the address of a record (its getUrl)
     */
    @SuppressWarnings("unchecked") // a segment is only ever loaded as the list type of its own service
    protected RecordService(String segment, Type listType, Function<T, String> searchText, Function<T, String> address) {
        this.suffixStart = "/" + segment + "/";
        this.records = (List<T>) LOADED.computeIfAbsent(segment,
                s -> DataFile.<T>load("data/" + s + ".json", listType));
        this.searchText = searchText;
        this.address = address;
    }

    /** Every record, in the order of the data file. */
    public List<T> all() {
        return records;
    }

    /** The record whose address ends with /&lt;segment&gt;/&lt;id&gt;, or null. */
    public T byId(int id) {
        String suffix = suffixStart + id;
        return records.stream()
                .filter(record -> {
                    String url = address.apply(record);
                    return url != null && url.endsWith(suffix);
                })
                .findFirst()
                .orElse(null);
    }

    /** The records whose name (or title) contains the text, in any case. */
    public List<T> search(String text) {
        return records.stream()
                .filter(record -> searchText.apply(record).toLowerCase().contains(text.toLowerCase()))
                .collect(Collectors.toList());
    }

    /** One record, drawn at random. */
    public T random() {
        return records.get(ThreadLocalRandom.current().nextInt(records.size()));
    }
}
