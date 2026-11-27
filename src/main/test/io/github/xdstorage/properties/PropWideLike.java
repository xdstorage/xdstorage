package io.github.xdstorage.properties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PROPTEST (docs/tasks/2.2.0/03): the shared shape of the three policy variants —
 * the generator and the field-by-field comparator work against this interface so one
 * property covers StoreAsClassObjects (hash), StoreAsClassObjects (btree) and
 * StoreAsSingleObject with an identical field population.
 */
public interface PropWideLike {

    String getId();

    void setId(String id);

    String getName();

    void setName(String name);

    Long getAmount();

    void setAmount(Long amount);

    Integer getCount();

    void setCount(Integer count);

    Double getRatio();

    void setRatio(Double ratio);

    Boolean getActive();

    void setActive(Boolean active);

    Date getCreated();

    void setCreated(Date created);

    BigDecimal getDecimal();

    void setDecimal(BigDecimal decimal);

    List<String> getTags();

    void setTags(List<String> tags);

    Map<String, String> getMeta();

    void setMeta(Map<String, String> meta);

    String[] getLabels();

    void setLabels(String[] labels);

    PropNested getNested();

    void setNested(PropNested nested);

    /** UUID — stored as its canonical ISO-8601 string. */
    UUID getUuid();
    void setUuid(UUID uuid);

    /** Instant — stored as ISO-8601. */
    Instant getInstant();
    void setInstant(Instant instant);

    /** LocalDate — stored as ISO-8601. */
    LocalDate getLocalDate();
    void setLocalDate(LocalDate localDate);

    /** LocalDateTime — stored as ISO-8601. */
    LocalDateTime getLocalDateTime();
    void setLocalDateTime(LocalDateTime localDateTime);

    /** Duration — stored as ISO-8601 (ISO-8601 duration format, e.g. "PT1H30M"). */
    Duration getDuration();
    void setDuration(Duration duration);
}
