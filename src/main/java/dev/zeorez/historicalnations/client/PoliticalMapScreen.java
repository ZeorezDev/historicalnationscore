package dev.zeorez.historicalnations.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.client.ClientMapState.BorderSegment;
import dev.zeorez.historicalnations.client.ClientMapState.FillRect;
import dev.zeorez.historicalnations.client.ClientMapState.NationBounds;
import dev.zeorez.historicalnations.client.ClientMapState.RegionMesh;
import dev.zeorez.historicalnations.client.ClientMapState.SearchHit;
import dev.zeorez.historicalnations.client.ClientTileCache.TileSlice;
import dev.zeorez.historicalnations.gui.PanelQuery.View;
import dev.zeorez.historicalnations.map.MapCalibration;
import dev.zeorez.historicalnations.map.MapModels.FocusKind;
import dev.zeorez.historicalnations.map.MapModels.NationSummary;
import dev.zeorez.historicalnations.map.MapModels.ServerMapSettings;
import dev.zeorez.historicalnations.map.MapModels.TownSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Full-screen interactive Political World Map (1930-1950 geopolitical strategic aesthetic).
 *
 * Implements the 6 ordered map layers:
 *   1. Terrain Layer (multi-LOD streamed Anvil tiles + strategic nautical chart graticule fallback)
 *   2. Political Layer (semi-transparent sovereign & independent territory fills)
 *   3. Nation Border Layer (thick sovereign borders, omitted between towns of the same nation)
 *   4. Town Border Layer (thin municipal boundaries + close-zoom 16x16 chunk grid)
 *   5. Settlement Label Layer (zoom-adaptive collision-culled Nation & Town labels + Capital stars)
 *   6. Player Location Layer (live compass marker)
 */
public final class PoliticalMapScreen extends Screen {
    // 1930s-1950s War Room Strategic Palette
    private static final int OCEAN_BG = 0xFF13222B;
    private static final int GRATICULE_MAJOR = 0x33496675;
    private static final int GRATICULE_MINOR = 0x1A3B5461;
    private static final int TOP_BAR_BG = 0xF2171E21;
    private static final int BOTTOM_BAR_BG = 0xEB151B1E;
    private static final int CARD_BG = 0xF41B2225;
    private static final int GOLD = 0xFFE5C579;
    private static final int CREAM = 0xFFEDE6D5;
    private static final int MUTED = 0xFFA4B0B2;
    private static final int BORDER = 0xFF71654C;

    private static final double MIN_ZOOM = 0.010D; // Fits entire 73,728 x 36,864 Earth 1:500 comfortably
    private static final double MAX_ZOOM = 8.000D; // 128 px per chunk close-up
    private static final int TOP_BAR_H = 26;
    private static final int BOTTOM_BAR_H = 18;

    private final Screen returnScreen;

    // Camera state in exact Minecraft world block coordinates
    private double centerX = 0.0D;
    private double centerZ = 0.0D;
    private double pixelsPerBlock = 0.25D; // 4 px per chunk regional default
    private boolean initializedCamera = false;
    private boolean settingsApplied = false;

    // Layer toggles
    private TerritoryConfig.MapMode mapMode = TerritoryConfig.MapMode.POLITICAL;
    private boolean showNationBorders = true;
    private boolean showTownBorders = true;

    // Drag & interaction state
    private boolean dragging = false;
    private double dragStartX, dragStartY;
    private boolean dragMoved = false;

    // Pending focus target (from /map, VIEW ON MAP, or search)
    private FocusKind pendingFocus = FocusKind.NONE;
    private UUID pendingTargetId;
    private int pendingBlockX, pendingBlockZ;

    // Highlight state (search / VIEW ON MAP)
    private UUID highlightedNationId;
    private UUID highlightedTownId;
    private Integer markerBlockX, markerBlockZ;
    private int highlightTicks = 0;

    // Right-click Town Info Popup state
    private UUID popupTownId;
    private int popupX, popupY, popupW, popupH;
    private int popupHeaderH = 18;
    private int viewTownBtnX, viewTownBtnY, viewTownBtnW, viewTownBtnH;

    // Clickable Nation labels rendered in the current frame
    private record LabelHitBox(int x1, int y1, int x2, int y2, UUID nationId) {}
    private final List<LabelHitBox> nationLabelBoxes = new ArrayList<>();

    // Search UI
    private boolean searchVisible = false;
    private EditBox searchBox;
    private String searchText = "";
    private List<SearchHit> searchResults = List.of();

    public PoliticalMapScreen(Screen returnScreen) {
        super(Component.translatable("hn.map.title"));
        this.returnScreen = returnScreen;
    }

    public static void openDefault() {
        openWithFocus(FocusKind.PLAYER, null, 0, 0, null);
    }

    public static void openWithFocus(FocusKind focus, UUID targetId, int blockX, int blockZ) {
        openWithFocus(focus, targetId, blockX, blockZ, null);
    }

