package com.mediastudio.render;

/**
 * Where text can go on a 9:16 photo so TikTok never covers it, as fractions of the image.
 * <ul>
 *   <li>TikTok on iPhone shows only the middle ~76% of the image height (rows 0.12 to 0.88) and draws the search bar
 *       and Following / For You tabs over the top of that.</li>
 *   <li>The account name, caption ("bio") and music line grow up from the bottom; a long caption can cover a fifth
 *       of the screen.</li>
 *   <li>The like / comment / share / avatar buttons sit on the right edge, from about the middle down.</li>
 * </ul>
 * Inside TOP..BOTTOM and LEFT..RIGHT nothing is ever covered, on desktop (full image) or on a phone.
 */
final class PhoneSafeArea {
    private PhoneSafeArea() {}

    static final double TOP = 0.20;
    static final double BOTTOM = 0.655;
    static final double LEFT = 0.06;
    /** Right edge for text in the lower half, where TikTok's buttons are. */
    static final double RIGHT = 0.84;
}
