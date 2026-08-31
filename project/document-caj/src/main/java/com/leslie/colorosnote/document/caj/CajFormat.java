package com.leslie.colorosnote.document.caj;

/**
 * Real on-disk format of an imported file, detected from magic bytes —
 * never from the file extension.
 *
 * <p>Known CAJ-family container headers seen in the wild:
 * <ul>
 *   <li>{@code CAJ} / {@code %CAJ} — CAJViewer full-text files;</li>
 *   <li>{@code HN} — CAJViewer "HN" variant (often .nh/.caj);</li>
 *   <li>{@code KDH} — KDH container;</li>
 *   <li>{@code TEB} — TEB ebook container.</li>
 * </ul>
 * Many .caj downloads are actually plain PDFs ({@code %PDF} header) or CAJ
 * containers with a complete PDF embedded inside.
 */
public enum CajFormat {
    /** Plain PDF file. */
    PDF,
    /** CAJViewer container, "CAJ" header. */
    CAJ,
    /** CAJViewer "HN" variant. */
    CAJ_HN,
    /** KDH container. */
    KDH,
    /** TEB ebook container. */
    TEB,
    /** Header matches nothing we know. */
    UNKNOWN
}
