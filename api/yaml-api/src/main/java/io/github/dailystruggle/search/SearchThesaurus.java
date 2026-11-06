package io.github.dailystruggle.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java bidirectional synset, cross-lingual pivot, and cascade retrieval thesaurus ({@code :yaml-api}).
 *
 * @governedBy ADR-104, ADR-115
 */
public final class SearchThesaurus {

    private static final SearchThesaurus DEFAULT = new SearchThesaurus(
            io.github.dailystruggle.rtp.common.search.SearchThesaurus.defaultInstance());

    private final io.github.dailystruggle.rtp.common.search.SearchThesaurus delegate;

    private SearchThesaurus(io.github.dailystruggle.rtp.common.search.SearchThesaurus delegate) {
        this.delegate = delegate;
    }

    public static SearchThesaurus defaultInstance() {
        return DEFAULT;
    }

    public SearchThesaurus withCustomSynsets(Map<String, ? extends Collection<String>> customSynsets) {
        return new SearchThesaurus(delegate.withCustomSynsets(customSynsets));
    }

    public String normalizePhrases(String query) {
        return delegate.normalizePhrases(query);
    }

    public List<String> splitCompoundToken(String token) {
        return delegate.splitCompoundToken(token);
    }

    public Set<String> expandTokens(Collection<String> tokens) {
        return delegate.expandTokens(tokens);
    }

    public Set<String> expandSynset(String token) {
        return delegate.expandSynset(token);
    }
}
