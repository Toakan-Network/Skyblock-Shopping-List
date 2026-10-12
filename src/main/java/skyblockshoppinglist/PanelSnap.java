package skyblockshoppinglist;

/**
 * Snapping for Move HUD: a dragged panel's left edge, centre or right edge (or top, middle, bottom) jumps to a
 * nearby line, such as a screen edge, the screen centre or another panel's edge. One axis at a time.
 */
final class PanelSnap {
    /** How close (GUI pixels) an edge must come to a line to snap to it. */
    static final int DISTANCE = 6;

    /** Where the panel ends up, and the line it snapped to ({@code line} is -1 when it didn't snap). */
    record Result(int pos, int line) {}

    private PanelSnap() {}

    /**
     * Snaps a panel at {@code pos} of size {@code size} on one axis to the closest of {@code lines}, by whichever of
     * its start, middle or end is nearest one, if within {@link #DISTANCE}.
     */
    static Result snap(int pos, int size, int[] lines) {
        int[] anchors = {pos, pos + size / 2, pos + size};
        int best = DISTANCE + 1;
        int shift = 0;
        int line = -1;
        for (int anchor : anchors) {
            for (int target : lines) {
                int d = Math.abs(target - anchor);
                if (d < best) {
                    best = d;
                    shift = target - anchor;
                    line = target;
                }
            }
        }
        return new Result(pos + shift, line);
    }
}
