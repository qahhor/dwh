package com.smartup24.cms.instance.search;

import com.smartup24.cms.instance.search.api.TypesenseProperties;
import com.smartup24.cms.instance.search.service.SearchMetrics;
import com.smartup24.cms.instance.search.typesense.TypesenseClient;
import com.smartup24.cms.instance.search.typesense.TypesenseCollections;
import com.smartup24.cms.instance.search.typesense.TypesenseDocuments;
import com.smartup24.cms.instance.search.typesense.TypesenseHealth;
import com.smartup24.cms.instance.search.typesense.TypesenseSearch;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** The Typesense concern classes over one shared transport, wired as the application context wires them. */
record TypesenseFixture(
        TypesenseClient transport,
        TypesenseCollections collections,
        TypesenseDocuments documents,
        TypesenseSearch search,
        TypesenseHealth health) {

    static TypesenseFixture of(TypesenseProperties properties, ObjectMapper mapper) {
        return of(properties, mapper, Optional.empty());
    }

    static TypesenseFixture of(TypesenseProperties properties, ObjectMapper mapper, Optional<SearchMetrics> metrics) {
        var transport = new TypesenseClient(properties, mapper);
        var collections = new TypesenseCollections(transport);
        return new TypesenseFixture(
                transport,
                collections,
                new TypesenseDocuments(transport, collections, metrics),
                new TypesenseSearch(transport),
                new TypesenseHealth(transport));
    }
}
