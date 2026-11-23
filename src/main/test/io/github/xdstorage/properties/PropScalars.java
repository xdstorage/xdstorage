package io.github.xdstorage.properties;

import java.math.BigDecimal;
import java.util.Date;

/** PROPTEST Этап 2: every scalar flavor (the embedded default policy). */
public class PropScalars {
    private long primitiveLong;
    private boolean primitiveBool;
    private Integer boxedInt;
    private Double boxedDouble;
    private String text;
    private BigDecimal decimal;
    private Date moment;
    private PropColor color;
    private Class<?> className;
    private String maybeNull;

    public long getPrimitiveLong() {
        return primitiveLong;
    }

    public void setPrimitiveLong(final long primitiveLong) {
        this.primitiveLong = primitiveLong;
    }

    public boolean isPrimitiveBool() {
        return primitiveBool;
    }

    public void setPrimitiveBool(final boolean primitiveBool) {
        this.primitiveBool = primitiveBool;
    }

    public Integer getBoxedInt() {
        return boxedInt;
    }

    public void setBoxedInt(final Integer boxedInt) {
        this.boxedInt = boxedInt;
    }

    public Double getBoxedDouble() {
        return boxedDouble;
    }

    public void setBoxedDouble(final Double boxedDouble) {
        this.boxedDouble = boxedDouble;
    }

    public String getText() {
        return text;
    }

    public void setText(final String text) {
        this.text = text;
    }

    public BigDecimal getDecimal() {
        return decimal;
    }

    public void setDecimal(final BigDecimal decimal) {
        this.decimal = decimal;
    }

    public Date getMoment() {
        return moment;
    }

    public void setMoment(final Date moment) {
        this.moment = moment;
    }

    public PropColor getColor() {
        return color;
    }

    public void setColor(final PropColor color) {
        this.color = color;
    }

    public Class<?> getClassName() {
        return className;
    }

    public void setClassName(final Class<?> className) {
        this.className = className;
    }

    public String getMaybeNull() {
        return maybeNull;
    }

    public void setMaybeNull(final String maybeNull) {
        this.maybeNull = maybeNull;
    }
}