    public static void openWithFocus(FocusKind focus, UUID targetId, int blockX, int blockZ, Screen returnTo) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        PoliticalMapScreen screen = mc.screen instanceof PoliticalMapScreen existing
                ? existing
                : new PoliticalMapScreen(returnTo);
        screen.pendingFocus = focus;
        screen.pendingTargetId = targetId;
        screen.pendingBlockX = blockX;
        screen.pendingBlockZ = blockZ;
        if (mc.screen != screen) {
            mc.setScreen(screen);
        }
        ClientMapState.INSTANCE.requestSyncFromServer();
        screen.applyPendingFocusIfReady();
    }

    public void onMapDataUpdated(boolean fullSnapshot) {
        ServerMapSettings s = ClientMapState.INSTANCE.settings();
        if (!settingsApplied) {
            mapMode = s.defaultMode();
            showNationBorders = s.showNationBorders();
            showTownBorders = s.showTownBorders();
            settingsApplied = true;
            rebuildWidgets();
        }
        if (popupTownId != null && ClientMapState.INSTANCE.town(popupTownId) == null) {
            popupTownId = null;
        }
        if (searchVisible && !searchText.isBlank()) {
            searchResults = ClientMapState.INSTANCE.search(searchText);
        }
        applyPendingFocusIfReady();
    }

    private void applyPendingFocusIfReady() {
        if (pendingFocus == FocusKind.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        ClientMapState state = ClientMapState.INSTANCE;

        switch (pendingFocus) {
            case PLAYER -> {
                if (mc.player != null) {
                    centerX = mc.player.getX();
                    centerZ = mc.player.getZ();
                    if (!initializedCamera) {
                        pixelsPerBlock = 0.35D;
                        initializedCamera = true;
                    }
                }
                pendingFocus = FocusKind.NONE;
            }
            case COORDS -> {
                centerX = pendingBlockX;
                centerZ = pendingBlockZ;
                pixelsPerBlock = 0.75D;
                markerBlockX = pendingBlockX;
                markerBlockZ = pendingBlockZ;
                highlightTicks = 200;
                initializedCamera = true;
                pendingFocus = FocusKind.NONE;
            }
            case TOWN -> {
                TownSummary t = state.town(pendingTargetId);
                if (t == null) return; // Wait until snapshot arrives
                if (t.hasClaims()) {
                    centerX = t.homeBlockX();
                    centerZ = t.homeBlockZ();
                    pixelsPerBlock = 1.25D;
                }
                highlightedTownId = t.id();
                highlightedNationId = null;
                highlightTicks = 200;
                popupTownId = state.settings().enableTownInfoPopup() ? t.id() : null;
                popupX = width / 2 + 18;
                popupY = height / 2 - 40;
                initializedCamera = true;
                pendingFocus = FocusKind.NONE;
            }
            case NATION -> {
                NationSummary n = state.nation(pendingTargetId);
                if (n == null) return; // Wait until snapshot arrives
                NationBounds nb = state.nationBounds().get(n.id());
                if (nb != null) {
                    centerX = (nb.minBlockX() + nb.maxBlockX()) * 0.5D;
                    centerZ = (nb.minBlockZ() + nb.maxBlockZ()) * 0.5D;
                    double spanX = Math.max(128.0D, (nb.maxBlockX() - nb.minBlockX()) * 1.45D);
                    double spanZ = Math.max(128.0D, (nb.maxBlockZ() - nb.minBlockZ()) * 1.45D);
                    int viewH = Math.max(100, height - TOP_BAR_H - BOTTOM_BAR_H);
                    pixelsPerBlock = clampZoom(Math.min(width / spanX, viewH / spanZ));
                } else {
                    TownSummary cap = state.town(n.capitalTownId());
                    if (cap != null && cap.hasClaims()) {
                        centerX = cap.homeBlockX();
                        centerZ = cap.homeBlockZ();
                    }
                    pixelsPerBlock = 0.5D;
                }
                highlightedNationId = n.id();
                highlightedTownId = null;
                highlightTicks = 240;
                initializedCamera = true;
                pendingFocus = FocusKind.NONE;
            }
            default -> {}
        }
    }

    @Override
    protected void init() {
        if (!initializedCamera && minecraft != null && minecraft.player != null && pendingFocus == FocusKind.NONE) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
            initializedCamera = true;
        }

        int x = 6;
        int y = 4;
        int h = 18;
        int gap = 3;

        // 1. Political Map
        x = addBarBtn(x, y, btnWidth(tr("mode_political"), 68), h, tr("mode_political"), mapMode == TerritoryConfig.MapMode.POLITICAL, () -> {
            mapMode = TerritoryConfig.MapMode.POLITICAL;
            rebuildWidgets();
        }) + gap;

        // 2. Terrain Map
        x = addBarBtn(x, y, btnWidth(tr("mode_terrain"), 68), h, tr("mode_terrain"), mapMode == TerritoryConfig.MapMode.TERRAIN, () -> {
            mapMode = TerritoryConfig.MapMode.TERRAIN;
            rebuildWidgets();
        }) + gap + 4;

        // 3. Nation Borders
        x = addBarBtn(x, y, btnWidth(tr("nation_borders"), 74), h, tr("nation_borders"), showNationBorders, () -> {
            showNationBorders = !showNationBorders;
            rebuildWidgets();
        }) + gap;

        // 4. Town Borders
        x = addBarBtn(x, y, btnWidth(tr("town_borders"), 72), h, tr("town_borders"), showTownBorders, () -> {
            showTownBorders = !showTownBorders;
            rebuildWidgets();
        }) + gap + 4;

        // 5. My Location
        x = addBarBtn(x, y, btnWidth(tr("my_location"), 60), h, tr("my_location"), false, this::focusMyLocation) + gap;

        // 6. Search
        if (ClientMapState.INSTANCE.settings().enableSearch()) {
            x = addBarBtn(x, y, btnWidth(tr("search_btn"), 44), h, tr("search_btn"), searchVisible, () -> {
                searchVisible = !searchVisible;
                rebuildWidgets();
            }) + gap;
        }

        // Right-aligned Zoom In (+), Zoom Out (-), and Close/Back
        int right = width - 6;
        if (returnScreen != null) {
            right -= 42;
            addBarBtn(right, y, 42, h, Component.translatable("hn.panel.back"), false, this::onClose);
            right -= gap;
        }
        right -= 20;
        addBarBtn(right, y, 20, h, Component.literal("−"), false, () -> zoomAt(width * 0.5D, height * 0.5D, 1.0D / 1.45D));
        right -= (20 + gap);
        addBarBtn(right, y, 20, h, Component.literal("+"), false, () -> zoomAt(width * 0.5D, height * 0.5D, 1.45D));

        if (searchVisible && ClientMapState.INSTANCE.settings().enableSearch()) {
            int boxW = Math.min(240, Math.max(140, width - 32));
            int boxX = (width - boxW) / 2;
            int boxY = TOP_BAR_H + 5;
            searchBox = addRenderableWidget(new EditBox(font, boxX + 4, boxY + 3, boxW - 8, 14, tr("search_hint")));
            searchBox.setMaxLength(64);
            searchBox.setBordered(false);
            searchBox.setHint(tr("search_hint"));
            searchBox.setValue(searchText);
            searchBox.setResponder(val -> {
                searchText = val;
                searchResults = ClientMapState.INSTANCE.search(val);
            });
            setInitialFocus(searchBox);
            searchResults = ClientMapState.INSTANCE.search(searchText);
        } else {
            searchBox = null;
        }
    }

    private int btnWidth(Component label, int minWidth) {
        return Math.max(minWidth, font.width(label) + 12);
    }

    private int addBarBtn(int x, int y, int w, int h, Component label, boolean toggled, Runnable onClick) {
        Button btn = new Button(x, y, w, h, label, b -> onClick.run(), s -> s.get()) {
            @Override
            protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
                int bx = getX(), by = getY();
                int bg = isHoveredOrFocused() ? 0xFF3E4B4C : (toggled ? 0xFF353E35 : 0xFF242C2F);
                g.fill(bx, by, bx + width, by + height, bg);
                g.renderOutline(bx, by, width, height, toggled ? GOLD : BORDER);
                if (toggled) {
                    g.fill(bx + 1, by + height - 2, bx + width - 1, by + height - 1, GOLD);
                }
                String txt = font.plainSubstrByWidth(getMessage().getString(), Math.max(0, width - 6));
                int tx = bx + (width - font.width(txt)) / 2;
                g.drawString(font, txt, tx, by + 5, toggled ? GOLD : CREAM, false);
            }
        };
        btn.setTooltip(Tooltip.create(label));
        addRenderableWidget(btn);
        return x + w;
    }

    private void focusMyLocation() {
        if (minecraft != null && minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
            if (pixelsPerBlock < 0.25D) pixelsPerBlock = 0.5D;
        }
    }

    private void zoomAt(double screenX, double screenY, double factor) {
        double beforeWorldX = screenToWorldX(screenX);
        double beforeWorldZ = screenToWorldZ(screenY);
        pixelsPerBlock = clampZoom(pixelsPerBlock * factor);
        double afterWorldX = screenToWorldX(screenX);
        double afterWorldZ = screenToWorldZ(screenY);
        centerX += (beforeWorldX - afterWorldX);
        centerZ += (beforeWorldZ - afterWorldZ);
    }

    private static double clampZoom(double z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    private double mapCenterScreenY() {
        return TOP_BAR_H + (height - TOP_BAR_H - BOTTOM_BAR_H) * 0.5D;
    }

    private double screenToWorldX(double sx) {
        return centerX + (sx - width * 0.5D) / pixelsPerBlock;
    }

    private double screenToWorldZ(double sy) {
        return centerZ + (sy - mapCenterScreenY()) / pixelsPerBlock;
    }

    private float worldToScreenX(double wx) {
        return (float) (width * 0.5D + (wx - centerX) * pixelsPerBlock);
    }

    private float worldToScreenY(double wz) {
        return (float) (mapCenterScreenY() + (wz - centerZ) * pixelsPerBlock);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        ClientMapState state = ClientMapState.INSTANCE;
        state.tiles.beginFrame();
        nationLabelBoxes.clear();

        int viewTop = TOP_BAR_H;
        int viewBottom = height - BOTTOM_BAR_H;

        // Ocean backdrop
        g.fill(0, 0, width, height, OCEAN_BG);

        if (!state.settings().enabled()) {
            g.drawCenteredString(font, tr("disabled"), width / 2, height / 2 - 4, MUTED);
            renderTopAndBottomChrome(g, mx, my, state);
            super.render(g, mx, my, partialTick);
            return;
        }

        int minBlockX = (int) Math.floor(screenToWorldX(0)) - 16;
        int maxBlockX = (int) Math.ceil(screenToWorldX(width)) + 16;
        int minBlockZ = (int) Math.floor(screenToWorldZ(viewTop)) - 16;
        int maxBlockZ = (int) Math.ceil(screenToWorldZ(viewBottom)) + 16;

        g.enableScissor(0, viewTop, width, viewBottom);

        // LAYER 1: Terrain Layer (streamed multi-LOD tiles + nautical strategic graticule)
        renderTerrainLayer(g, state, minBlockX, minBlockZ, maxBlockX, maxBlockZ);

        List<RegionMesh> visibleMeshes = state.visibleRegions(minBlockX, minBlockZ, maxBlockX, maxBlockZ);

        // LAYER 2: Political Layer (sovereign & independent territory fills)
        renderPoliticalLayer(g, visibleMeshes);

        // LAYER 3: Nation Border Layer (thick sovereign borders)
        if (showNationBorders) {
            renderNationBorderLayer(g, visibleMeshes);
        }

        // LAYER 4: Town Border Layer (thin municipal borders + close-zoom 16x16 chunk grid)
        if (showTownBorders) {
            renderTownBorderLayer(g, visibleMeshes, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        }

        // LAYER 5: Settlement Label Layer (zoom-adaptive Nation & Town names + Capital stars)
        renderSettlementLabelLayer(g, state, minBlockX, minBlockZ, maxBlockX, maxBlockZ);

        // LAYER 6: Player Location Layer (+ coordinate search reticle if active)
        renderPlayerLocationLayer(g, state);

        g.disableScissor();

        // Search dropdown & Right-Click Town Info Card
        renderTopAndBottomChrome(g, mx, my, state);
        super.render(g, mx, my, partialTick);
        renderSearchDropdown(g, mx, my);
        renderTownPopup(g, mx, my, state);
    }

    private void renderTerrainLayer(GuiGraphics g, ClientMapState state, int minBx, int minBz, int maxBx, int maxBz) {
        // Choose LOD whose blocksPerPixel best matches 1 / pixelsPerBlock
        double desiredBpp = 1.0D / pixelsPerBlock;
        int lod = 0;
        while (lod < MapCalibration.MAX_LOD && MapCalibration.blocksPerPixel(lod + 1) <= desiredBpp * 1.15D) {
            lod++;
        }

        int bpt = MapCalibration.blocksPerTile(lod);
        int minTx = MapCalibration.blockToTile(minBx, lod);
        int maxTx = MapCalibration.blockToTile(maxBx, lod);
        int minTz = MapCalibration.blockToTile(minBz, lod);
        int maxTz = MapCalibration.blockToTile(maxBz, lod);

        // Draw subtle Earth map bounds frame
        MapCalibration cal = state.calibration();
        int bx0 = Math.round(worldToScreenX(cal.minBlockX()));
        int bz0 = Math.round(worldToScreenY(cal.minBlockZ()));
        int bx1 = Math.round(worldToScreenX(cal.maxBlockX() + 1));
        int bz1 = Math.round(worldToScreenY(cal.maxBlockZ() + 1));
        if (bx1 > bx0 && bz1 > bz0) {
            g.fill(Math.max(0, bx0), Math.max(TOP_BAR_H, bz0), Math.min(width, bx1), Math.min(height - BOTTOM_BAR_H, bz1), 0xFF172A36);
        }

        // Strategic graticule grid (so unrendered ocean or non-generated areas always look clean and calibrated)
        renderGraticule(g, minBx, minBz, maxBx, maxBz);

        // Stream & blit visible tiles
        long tileSpan = (long) (maxTx - minTx + 1) * (long) (maxTz - minTz + 1);
        if (tileSpan <= 600) {
            Matrix4f pose = g.pose().last().pose();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

            for (int tz = minTz; tz <= maxTz; tz++) {
                double wz0 = (double) tz * bpt;
                double wz1 = wz0 + bpt;
                float sy0 = worldToScreenY(wz0);
                float sy1 = worldToScreenY(wz1);
                if (sy1 < TOP_BAR_H || sy0 > height - BOTTOM_BAR_H) continue;

                for (int tx = minTx; tx <= maxTx; tx++) {
                    double wx0 = (double) tx * bpt;
                    double wx1 = wx0 + bpt;
                    float sx0 = worldToScreenX(wx0);
                    float sx1 = worldToScreenX(wx1);
                    if (sx1 < 0 || sx0 > width) continue;

                    TileSlice slice = state.tiles.resolveOrRequest(lod, tx, tz);
                    if (slice == null) continue;

                    RenderSystem.setShaderTexture(0, slice.texture());
                    BufferBuilder bb = Tesselator.getInstance().getBuilder();
                    bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                    bb.vertex(pose, sx0, sy1, 0f).uv(slice.u0(), slice.v1()).endVertex();
                    bb.vertex(pose, sx1, sy1, 0f).uv(slice.u1(), slice.v1()).endVertex();
                    bb.vertex(pose, sx1, sy0, 0f).uv(slice.u1(), slice.v0()).endVertex();
                    bb.vertex(pose, sx0, sy0, 0f).uv(slice.u0(), slice.v0()).endVertex();
                    Tesselator.getInstance().end();
                }
            }
            RenderSystem.disableBlend();
        }

        // World border outline
        if (bx1 - bx0 >= 4 && bz1 - bz0 >= 4) {
            drawClippedRectOutline(g, bx0, bz0, bx1, bz1, 1, 0x5571654C);
        }
    }

    private void renderGraticule(GuiGraphics g, int minBx, int minBz, int maxBx, int maxBz) {
        int stepBlocks;
        if (pixelsPerBlock < 0.03D) stepBlocks = 8192;
        else if (pixelsPerBlock < 0.12D) stepBlocks = 2048;
        else if (pixelsPerBlock < 0.50D) stepBlocks = 512;
        else stepBlocks = 128;

        int startX = Math.floorDiv(minBx, stepBlocks) * stepBlocks;
        for (int wx = startX; wx <= maxBx; wx += stepBlocks) {
            int sx = Math.round(worldToScreenX(wx));
            if (sx >= 0 && sx < width) {
                int col = (wx == 0) ? 0x448A7B56 : ((wx % (stepBlocks * 4) == 0) ? GRATICULE_MAJOR : GRATICULE_MINOR);
                g.fill(sx, TOP_BAR_H, sx + 1, height - BOTTOM_BAR_H, col);
            }
        }
        int startZ = Math.floorDiv(minBz, stepBlocks) * stepBlocks;
        for (int wz = startZ; wz <= maxBz; wz += stepBlocks) {
            int sy = Math.round(worldToScreenY(wz));
            if (sy >= TOP_BAR_H && sy < height - BOTTOM_BAR_H) {
                int col = (wz == 0) ? 0x448A7B56 : ((wz % (stepBlocks * 4) == 0) ? GRATICULE_MAJOR : GRATICULE_MINOR);
                g.fill(0, sy, width, sy + 1, col);
            }
        }
    }

    private void renderPoliticalLayer(GuiGraphics g, List<RegionMesh> meshes) {
        boolean terrainMode = mapMode == TerritoryConfig.MapMode.TERRAIN;
        int baseAlpha = terrainMode ? 0x1E : 0x78; // Semi-transparent so terrain geography shines through

        for (RegionMesh rm : meshes) {
            for (FillRect rect : rm.fills) {
                boolean isHighlighted = (highlightedNationId != null && highlightedNationId.equals(rect.nationId()))
                        || (highlightedTownId != null && highlightedTownId.equals(rect.townId()));
                if (terrainMode && !isHighlighted) continue;

                int x0 = Math.round(worldToScreenX(rect.minBlockX()));
                int y0 = Math.round(worldToScreenY(rect.minBlockZ()));
                int x1 = Math.max(x0 + 1, Math.round(worldToScreenX(rect.maxBlockX())));
                int y1 = Math.max(y0 + 1, Math.round(worldToScreenY(rect.maxBlockZ())));
                if (x1 < 0 || x0 > width || y1 < TOP_BAR_H || y0 > height - BOTTOM_BAR_H) continue;

                int alpha = isHighlighted ? 0xB4 : baseAlpha;
                int argb = (alpha << 24) | (rect.colorRgb() & 0xFFFFFF);
                g.fill(x0, y0, x1, y1, argb);
            }
        }
    }

    private void renderNationBorderLayer(GuiGraphics g, List<RegionMesh> meshes) {
        int thickness = pixelsPerBlock >= 0.30D ? 2 : 1;
        for (RegionMesh rm : meshes) {
            for (BorderSegment seg : rm.nationBorders) {
                int x0 = Math.round(worldToScreenX(seg.x1()));
                int y0 = Math.round(worldToScreenY(seg.z1()));
                int x1 = Math.round(worldToScreenX(seg.x2()));
                int y1 = Math.round(worldToScreenY(seg.z2()));

                boolean highlighted = highlightedNationId != null && highlightedNationId.equals(seg.nationId());
                int borderRgb = highlighted ? 0xFFF3D98B : brighten(seg.colorRgb(), 28);
                int stroke = 0xEE000000 | borderRgb;

                if (seg.z1() == seg.z2()) {
                    int minX = Math.min(x0, x1);
                    int maxX = Math.max(minX + 1, Math.max(x0, x1));
                    if (maxX < 0 || minX > width || y0 < TOP_BAR_H - 2 || y0 > height - BOTTOM_BAR_H + 2) continue;
                    g.fill(minX, y0 - thickness / 2, maxX, y0 - thickness / 2 + thickness, stroke);
                } else {
                    int minY = Math.min(y0, y1);
                    int maxY = Math.max(minY + 1, Math.max(y0, y1));
                    if (x0 < -2 || x0 > width + 2 || maxY < TOP_BAR_H || minY > height - BOTTOM_BAR_H) continue;
                    g.fill(x0 - thickness / 2, minY, x0 - thickness / 2 + thickness, maxY, stroke);
                }
            }
        }
    }

    private void renderTownBorderLayer(GuiGraphics g, List<RegionMesh> meshes, int minBx, int minBz, int maxBx, int maxBz) {
        // Close-zoom 16x16 chunk grid (>= 0.65 px/block -> >= 10.4 px per chunk)
        if (pixelsPerBlock >= 0.65D) {
            int startCx = minBx >> 4;
            int endCx = maxBx >> 4;
            int startCz = minBz >> 4;
            int endCz = maxBz >> 4;
            if ((endCx - startCx) <= 220 && (endCz - startCz) <= 160) {
                for (int cx = startCx; cx <= endCx; cx++) {
                    int sx = Math.round(worldToScreenX(cx * 16));
                    if (sx >= 0 && sx < width) g.fill(sx, TOP_BAR_H, sx + 1, height - BOTTOM_BAR_H, 0x1EEDE6D5);
                }
                for (int cz = startCz; cz <= endCz; cz++) {
                    int sy = Math.round(worldToScreenY(cz * 16));
                    if (sy >= TOP_BAR_H && sy < height - BOTTOM_BAR_H) g.fill(0, sy, width, sy + 1, 0x1EEDE6D5);
                }
            }
        }

        // Municipal boundary lines (rendered at Regional & Close zoom, or when zoomed >= 0.08 px/block)
        if (pixelsPerBlock < 0.08D) return;
        for (RegionMesh rm : meshes) {
            for (BorderSegment seg : rm.townBorders) {
                int x0 = Math.round(worldToScreenX(seg.x1()));
                int y0 = Math.round(worldToScreenY(seg.z1()));
                int x1 = Math.round(worldToScreenX(seg.x2()));
                int y1 = Math.round(worldToScreenY(seg.z2()));
                int stroke = 0xAA000000 | darken(seg.colorRgb(), 24);

                if (seg.z1() == seg.z2()) {
                    int minX = Math.min(x0, x1);
                    int maxX = Math.max(minX + 1, Math.max(x0, x1));
                    if (maxX < 0 || minX > width || y0 < TOP_BAR_H || y0 > height - BOTTOM_BAR_H) continue;
                    g.fill(minX, y0, maxX, y0 + 1, stroke);
                } else {
                    int minY = Math.min(y0, y1);
                    int maxY = Math.max(minY + 1, Math.max(y0, y1));
                    if (x0 < 0 || x0 > width || maxY < TOP_BAR_H || minY > height - BOTTOM_BAR_H) continue;
                    g.fill(x0, minY, x0 + 1, maxY, stroke);
                }
            }
        }
    }

    private record ScreenBox(int x1, int y1, int x2, int y2) {
        boolean intersects(ScreenBox o) {
            return x1 < o.x2 && x2 > o.x1 && y1 < o.y2 && y2 > o.y1;
        }
    }

    private void renderSettlementLabelLayer(GuiGraphics g, ClientMapState state, int minBx, int minBz, int maxBx, int maxBz) {
        ServerMapSettings s = state.settings();
        int maxLabels = s.maxVisibleLabels();
        List<ScreenBox> occupied = new ArrayList<>();
        int placed = 0;

        // 1. Nation Names (World & Regional views; subtle header at Close view)
        if (s.showNationNames() && pixelsPerBlock <= 1.8D) {
            Map<UUID, NationBounds> nbMap = state.nationBounds();
            List<NationSummary> orderedNations = new ArrayList<>(state.nations());
            orderedNations.sort(Comparator.comparingInt(NationSummary::claimCount).reversed());

            for (NationSummary n : orderedNations) {
                if (placed >= maxLabels) break;
                NationBounds nb = nbMap.get(n.id());
                if (nb == null) continue;
                // In very far World View, hide tiny 1-chunk micro-nations only if overcrowded via collision
                int sx = Math.round(worldToScreenX(nb.labelBlockX()));
                int sy = Math.round(worldToScreenY(nb.labelBlockZ()));
                if (sx < 20 || sx > width - 20 || sy < TOP_BAR_H + 10 || sy > height - BOTTOM_BAR_H - 10) continue;

                String text = n.name().toUpperCase(Locale.ROOT);
                float scale = pixelsPerBlock < 0.15D ? 1.15f : 1.0f;
                int tw = Math.round(font.width(text) * scale) + 8;
                int th = Math.round(10 * scale) + 4;
                ScreenBox box = new ScreenBox(sx - tw / 2, sy - th / 2, sx + tw / 2, sy + th / 2);
                if (collides(box, occupied)) continue;

                occupied.add(box);
                placed++;
                nationLabelBoxes.add(new LabelHitBox(box.x1(), box.y1(), box.x2(), box.y2(), n.id()));

                g.pose().pushPose();
                g.pose().translate(sx, sy - (4 * scale), 0f);
                g.pose().scale(scale, scale, 1f);
                int labelW = font.width(text);
                g.fill(-labelW / 2 - 3, -2, labelW / 2 + 3, 9, 0x9912181B);
                g.fill(-labelW / 2 - 3, 8, labelW / 2 + 3, 9, 0xCC000000 | n.colorRgb());
                g.drawString(font, text, -labelW / 2, 0, CREAM, true);
                g.pose().popPose();
            }
        }

        // 2. Town Markers, Capital Emblems & Town Names (Regional & Close views)
        if (s.showTownNames() && pixelsPerBlock >= 0.10D) {
            List<TownSummary> visibleTowns = new ArrayList<>();
            for (TownSummary t : state.towns()) {
                if (!t.hasClaims()) continue;
                if (t.homeBlockX() < minBx - 64 || t.homeBlockX() > maxBx + 64
                        || t.homeBlockZ() < minBz - 64 || t.homeBlockZ() > maxBz + 64) {
                    continue;
                }
                visibleTowns.add(t);
            }
            // Capitals first, then higher claim count, then population
            visibleTowns.sort((a, b) -> {
                boolean capA = isCapital(state, a);
                boolean capB = isCapital(state, b);
                if (capA != capB) return capA ? -1 : 1;
                int c = Integer.compare(b.claimCount(), a.claimCount());
                return c != 0 ? c : Integer.compare(b.population(), a.population());
            });

            for (TownSummary t : visibleTowns) {
                if (placed >= maxLabels) break;
                boolean capital = isCapital(state, t);
                // At outer regional zoom (0.10..0.22), prioritize capitals and larger settlements
                if (pixelsPerBlock < 0.20D && !capital && t.claimCount() < 3 && visibleTowns.size() > 25) {
                    continue;
                }

                int sx = Math.round(worldToScreenX(t.homeBlockX()));
                int sy = Math.round(worldToScreenY(t.homeBlockZ()));
                if (sx < 8 || sx > width - 8 || sy < TOP_BAR_H + 8 || sy > height - BOTTOM_BAR_H - 8) continue;

                // Draw town dot / capital badge
                if (capital) {
                    g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFF141A1C);
                    g.fill(sx - 2, sy - 2, sx + 3, sy + 3, GOLD);
                } else {
                    g.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF141A1C);
                    g.fill(sx - 1, sy - 1, sx + 1, sy + 1, CREAM);
                }

                String name = (capital ? "★ " : "") + t.name().toUpperCase(Locale.ROOT);
                float scale = pixelsPerBlock >= 0.75D ? 0.90f : 0.78f;
                int tw = Math.round(font.width(name) * scale) + 6;
                int th = Math.round(9 * scale) + 2;
                ScreenBox box = new ScreenBox(sx - tw / 2, sy + 4, sx + tw / 2, sy + 4 + th);
                if (collides(box, occupied)) continue;

                occupied.add(box);
                placed++;

                g.pose().pushPose();
                g.pose().translate(sx, sy + 5, 0f);
                g.pose().scale(scale, scale, 1f);
                int lw = font.width(name);
                g.fill(-lw / 2 - 2, -1, lw / 2 + 2, 9, 0x88141B1E);
                g.drawString(font, name, -lw / 2, 0, capital ? GOLD : 0xFFDAD5C8, true);
                g.pose().popPose();
            }
        }
    }

    private void renderPlayerLocationLayer(GuiGraphics g, ClientMapState state) {
        if (markerBlockX != null && markerBlockZ != null && highlightTicks > 0) {
            int mx = Math.round(worldToScreenX(markerBlockX));
            int my = Math.round(worldToScreenY(markerBlockZ));
            drawClippedRectOutline(g, mx - 6, my - 6, mx + 7, my + 7, 1, GOLD);
            g.fill(mx - 1, my - 9, mx + 2, my + 10, 0xAAFFE082);
            g.fill(mx - 9, my - 1, mx + 10, my + 2, 0xAAFFE082);
        }

        if (minecraft == null || minecraft.player == null) return;
        if (!minecraft.player.level().dimension().location().equals(state.calibration().dimension())) return;

        int px = Math.round(worldToScreenX(minecraft.player.getX()));
        int py = Math.round(worldToScreenY(minecraft.player.getZ()));
        if (px < -10 || px > width + 10 || py < TOP_BAR_H - 10 || py > height - BOTTOM_BAR_H + 10) return;

        // Crisp tactical player locator emblem
        g.fill(px - 4, py - 4, px + 5, py + 5, 0xFF101619);
        g.renderOutline(px - 4, py - 4, 9, 9, GOLD);
        g.fill(px - 2, py - 2, px + 3, py + 3, 0xFF63E2B7);
    }

    private void renderTopAndBottomChrome(GuiGraphics g, int mx, int my, ClientMapState state) {
        // Top toolbar background
        g.fill(0, 0, width, TOP_BAR_H, TOP_BAR_BG);
        g.fill(0, TOP_BAR_H - 1, width, TOP_BAR_H, BORDER);

        // Bottom telemetry bar
        int by = height - BOTTOM_BAR_H;
        g.fill(0, by, width, height, BOTTOM_BAR_BG);
        g.fill(0, by, width, by + 1, BORDER);

        int wx = (int) Math.floor(screenToWorldX(mx));
        int wz = (int) Math.floor(screenToWorldZ(my));
        int cx = wx >> 4;
        int cz = wz >> 4;

        String tier = pixelsPerBlock < 0.12D ? tr("zoom_world").getString()
                : pixelsPerBlock < 0.75D ? tr("zoom_regional").getString()
                : tr("zoom_close").getString();
        String geo = state.calibration().formatGeo(wx, wz);
        String leftInfo = String.format(Locale.ROOT, "X: %d  Z: %d  [%d, %d]%s  •  1:%d  •  %s",
                wx, wz, cx, cz,
                geo.isEmpty() ? "" : "  (" + geo + ")",
                state.calibration().scale(),
                tier);
        g.drawString(font, leftInfo, 8, by + 5, CREAM, false);

        // Hovered territory indicator on the right
        if (my >= TOP_BAR_H && my < by) {
            TownSummary hovered = state.townAtChunk(cx, cz);
            String rightInfo;
            int rightCol = MUTED;
            if (hovered != null) {
                NationSummary nat = state.nation(hovered.nationId());
                rightInfo = nat != null ? (nat.name() + " — " + hovered.name()) : hovered.name();
                rightCol = GOLD;
            } else {
                rightInfo = tr("unclaimed_land").getString();
            }
            g.drawString(font, rightInfo, width - font.width(rightInfo) - 8, by + 5, rightCol, false);
        }

        // Search box frame if open
        if (searchBox != null) {
            int bx = searchBox.getX() - 4;
            int sy = searchBox.getY() - 3;
            int bw = searchBox.getWidth() + 8;
            g.fill(bx, sy, bx + bw, sy + 18, 0xF0182023);
            g.renderOutline(bx, sy, bw, 18, GOLD);
        }
    }

    private void renderSearchDropdown(GuiGraphics g, int mx, int my) {
        if (searchBox == null || searchText.isBlank()) return;
        int bx = searchBox.getX() - 4;
        int by = searchBox.getY() + 16;
        int bw = searchBox.getWidth() + 8;

        if (searchResults.isEmpty()) {
            g.fill(bx, by, bx + bw, by + 18, CARD_BG);
            g.renderOutline(bx, by, bw, 18, BORDER);
            g.drawString(font, tr("no_results"), bx + 6, by + 5, MUTED, false);
            return;
        }

        int rowH = 18;
        int totalH = searchResults.size() * rowH + 2;
        g.fill(bx, by, bx + bw, by + totalH, CARD_BG);
        g.renderOutline(bx, by, bw, totalH, BORDER);

        for (int i = 0; i < searchResults.size(); i++) {
            SearchHit hit = searchResults.get(i);
            int ry = by + 1 + i * rowH;
            boolean hov = mx >= bx && mx < bx + bw && my >= ry && my < ry + rowH;
            if (hov) g.fill(bx + 1, ry, bx + bw - 1, ry + rowH, 0xFF334042);
            String badge = switch (hit.kind()) {
                case NATION -> "[N] ";
                case TOWN -> "[T] ";
                default -> "[⌖] ";
            };
            String left = font.plainSubstrByWidth(badge + hit.primary(), bw - 80);
            g.drawString(font, left, bx + 5, ry + 5, hit.kind() == FocusKind.NATION ? GOLD : CREAM, false);
            if (!hit.secondary().isEmpty()) {
                String right = font.plainSubstrByWidth(hit.secondary(), 72);
                g.drawString(font, right, bx + bw - font.width(right) - 5, ry + 5, MUTED, false);
            }
        }
    }

    private void renderTownPopup(GuiGraphics g, int mx, int my, ClientMapState state) {
        if (popupTownId == null || !state.settings().enableTownInfoPopup()) return;
        TownSummary town = state.town(popupTownId);
        if (town == null) {
            popupTownId = null;
            return;
        }

        NationSummary nation = state.nation(town.nationId());
        boolean capital = nation != null && nation.capitalTownId().equals(town.id());
        String nationHeader = nation != null
                ? nation.name().toUpperCase(Locale.ROOT)
                : tr("independent_header").getString().toUpperCase(Locale.ROOT);
        int headerColor = state.territoryColor(town);

        String townTitle = (capital ? "★ " : "") + town.name().toUpperCase(Locale.ROOT);
        String lineMayor = Component.translatable("hn.map.popup_mayor", town.mayorName()).getString();
        String linePop = Component.translatable("hn.map.popup_population", town.population()).getString();
        String lineLand = Component.translatable("hn.map.popup_territory", town.claimCount()).getString();
        Component statusVal = nation == null ? tr("status_independent") : (capital ? tr("status_capital") : tr("status_member"));
        String lineStatus = Component.translatable("hn.map.popup_status", statusVal).getString();
        String viewBtnLabel = tr("view_town").getString();

        int contentW = Math.max(164, Math.max(
                font.width(nationHeader) + 16,
                Math.max(font.width(townTitle) + 16, Math.max(font.width(lineMayor), font.width(lineStatus)) + 16)
        ));
        popupW = Math.min(250, contentW);
        popupH = 112;
        popupHeaderH = 18;

        // Clamp strictly inside viewport so the card never overflows screen edges
        int clampedX = Math.max(8, Math.min(width - popupW - 8, popupX));
        int clampedY = Math.max(TOP_BAR_H + 6, Math.min(height - BOTTOM_BAR_H - popupH - 6, popupY));
        popupX = clampedX;
        popupY = clampedY;

        // Drop shadow + card body
        g.fill(clampedX + 3, clampedY + 3, clampedX + popupW + 3, clampedY + popupH + 3, 0x88000000);
        g.fill(clampedX, clampedY, clampedX + popupW, clampedY + popupH, CARD_BG);

        // Top Nation Header Bar (clickable when town belongs to a nation)
        boolean hoverHeader = nation != null
                && mx >= clampedX && mx < clampedX + popupW
                && my >= clampedY && my < clampedY + popupHeaderH;
        int bannerBg = 0xFF000000 | (hoverHeader ? brighten(headerColor, 20) : darken(headerColor, 15));
        g.fill(clampedX + 1, clampedY + 1, clampedX + popupW - 1, clampedY + popupHeaderH, bannerBg);
        g.fill(clampedX + 1, clampedY + popupHeaderH, clampedX + popupW - 1, clampedY + popupHeaderH + 1, GOLD);
        g.renderOutline(clampedX, clampedY, popupW, popupH, BORDER);

        String trimmedHeader = font.plainSubstrByWidth(nationHeader, popupW - 10);
        g.drawString(font, trimmedHeader, clampedX + 6, clampedY + 5, CREAM, true);

        int ty = clampedY + popupHeaderH + 6;
        g.drawString(font, font.plainSubstrByWidth(townTitle, popupW - 12), clampedX + 6, ty, GOLD, false);
        ty += 13;
        g.fill(clampedX + 6, ty - 2, clampedX + popupW - 6, ty - 1, 0x5571654C);
        g.drawString(font, font.plainSubstrByWidth(lineMayor, popupW - 12), clampedX + 6, ty + 1, CREAM, false);
        ty += 12;
        g.drawString(font, font.plainSubstrByWidth(linePop, popupW - 12), clampedX + 6, ty + 1, 0xFFC7D3D4, false);
        ty += 12;
        g.drawString(font, font.plainSubstrByWidth(lineLand, popupW - 12), clampedX + 6, ty + 1, 0xFFC7D3D4, false);
        ty += 12;
        g.drawString(font, font.plainSubstrByWidth(lineStatus, popupW - 12), clampedX + 6, ty + 1, MUTED, false);

        // [VIEW TOWN] button
        viewTownBtnX = clampedX + 6;
        viewTownBtnY = clampedY + popupH - 21;
        viewTownBtnW = popupW - 12;
        viewTownBtnH = 16;
        boolean hoverBtn = mx >= viewTownBtnX && mx < viewTownBtnX + viewTownBtnW
                && my >= viewTownBtnY && my < viewTownBtnY + viewTownBtnH;
        g.fill(viewTownBtnX, viewTownBtnY, viewTownBtnX + viewTownBtnW, viewTownBtnY + viewTownBtnH,
                hoverBtn ? 0xFF425150 : 0xFF2B3538);
        g.renderOutline(viewTownBtnX, viewTownBtnY, viewTownBtnW, viewTownBtnH, hoverBtn ? GOLD : BORDER);
        g.drawCenteredString(font, viewBtnLabel, viewTownBtnX + viewTownBtnW / 2, viewTownBtnY + 4, GOLD);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 1. Check Town Info Popup clicks first so it stays crisp and interactive
        if (popupTownId != null && button == 0
                && mx >= popupX && mx < popupX + popupW
                && my >= popupY && my < popupY + popupH) {
            TownSummary town = ClientMapState.INSTANCE.town(popupTownId);
            if (town != null) {
                if (mx >= viewTownBtnX && mx < viewTownBtnX + viewTownBtnW
                        && my >= viewTownBtnY && my < viewTownBtnY + viewTownBtnH) {
                    PoliticalScreen.openDossier(View.TOWN, town.id(), this);
                    return true;
                }
                if (town.nationId() != null && my >= popupY && my < popupY + popupHeaderH) {
                    PoliticalScreen.openDossier(View.NATION, town.nationId(), this);
                    return true;
                }
            }
            return true;
        }

        // 2. Check Search Dropdown clicks
        if (button == 0 && searchBox != null && !searchText.isBlank() && !searchResults.isEmpty()) {
            int bx = searchBox.getX() - 4;
            int by = searchBox.getY() + 16;
            int bw = searchBox.getWidth() + 8;
            int rowH = 18;
            if (mx >= bx && mx < bx + bw && my >= by + 1 && my < by + 1 + searchResults.size() * rowH) {
                int idx = (int) ((my - (by + 1)) / rowH);
                if (idx >= 0 && idx < searchResults.size()) {
                    activateSearchHit(searchResults.get(idx));
                    return true;
                }
            }
        }

        // 3. Toolbar & Search Box widgets
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }

        if (my < TOP_BAR_H || my >= height - BOTTOM_BAR_H) {
            return false;
        }

        // 4. Right-click: open Town Info Popup on claimed chunk, or close if unclaimed
        if (button == 1) {
            int wx = (int) Math.floor(screenToWorldX(mx));
            int wz = (int) Math.floor(screenToWorldZ(my));
            TownSummary clicked = ClientMapState.INSTANCE.townAtBlock(wx, wz);
            if (clicked != null && ClientMapState.INSTANCE.settings().enableTownInfoPopup()) {
                popupTownId = clicked.id();
                popupX = (int) mx + 10;
                popupY = (int) my - 10;
            } else {
                popupTownId = null;
            }
            return true;
        }

        // 5. Left-click: start map drag (or click Nation label on mouse release if not dragged)
        if (button == 0) {
            if (searchBox != null) searchBox.setFocused(false);
            dragging = true;
            dragMoved = false;
            dragStartX = mx;
            dragStartY = my;
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) {
            if (Math.hypot(mx - dragStartX, my - dragStartY) > 3.0D) {
                dragMoved = true;
            }
            centerX -= dx / pixelsPerBlock;
            centerZ -= dy / pixelsPerBlock;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            if (!dragMoved) {
                // Click without dragging: check Nation label click first, otherwise dismiss popup
                for (LabelHitBox box : nationLabelBoxes) {
                    if (mx >= box.x1() && mx <= box.x2() && my >= box.y1() && my <= box.y2()) {
                        PoliticalScreen.openDossier(View.NATION, box.nationId(), this);
                        return true;
                    }
                }
                popupTownId = null;
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (delta == 0.0D) return false;
        double factor = delta > 0 ? 1.25D : (1.0D / 1.25D);
        zoomAt(mx, my, factor);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (popupTownId != null) {
                popupTownId = null;
                return true;
            }
            if (searchVisible) {
                searchVisible = false;
                rebuildWidgets();
                return true;
            }
            onClose();
            return true;
        }
        if (searchBox != null && searchBox.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            if (!searchResults.isEmpty()) {
                activateSearchHit(searchResults.get(0));
            }
            return true;
        }
        // Pressing M again while not typing in search closes the map cleanly
        if ((searchBox == null || !searchBox.isFocused()) && MapClientInit.OPEN_MAP_KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void activateSearchHit(SearchHit hit) {
        searchVisible = false;
        rebuildWidgets();
        pendingFocus = hit.kind();
        pendingTargetId = hit.id();
        pendingBlockX = hit.blockX();
        pendingBlockZ = hit.blockZ();
        applyPendingFocusIfReady();
    }

    @Override
    public void tick() {
        if (searchBox != null) searchBox.tick();
        if (highlightTicks > 0) {
            highlightTicks--;
            if (highlightTicks == 0) {
                highlightedNationId = null;
                highlightedTownId = null;
                markerBlockX = null;
                markerBlockZ = null;
            }
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(returnScreen);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean isCapital(ClientMapState state, TownSummary town) {
        if (town.nationId() == null) return false;
        NationSummary n = state.nation(town.nationId());
        return n != null && Objects.equals(n.capitalTownId(), town.id());
    }

    private static boolean collides(ScreenBox candidate, List<ScreenBox> existing) {
        for (ScreenBox box : existing) {
            if (candidate.intersects(box)) return true;
        }
        return false;
    }

    private static void drawClippedRectOutline(GuiGraphics g, int x0, int y0, int x1, int y1, int w, int argb) {
        g.fill(x0, y0, x1, y0 + w, argb);
        g.fill(x0, y1 - w, x1, y1, argb);
        g.fill(x0, y0, x0 + w, y1, argb);
        g.fill(x1 - w, y0, x1, y1, argb);
    }

    private static int brighten(int rgb, int delta) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + delta);
        int gr = Math.min(255, ((rgb >> 8) & 0xFF) + delta);
        int b = Math.min(255, (rgb & 0xFF) + delta);
        return (r << 16) | (gr << 8) | b;
    }

    private static int darken(int rgb, int delta) {
        int r = Math.max(0, ((rgb >> 16) & 0xFF) - delta);
        int gr = Math.max(0, ((rgb >> 8) & 0xFF) - delta);
        int b = Math.max(0, (rgb & 0xFF) - delta);
        return (r << 16) | (gr << 8) | b;
    }

    private static Component tr(String key) {
        return Component.translatable("hn.map." + key);
    }
}
