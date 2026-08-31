package com.leslie.colorosnote.feature.search;

/**
 * Implemented by the hosting activity/fragment. Opening the document, jumping
 * to the page and flashing the hit region for 2-3s (spec 22.3) are the
 * editor's job — this module only reports the target.
 */
public interface SearchHost {

    /**
     * @param documentId  target document
     * @param pageId      target page; "" when the hit is document-level
     * @param boundingBox optional hit region [left, top, right, bottom] in page
     *                    coordinates; null when the segment carries no region
     */
    void openResult(String documentId, String pageId, float[] boundingBox);
}
