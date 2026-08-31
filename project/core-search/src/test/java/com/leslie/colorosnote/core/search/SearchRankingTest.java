package com.leslie.colorosnote.core.search;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchRankingTest {

    @Test
    public void fileNameOutranksOcr() {
        assertTrue(SearchRanking.sourceWeight(SearchDocument.SourceType.FILE_NAME)
                > SearchRanking.sourceWeight(SearchDocument.SourceType.OCR));
    }

    @Test
    public void lowConfidencePenalized() {
        SearchDocument d = new SearchDocument();
        d.sourceType = SearchDocument.SourceType.OCR;
        d.lowConfidence = false;
        double good = SearchRanking.score(d, 2, 2);
        d.lowConfidence = true;
        double bad = SearchRanking.score(d, 2, 2);
        assertTrue(good > bad);
    }

    @Test
    public void partialCoverageScoresLower() {
        SearchDocument d = new SearchDocument();
        d.sourceType = SearchDocument.SourceType.TEXT_BOX;
        assertTrue(SearchRanking.score(d, 1, 3) < SearchRanking.score(d, 3, 3));
    }
}
