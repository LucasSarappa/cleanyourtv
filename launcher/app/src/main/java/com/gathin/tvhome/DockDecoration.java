package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row, or the tiles' reflections, and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** config.json dock_glass: a frosted, see-through band with a bright rim instead of the dark one. */
    private final boolean glass;
    /** dock_band false: no band at all, the tiles float over the wallpaper. */
    private final boolean drawBand;
    /** dock_reflection: each dock tile mirrored under itself, fading out. */
    private final boolean reflection;
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    private final Path clip = new Path();
    /** Room under the dock for the reflections, in dp. */
    private static final int REFLECTION_ROOM_DP = 44;
    private float shaderTop = Float.NaN;
    private float shaderBottom = Float.NaN;
    private float fadeTop = Float.NaN;

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     * @param style       Appearance.FLOATING (no band), GLASS (frosted band) or BAND (the dark band).
     * @param reflection  mirror the dock tiles under themselves.
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, String style, boolean reflection) {
        this.dockSize = dockSize;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.glass = Appearance.GLASS.equals(style);
        this.drawBand = !Appearance.FLOATING.equals(style);
        this.reflection = reflection;
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        band.setColor(translucent ? 0x8C121014 : 0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(glass ? 1.5f * density : density);
        edge.setColor(glass ? 0x66FFFFFF : translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    /** The glass gradients depend on where the band is; rebuilt only when it moves, not on every frame. */
    private void glassShaders(RectF r) {
        if (r.top == shaderTop && r.bottom == shaderBottom) return;
        shaderTop = r.top;
        shaderBottom = r.bottom;
        band.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x47FFFFFF, 0x1FFFFFFF, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.4f, 0x33FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            // With reflections the gap under the dock holds them, and pushes the next row off the screen: the dock
            // is parked from fixed offsets (MainActivity), so the row that used to peek in now starts below the edge.
            outRect.bottom = (int) ((reflection ? 12 + REFLECTION_ROOM_DP : 12) * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        if (drawBand) drawBand(c, parent);
        if (!reflection) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null || card.getHeight() == 0) continue;
            drawReflection(c, card, child.getLeft() + card.getLeft(),
                    child.getTop() + card.getTop() + child.getTranslationY() + card.getHeight());
        }
    }

    private void drawBand(Canvas c, RecyclerView parent) {
        float pad = 14 * density;
        // The band can only draw inside the grid, and the first and last dock tiles sit 12dp in from its
        // edges: a full 14dp at the sides sliced the rounded ends flat (#454). Keep the sides, with the
        // 1dp edge line, inside the grid; the top and bottom have room.
        float side = Math.max(0f, Math.min(pad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        rect.inset(-side, -pad);
        rect.bottom += namesRoom;
        float radius = (glass ? 34 : 26) * density;
        if (glass) glassShaders(rect);
        c.drawRoundRect(rect, radius, radius, band);
        if (glass) c.drawRoundRect(rect, radius, radius, sheen);
        c.drawRoundRect(rect, radius, radius, edge);
    }

    /**
     * The tile upside down under itself, the top third of it fading to nothing. Drawn into a layer the size of the
     * reflection only, so the fade (DST_IN) never touches the wallpaper around it.
     */
    private void drawReflection(Canvas c, View card, float left, float bottom) {
        float gap = 5 * density;
        float top = bottom + gap;
        float height = Math.min(card.getHeight() * 0.34f, (REFLECTION_ROOM_DP - 4) * density);
        if (top != fadeTop) {
            fadeTop = top;
            // Gone well before the layer's edge, so the reflection never ends in a line.
            fade.setShader(new LinearGradient(0, top, 0, top + height, new int[]{0x38FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.8f}, Shader.TileMode.CLAMP));
        }
        int saved = c.saveLayer(left, top, left + card.getWidth(), top + height, null);
        c.save();
        // View.draw does not apply the tile's rounded outline, so round the mirrored corners here.
        float radius = AppAdapter.cornerRadius(card.getWidth(), density);
        clip.reset();
        clip.addRoundRect(left, top, left + card.getWidth(), top + card.getHeight(), radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        c.translate(left, top + card.getHeight());
        c.scale(1f, -1f);
        card.draw(c);
        c.restore();
        // DST_IN only touches the pixels it covers: overshoot the layer, whose edges are rounded out to whole pixels,
        // so its last row is faded too instead of showing the tile at full strength as a thin line.
        c.drawRect(left - 2, top - 2, left + card.getWidth() + 2, top + height + 2 * density, fade);
        c.restoreToCount(saved);
    }
}
