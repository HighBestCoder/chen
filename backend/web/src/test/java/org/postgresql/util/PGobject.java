package org.postgresql.util;

/** Test stand-in for the PostgreSQL driver type, which chen loads at runtime from its drivers directory. */
public class PGobject {
    private String value;
    public void setValue(String value) { this.value = value; }
    public String getValue() { return value; }
    @Override public String toString() { return getValue(); }
}
