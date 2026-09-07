package com.greenwhite.dwh.instance.search.typesense;

/** Safe adapter exception whose message never contains a downstream response or user query. */
public final class TypesenseException extends RuntimeException {

    private TypesenseException(String message) {
        super(message);
    }

    public static TypesenseException unavailable() {
        return new TypesenseException("Typesense search is unavailable");
    }

    public static TypesenseException invalidResponse() {
        return new TypesenseException("Typesense search response is invalid");
    }

    public static TypesenseException uninitialized() {
        return new TypesenseException("Typesense search is not initialized");
    }
}
