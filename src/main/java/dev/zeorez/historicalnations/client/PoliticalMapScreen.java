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
import dev.zeorez.historicalnations.map.MapNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

    private static final double MIN_ZOOM = 0.005D; // Fits entire 73,728 x 36,864 Earth 1:500 even at GUI Scale 4
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

    // Right-click Town Info / OP Coordinate Teleport Popup state
    private UUID popupTownId;
    private boolean coordPopupOpen = false;
    private int popupCoordX, popupCoordZ;
    private int popupX, popupY, popupW, popupH;
    private int popupHeaderH = 18;
    private int viewTownBtnX, viewTownBtnY, viewTownBtnW, viewTownBtnH;
    private int evalCityBtnY;
    private int teleportBtnX, teleportBtnY, teleportBtnW, teleportBtnH;

    // Clickable Nation labels & Active War Flag icons rendered in the current frame
    private record LabelHitBox(int x1, int y1, int x2, int y2, UUID nationId) {}
    private record FlagHitBox(int x1, int y1, int x2, int y2, UUID flagId) {}
    private final List<LabelHitBox> nationLabelBoxes = new ArrayList<>();
    private final List<FlagHitBox> flagHitBoxes = new ArrayList<>();
    private UUID selectedFlagId;
    private int flagPopupX, flagPopupY, flagPopupW, flagPopupH;
    private int peaceBtnY = -1;
    private UUID popupWarId;

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

    public static void openInMode(TerritoryConfig.MapMode mode, Screen returnTo) {
        openWithFocus(FocusKind.WAR_MODE, null, 0, 0, returnTo);
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PoliticalMapScreen s) {
            s.mapMode = mode;
            s.settingsApplied = true;
            s.rebuildWidgets();
        }
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
        ClientMapState.INSTANCE.tiles.resetTransientState();
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
        clampCamera();
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
                coordPopupOpen = false;
                popupCoordX = t.homeBlockX();
                popupCoordZ = t.homeBlockZ();
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
            case WAR_MODE -> {
                mapMode = TerritoryConfig.MapMode.WAR;
                settingsApplied = true;
                if (mc.player != null && !initializedCamera) {
                    centerX = mc.player.getX();
                    centerZ = mc.player.getZ();
                    pixelsPerBlock = 0.25D;
                    initializedCamera = true;
                }
                pendingFocus = FocusKind.NONE;
                rebuildWidgets();
            }
            default -> {}
        }
        clampCamera();
    }

    @Override
    protected void init() {
        if (!initializedCamera && minecraft != null && minecraft.player != null && pendingFocus == FocusKind.NONE) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
            initializedCamera = true;
        }
        clampCamera();

        int x = 6;
        int y = 4;
        int h = 18;
        int gap = 3;

        // 1. Political Map
        x = addBarBtn(x, y, btnWidth(tr("mode_political"), 62), h, tr("mode_political"), mapMode == TerritoryConfig.MapMode.POLITICAL, () -> {
            mapMode = TerritoryConfig.MapMode.POLITICAL;
            rebuildWidgets();
        }) + gap;

        // 2. Terrain Map
        x = addBarBtn(x, y, btnWidth(tr("mode_terrain"), 60), h, tr("mode_terrain"), mapMode == TerritoryConfig.MapMode.TERRAIN, () -> {
            mapMode = TerritoryConfig.MapMode.TERRAIN;
            rebuildWidgets();
        }) + gap;

        // 3. War Map Mode (v11.0.0)
        x = addBarBtn(x, y, btnWidth(tr("mode_war"), 60), h, tr("mode_war"), mapMode == TerritoryConfig.MapMode.WAR, () -> {
            mapMode = TerritoryConfig.MapMode.WAR;
            rebuildWidgets();
        }) + gap + 4;

        // 4. Nation Borders
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
            clampCamera();
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
        clampCamera();
    }

    private double clampZoom(double z) {
        return Math.max(minAllowedZoom(), Math.min(MAX_ZOOM, z));
    }

    private double minAllowedZoom() {
        ClientMapState state = ClientMapState.INSTANCE;
        if (!state.settings().lockCameraToBounds() || width <= 0 || height <= TOP_BAR_H + BOTTOM_BAR_H) {
            return MIN_ZOOM;
        }
        MapCalibration cal = state.calibration();
        double spanX = Math.max(16.0D, (cal.eastBlockX() + 1.0D) - cal.westBlockX());
        double spanZ = Math.max(16.0D, (cal.southBlockZ() + 1.0D) - cal.northBlockZ());
        int viewH = Math.max(1, height - TOP_BAR_H - BOTTOM_BAR_H);
        double coverZoom = Math.max((double) width / spanX, (double) viewH / spanZ);
        return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, coverZoom));
    }

    /**
     * Strictly locks both zoom and camera center inside the configured Earth map bounds so
     * out-of-bounds void space can never enter the viewport.
     */
    private void clampCamera() {
        if (width <= 0 || height <= TOP_BAR_H + BOTTOM_BAR_H) return;
        pixelsPerBlock = clampZoom(pixelsPerBlock);
        ClientMapState state = ClientMapState.INSTANCE;
        if (!state.settings().lockCameraToBounds()) return;

        MapCalibration cal = state.calibration();
        double mapMinX = cal.westBlockX();
        double mapMaxX = cal.eastBlockX() + 1.0D;
        double mapMinZ = cal.northBlockZ();
        double mapMaxZ = cal.southBlockZ() + 1.0D;

        int viewH = Math.max(1, height - TOP_BAR_H - BOTTOM_BAR_H);
        double halfW = (width * 0.5D) / pixelsPerBlock;
        double halfH = (viewH * 0.5D) / pixelsPerBlock;

        double minCx = mapMinX + halfW;
        double maxCx = mapMaxX - halfW;
        centerX = minCx <= maxCx
                ? Math.max(minCx, Math.min(maxCx, centerX))
                : (mapMinX + mapMaxX) * 0.5D;

        double minCz = mapMinZ + halfH;
        double maxCz = mapMaxZ - halfH;
        centerZ = minCz <= maxCz
                ? Math.max(minCz, Math.min(maxCz, centerZ))
                : (mapMinZ + mapMaxZ) * 0.5D;
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
        flagHitBoxes.clear();

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

        clampCamera();
        MapCalibration cal = state.calibration();
        boolean locked = state.settings().lockCameraToBounds();

        int minBlockX = (int) Math.floor(screenToWorldX(0)) - 16;
        int maxBlockX = (int) Math.ceil(screenToWorldX(width)) + 16;
        int minBlockZ = (int) Math.floor(screenToWorldZ(viewTop)) - 16;
        int maxBlockZ = (int) Math.ceil(screenToWorldZ(viewBottom)) + 16;
        if (locked) {
            minBlockX = Math.max(cal.westBlockX(), minBlockX);
            maxBlockX = Math.min(cal.eastBlockX(), maxBlockX);
            minBlockZ = Math.max(cal.northBlockZ(), minBlockZ);
            maxBlockZ = Math.min(cal.southBlockZ(), maxBlockZ);
        }

        int clipX0 = locked ? Math.max(0, Math.round(worldToScreenX(cal.westBlockX()))) : 0;
        int clipY0 = locked ? Math.max(viewTop, Math.round(worldToScreenY(cal.northBlockZ()))) : viewTop;
        int clipX1 = locked ? Math.min(width, Math.round(worldToScreenX(cal.eastBlockX() + 1))) : width;
        int clipY1 = locked ? Math.min(viewBottom, Math.round(worldToScreenY(cal.southBlockZ() + 1))) : viewBottom;

        g.enableScissor(clipX0, clipY0, Math.max(clipX0, clipX1), Math.max(clipY0, clipY1));

        // LAYER 1: Terrain Layer (streamed multi-LOD tiles + nautical strategic graticule)
        renderTerrainLayer(g, state, minBlockX, minBlockZ, maxBlockX, maxBlockZ);

        List<RegionMesh> visibleMeshes = state.visibleRegions(minBlockX, minBlockZ, maxBlockX, maxBlockZ);

        // LAYER 2: Political Layer (sovereign & independent territory fills)
        renderPoliticalLayer(g, state, visibleMeshes);

        // LAYER 2B: War Occupation & Active Capture Flag Layer (v11.0.0)
        renderWarOverlayLayer(g, state, minBlockX, minBlockZ, maxBlockX, maxBlockZ);

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

        // Search dropdown & Right-Click Town Info / OP Teleport Card
        renderTopAndBottomChrome(g, mx, my, state);
        super.render(g, mx, my, partialTick);
        renderSearchDropdown(g, mx, my);
        renderTownPopup(g, mx, my, state);
        renderCoordPopup(g, mx, my, state);
        renderFlagPopup(g, mx, my, state);
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
        if (tileSpan <= 2500) {
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
                    if (slice != null) {
                        blitTileQuad(pose, slice.texture(), sx0, sy0, sx1, sy1, slice.u0(), slice.v0(), slice.u1(), slice.v1());
                    } else if (lod > 0) {
                        blitLoadedChildrenFallback(pose, state, lod, tx, tz, wx0, wz0, bpt);
                    }
                }
            }
            RenderSystem.disableBlend();
        }

        // Warm planetary root z7 tiles with any remaining request budget so World View is always ready
        state.tiles.preloadWorldOverview(cal);

        // World border outline
        if (bx1 - bx0 >= 4 && bz1 - bz0 >= 4) {
            drawClippedRectOutline(g, bx0, bz0, bx1, bz1, 1, 0x5571654C);
        }
    }

    private static void blitTileQuad(Matrix4f pose, ResourceLocation texture,
                                     float sx0, float sy0, float sx1, float sy1,
                                     float u0, float v0, float u1, float v1) {
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.vertex(pose, sx0, sy1, 0f).uv(u0, v1).endVertex();
        bb.vertex(pose, sx1, sy1, 0f).uv(u1, v1).endVertex();
        bb.vertex(pose, sx1, sy0, 0f).uv(u1, v0).endVertex();
        bb.vertex(pose, sx0, sy0, 0f).uv(u0, v0).endVertex();
        Tesselator.getInstance().end();
    }

    /**
     * When zooming out from Regional View into World View before a coarser LOD tile finishes streaming,
     * renders any already-cached child tiles (lod-1 or lod-2) so terrain never disappears during zoom.
     */
    private void blitLoadedChildrenFallback(Matrix4f pose, ClientMapState state, int lod, int tx, int tz,
                                            double wx0, double wz0, int bpt) {
        for (int step = 1; step <= 2 && lod - step >= MapCalibration.MIN_LOD; step++) {
            int childLod = lod - step;
            int grid = 1 << step;
            double childBpt = (double) bpt / grid;
            int baseChildTx = tx * grid;
            int baseChildTz = tz * grid;
            boolean drewAny = false;
            for (int dz = 0; dz < grid; dz++) {
                double cwz0 = wz0 + dz * childBpt;
                float csy0 = worldToScreenY(cwz0);
                float csy1 = worldToScreenY(cwz0 + childBpt);
                if (csy1 < TOP_BAR_H || csy0 > height - BOTTOM_BAR_H) continue;
                for (int dx = 0; dx < grid; dx++) {
                    double cwx0 = wx0 + dx * childBpt;
                    float csx0 = worldToScreenX(cwx0);
                    float csx1 = worldToScreenX(cwx0 + childBpt);
                    if (csx1 < 0 || csx0 > width) continue;
                    ResourceLocation tex = state.tiles.peekLoaded(childLod, baseChildTx + dx, baseChildTz + dz);
                    if (tex != null) {
                        blitTileQuad(pose, tex, csx0, csy0, csx1, csy1, 0f, 0f, 1f, 1f);
                        drewAny = true;
                    }
                }
            }
            if (drewAny) return;
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

    private void renderPoliticalLayer(GuiGraphics g, ClientMapState state, List<RegionMesh> meshes) {
        boolean terrainMode = mapMode == TerritoryConfig.MapMode.TERRAIN;
        boolean warMode = mapMode == TerritoryConfig.MapMode.WAR;
        int baseAlpha = terrainMode ? 0x1E : 0x78; // Semi-transparent so terrain geography shines through
        int minPx = pixelsPerBlock < 0.12D ? 2 : 1;

        for (RegionMesh rm : meshes) {
            for (FillRect rect : rm.fills) {
                boolean isHighlighted = (highlightedNationId != null && highlightedNationId.equals(rect.nationId()))
                        || (highlightedTownId != null && highlightedTownId.equals(rect.townId()));
                if (terrainMode && !isHighlighted) continue;

                int x0 = Math.round(worldToScreenX(rect.minBlockX()));
                int y0 = Math.round(worldToScreenY(rect.minBlockZ()));
                int x1 = Math.max(x0 + minPx, Math.round(worldToScreenX(rect.maxBlockX())));
                int y1 = Math.max(y0 + minPx, Math.round(worldToScreenY(rect.maxBlockZ())));
                if (x1 < 0 || x0 > width || y1 < TOP_BAR_H || y0 > height - BOTTOM_BAR_H) continue;

                int alpha;
                int rgb = rect.colorRgb() & 0xFFFFFF;
                if (isHighlighted) {
                    alpha = 0xB4;
                } else if (warMode) {
                    if (state.isNationAtWar(rect.nationId())) {
                        alpha = 0x8E;
                    } else {
                        alpha = 0x2E;
                        rgb = darken(rgb, 36);
                    }
                } else {
                    alpha = baseAlpha;
                }
                int argb = (alpha << 24) | rgb;
                g.fill(x0, y0, x1, y1, argb);
            }
        }
    }

    /**
     * Renders occupied chunks (diagonal stripes in Occupying Nation color + dashed occupation border)
     * and active War Capture Flags (pulsing crimson zone + clickable battle icon).
     */
    private void renderWarOverlayLayer(GuiGraphics g, ClientMapState state, int minBx, int minBz, int maxBx, int maxBz) {
        if (!state.enableOccupationOverlay() && mapMode != TerritoryConfig.MapMode.WAR) return;
        int minCx = minBx >> 4;
        int maxCx = maxBx >> 4;
        int minCz = minBz >> 4;
        int maxCz = maxBz >> 4;
        int minPx = pixelsPerBlock < 0.12D ? 2 : 1;

        // 1. Occupied Chunks (diagonal hatching + dashed border so occupation != permanent annexation)
        for (var occ : state.occupations()) {
            if (occ.chunkX() < minCx || occ.chunkX() > maxCx || occ.chunkZ() < minCz || occ.chunkZ() > maxCz) continue;
            int bx0 = occ.chunkX() * 16;
            int bz0 = occ.chunkZ() * 16;
            int x0 = Math.round(worldToScreenX(bx0));
            int y0 = Math.round(worldToScreenY(bz0));
            int x1 = Math.max(x0 + minPx, Math.round(worldToScreenX(bx0 + 16)));
            int y1 = Math.max(y0 + minPx, Math.round(worldToScreenY(bz0 + 16)));
            if (x1 < 0 || x0 > width || y1 < TOP_BAR_H || y0 > height - BOTTOM_BAR_H) continue;

            NationSummary occNat = state.nation(occ.occupyingNationId());
            int occRgb = occNat != null ? occNat.colorRgb() : 0xD4553E;
            int stripeArgb = 0xCC000000 | brighten(occRgb, 20);

            int spanW = x1 - x0;
            int spanH = y1 - y0;
            if (spanW >= 5 && spanH >= 5) {
                // Diagonal stepped hatching stripes inside the occupied chunk
                int step = Math.max(3, Math.min(6, spanW / 3));
                for (int py = y0; py < y1; py++) {
                    for (int px = x0; px < x1; px++) {
                        if (Math.floorMod((px - x0) + (py - y0), step) < 2) {
                            g.fill(px, py, px + 1, py + 1, stripeArgb);
                        }
                    }
                }
                // Dashed outer occupation border where adjacent chunk is not occupied by the same nation
                int dashArgb = 0xEE000000 | brighten(occRgb, 48);
                var north = state.occupationAtChunk(occ.chunkX(), occ.chunkZ() - 1);
                var south = state.occupationAtChunk(occ.chunkX(), occ.chunkZ() + 1);
                var west = state.occupationAtChunk(occ.chunkX() - 1, occ.chunkZ());
                var east = state.occupationAtChunk(occ.chunkX() + 1, occ.chunkZ());
                if (north == null || !Objects.equals(north.occupyingNationId(), occ.occupyingNationId())) {
                    drawDashedHorizontal(g, x0, x1, y0, dashArgb);
                }
                if (south == null || !Objects.equals(south.occupyingNationId(), occ.occupyingNationId())) {
                    drawDashedHorizontal(g, x0, x1, y1 - 1, dashArgb);
                }
                if (west == null || !Objects.equals(west.occupyingNationId(), occ.occupyingNationId())) {
                    drawDashedVertical(g, x0, y0, y1, dashArgb);
                }
                if (east == null || !Objects.equals(east.occupyingNationId(), occ.occupyingNationId())) {
                    drawDashedVertical(g, x1 - 1, y0, y1, dashArgb);
                }
            } else {
                g.fill(x0, y0, x1, y1, stripeArgb);
            }
        }

        // 2. Active Capture Flags (crimson frontline highlight + clickable battle icon)
        boolean pulse = ((System.currentTimeMillis() / 450L) & 1L) == 0L;
        for (var flag : state.activeFlags()) {
            int cbx = flag.chunkX() * 16;
            int cbz = flag.chunkZ() * 16;
            int cx0 = Math.round(worldToScreenX(cbx));
            int cy0 = Math.round(worldToScreenY(cbz));
            int cx1 = Math.max(cx0 + 2, Math.round(worldToScreenX(cbx + 16)));
            int cy1 = Math.max(cy0 + 2, Math.round(worldToScreenY(cbz + 16)));
            if (cx1 >= 0 && cx0 <= width && cy1 >= TOP_BAR_H && cy0 <= height - BOTTOM_BAR_H) {
                g.fill(cx0, cy0, cx1, cy1, pulse ? 0x55FF3B3B : 0x33FF3B3B);
                drawClippedRectOutline(g, cx0, cy0, cx1, cy1, 1, pulse ? 0xFFFF5E5E : 0xFFCC3333);
            }

            int fx = Math.round(worldToScreenX(flag.blockX() + 0.5D));
            int fy = Math.round(worldToScreenY(flag.blockZ() + 0.5D));
            if (fx < -12 || fx > width + 12 || fy < TOP_BAR_H - 12 || fy > height - BOTTOM_BAR_H + 12) continue;

            int half = 6;
            g.fill(fx - half, fy - half, fx + half + 1, fy + half + 1, 0xF01C1414);
            g.renderOutline(fx - half, fy - half, half * 2 + 1, half * 2 + 1,
                    Objects.equals(selectedFlagId, flag.flagId()) ? GOLD : 0xFFFF5E5E);
            g.drawCenteredString(font, "⚔", fx, fy - 4, pulse ? 0xFFFF7A7A : GOLD);

            // Mini capture progress bar directly below the icon
            int barW = 14;
            int fillW = Math.round((flag.progressPct() / 100.0F) * barW);
            g.fill(fx - barW / 2, fy + half + 2, fx + barW / 2, fy + half + 4, 0xCC111618);
            if (fillW > 0) {
                g.fill(fx - barW / 2, fy + half + 2, fx - barW / 2 + fillW, fy + half + 4, 0xFFFF5252);
            }
            flagHitBoxes.add(new FlagHitBox(fx - half - 2, fy - half - 2, fx + half + 2, fy + half + 5, flag.flagId()));
        }
    }

    private static void drawDashedHorizontal(GuiGraphics g, int x0, int x1, int y, int argb) {
        for (int x = x0; x < x1; x += 4) {
            g.fill(x, y, Math.min(x1, x + 2), y + 1, argb);
        }
    }

    private static void drawDashedVertical(GuiGraphics g, int x, int y0, int y1, int argb) {
        for (int y = y0; y < y1; y += 4) {
            g.fill(x, y, x + 1, Math.min(y1, y + 2), argb);
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
        var focused = state.label(highlightedTownId);
        if (focused != null && s.showTownNames() && drawTownLabel(g, state, focused, occupied, true)) placed++;

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

        if (!s.showTownNames()) return;
        int minimum = pixelsPerBlock < 0.10 ? 5 : pixelsPerBlock < 0.75 ? 3 : 1;
        for (var label : state.labels()) {
            if (placed >= maxLabels || label.town().level() < minimum) break;
            if (label != focused && drawTownLabel(g, state, label, occupied, false)) placed++;
        }
    }

    private static final int[] LEVEL_COLORS = {0, 0xffa5aaad, 0xffb8deb0, 0xffead69b, 0xffffb55a, 0xffffdc60};
    private boolean drawTownLabel(GuiGraphics g, ClientMapState state, ClientMapState.TownLabel label,
                                  List<ScreenBox> occupied, boolean focused) {
        var t = label.town();
        int sx = Math.round(worldToScreenX(t.homeBlockX())), sy = Math.round(worldToScreenY(t.homeBlockZ()));
        if (sx < 0 || sx > width || sy < TOP_BAR_H || sy >= height - BOTTOM_BAR_H - 15) return false;
        float scale = 0.58f + t.level() * 0.13f;
        int lw = font.width(label.text()), tw = Math.round((lw+8)*scale);
        int left = Math.max(2,Math.min(width-tw-2,sx-tw/2)), top = sy+5;
        var box = new ScreenBox(left, top-2, left+tw, top+(int)Math.ceil(11*scale));
        if (collides(box, occupied)) return false;
        occupied.add(box);
        g.fill(sx-2,sy-2,sx+2,sy+2,isCapital(state,t)?GOLD:LEVEL_COLORS[t.level()]);
        g.pose().pushPose();
        g.pose().translate(left+tw/2f,top,0); g.pose().scale(scale,scale,1);
        g.fill(-lw/2-3,-1,(lw+1)/2+3,10,focused?0xee3a4038:0x99151b1c);
        if (t.level()>=4) {
            int radius=t.level()==5?2:1;
            for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++)
                if(dx!=0 || dy!=0) g.drawString(font,label.text(),-lw/2+dx*radius,dy*radius,0xff322819,false);
        }
        g.drawString(font,label.text(),-lw/2,0,LEVEL_COLORS[t.level()],t.level()>1 && t.level()<4);
        if(focused) g.fill(-lw/2-2,9,(lw+1)/2+2,10,GOLD);
        g.pose().popPose();
        return true;
    }

    private void renderPlayerLocationLayer(GuiGraphics g, ClientMapState state) {
        if (markerBlockX != null && markerBlockZ != null && highlightTicks > 0) {
            int mx = Math.round(worldToScreenX(markerBlockX));
            int my = Math.round(worldToScreenY(markerBlockZ));
            drawClippedRectOutline(g, mx - 6, my - 6, mx + 7, my + 7, 1, GOLD);
            g.fill(mx - 1, my - 9, mx + 2, my + 10, 0xAAFFE082);
            g.fill(mx - 9, my - 1, mx + 10, my + 2, 0xAAFFE082);
        }

        if (coordPopupOpen || popupTownId != null) {
            int rx = Math.round(worldToScreenX(popupCoordX + 0.5D));
            int ry = Math.round(worldToScreenY(popupCoordZ + 0.5D));
            if (rx >= -8 && rx <= width + 8 && ry >= TOP_BAR_H - 8 && ry <= height - BOTTOM_BAR_H + 8) {
                drawClippedRectOutline(g, rx - 4, ry - 4, rx + 5, ry + 5, 1, GOLD);
                g.fill(rx - 1, ry - 6, rx + 2, ry + 7, 0xBBFFE082);
                g.fill(rx - 6, ry - 1, rx + 7, ry + 2, 0xBBFFE082);
            }
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
        var occ = state.occupationAtChunk(popupCoordX >> 4, popupCoordZ >> 4);
        String lineOccNation = null;
        String lineOccStatus = null;
        String lineOccWar = null;
        popupWarId = null;
        if (occ != null) {
            NationSummary occNat = state.nation(occ.occupyingNationId());
            String occNatName = occNat != null ? occNat.name() : "?";
            lineOccNation = Component.translatable("hn.map.popup_occupying_nation", occNatName).getString();
            lineOccStatus = Component.translatable("hn.map.popup_occ_status").getString();
            lineOccWar = Component.translatable("hn.map.popup_war_info", occ.warTitle()).getString();
            popupWarId = occ.warId();
            contentW = Math.max(contentW, Math.max(font.width(lineOccNation), font.width(lineOccWar)) + 16);
        }

        popupW = Math.min(256, contentW);
        boolean canEval = minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(4);
        boolean canTp = isOpPlayer();
        boolean showPeaceBtn = popupWarId != null;
        int extraButtons = (canEval ? 1 : 0) + (canTp ? 1 : 0) + (showPeaceBtn ? 1 : 0);
        int occExtraH = occ != null ? 38 : 0;
        popupH = 148 + occExtraH + extraButtons * 20;
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
        g.drawString(font, font.plainSubstrByWidth(townTitle, popupW - 12), clampedX + 6, ty, LEVEL_COLORS[town.level()], false);
        ty += 13;
        g.fill(clampedX + 6, ty - 2, clampedX + popupW - 6, ty - 1, 0x5571654C);
        g.drawString(font, font.plainSubstrByWidth(lineMayor, popupW - 12), clampedX + 6, ty + 1, CREAM, false);
        ty += 12;
        g.drawString(font, font.plainSubstrByWidth(linePop, popupW - 12), clampedX + 6, ty + 1, 0xFFC7D3D4, false);
        ty += 12;
        g.drawString(font, font.plainSubstrByWidth(lineLand, popupW - 12), clampedX + 6, ty + 1, 0xFFC7D3D4, false);
        ty += 12;
        g.drawString(font, font.plainSubstrByWidth(lineStatus, popupW - 12), clampedX + 6, ty + 1, MUTED, false);

        Component development = Component.translatable("hn.panel.level_"+town.level());
        Component score = town.score()<0 ? Component.translatable("hn.panel.unevaluated") : Component.literal(town.score()+"/100");
        Component rating = town.rating()==0 ? Component.translatable("hn.panel.unrated")
                : Component.literal(dev.zeorez.historicalnations.core.CityEvaluation.stars(town.rating()));
        g.drawString(font, Component.translatable("hn.panel.map_level",development,town.level()), clampedX+6, ty+13, CREAM,false);
        g.drawString(font, Component.translatable("hn.panel.map_score",score), clampedX+6, ty+25, CREAM,false);
        g.drawString(font, Component.translatable("hn.panel.final_rating",rating), clampedX+6, ty+37, GOLD,false);

        if (occ != null) {
            int oy = ty + 50;
            g.fill(clampedX + 6, oy - 2, clampedX + popupW - 6, oy - 1, 0x66C44536);
            g.drawString(font, font.plainSubstrByWidth(lineOccStatus, popupW - 12), clampedX + 6, oy + 1, 0xFFFF7A6B, false);
            g.drawString(font, font.plainSubstrByWidth(lineOccNation, popupW - 12), clampedX + 6, oy + 13, GOLD, false);
            g.drawString(font, font.plainSubstrByWidth(lineOccWar, popupW - 12), clampedX + 6, oy + 25, MUTED, false);
        }

        // [VIEW TOWN] button
        viewTownBtnX = clampedX + 6;
        viewTownBtnY = clampedY + popupH - (21 + extraButtons * 20);
        viewTownBtnW = popupW - 12;
        viewTownBtnH = 16;
        boolean hoverBtn = mx >= viewTownBtnX && mx < viewTownBtnX + viewTownBtnW
                && my >= viewTownBtnY && my < viewTownBtnY + viewTownBtnH;
        g.fill(viewTownBtnX, viewTownBtnY, viewTownBtnX + viewTownBtnW, viewTownBtnY + viewTownBtnH,
                hoverBtn ? 0xFF425150 : 0xFF2B3538);
        g.renderOutline(viewTownBtnX, viewTownBtnY, viewTownBtnW, viewTownBtnH, hoverBtn ? GOLD : BORDER);
        g.drawCenteredString(font, viewBtnLabel, viewTownBtnX + viewTownBtnW / 2, viewTownBtnY + 4, GOLD);

        int nextBtnY = viewTownBtnY + 20;
        peaceBtnY = -1;
        if (showPeaceBtn) {
            peaceBtnY = nextBtnY;
            boolean hovPeace = mx >= viewTownBtnX && mx < viewTownBtnX + viewTownBtnW
                    && my >= peaceBtnY && my < peaceBtnY + 16;
            g.fill(viewTownBtnX, peaceBtnY, viewTownBtnX + viewTownBtnW, peaceBtnY + 16,
                    hovPeace ? 0xFF4B3735 : 0xFF392826);
            g.renderOutline(viewTownBtnX, peaceBtnY, viewTownBtnW, 16, hovPeace ? GOLD : 0xFFB85A4F);
            g.drawCenteredString(font, Component.translatable("hn.war.peace_btn"),
                    viewTownBtnX + viewTownBtnW / 2, peaceBtnY + 4, GOLD);
            nextBtnY += 20;
        }
        evalCityBtnY = -1;
        if (canEval) {
            evalCityBtnY = nextBtnY;
            boolean hovEval = mx >= viewTownBtnX && mx < viewTownBtnX + viewTownBtnW
                    && my >= evalCityBtnY && my < evalCityBtnY + 16;
            g.fill(viewTownBtnX, evalCityBtnY, viewTownBtnX + viewTownBtnW, evalCityBtnY + 16,
                    hovEval ? 0xFF56503C : 0xFF474232);
            g.renderOutline(viewTownBtnX, evalCityBtnY, viewTownBtnW, 16, hovEval ? GOLD : BORDER);
            g.drawCenteredString(font, Component.translatable("hn.panel.evaluate_city"),
                    viewTownBtnX + viewTownBtnW / 2, evalCityBtnY + 4, GOLD);
            nextBtnY += 20;
        }
        if (canTp) {
            teleportBtnX = viewTownBtnX;
            teleportBtnY = nextBtnY;
            teleportBtnW = viewTownBtnW;
            teleportBtnH = 16;
            boolean hovTp = mx >= teleportBtnX && mx < teleportBtnX + teleportBtnW
                    && my >= teleportBtnY && my < teleportBtnY + teleportBtnH;
            g.fill(teleportBtnX, teleportBtnY, teleportBtnX + teleportBtnW, teleportBtnY + teleportBtnH,
                    hovTp ? 0xFF2D4A3E : 0xFF21362E);
            g.renderOutline(teleportBtnX, teleportBtnY, teleportBtnW, teleportBtnH, hovTp ? 0xFF63E2B7 : GOLD);
            g.drawCenteredString(font, tr("teleport_here"),
                    teleportBtnX + teleportBtnW / 2, teleportBtnY + 4, 0xFF8FF0CA);
        } else {
            teleportBtnW = 0;
            teleportBtnH = 0;
        }
    }

    private void renderCoordPopup(GuiGraphics g, int mx, int my, ClientMapState state) {
        if (!coordPopupOpen || popupTownId != null) return;
        if (!isOpPlayer()) {
            coordPopupOpen = false;
            return;
        }

        String header = tr("coord_header").getString().toUpperCase(Locale.ROOT);
        String lineCoords = String.format(Locale.ROOT, "X: %d   Z: %d", popupCoordX, popupCoordZ);
        String geo = state.calibration().formatGeo(popupCoordX, popupCoordZ);
        String lineSub = String.format(Locale.ROOT, "Chunk: [%d, %d]%s",
                popupCoordX >> 4, popupCoordZ >> 4, geo.isEmpty() ? "" : " • " + geo);
        String tpLabel = tr("teleport_here").getString();

        int contentW = Math.max(158, Math.max(
                font.width(header) + 16,
                Math.max(font.width(lineCoords) + 16, Math.max(font.width(lineSub) + 16, font.width(tpLabel) + 28))
        ));
        popupW = Math.min(250, contentW);
        popupH = 70;
        popupHeaderH = 18;

        int clampedX = Math.max(8, Math.min(width - popupW - 8, popupX));
        int clampedY = Math.max(TOP_BAR_H + 6, Math.min(height - BOTTOM_BAR_H - popupH - 6, popupY));
        popupX = clampedX;
        popupY = clampedY;

        g.fill(clampedX + 3, clampedY + 3, clampedX + popupW + 3, clampedY + popupH + 3, 0x88000000);
        g.fill(clampedX, clampedY, clampedX + popupW, clampedY + popupH, CARD_BG);
        g.fill(clampedX + 1, clampedY + 1, clampedX + popupW - 1, clampedY + popupHeaderH, 0xFF233036);
        g.fill(clampedX + 1, clampedY + popupHeaderH, clampedX + popupW - 1, clampedY + popupHeaderH + 1, GOLD);
        g.renderOutline(clampedX, clampedY, popupW, popupH, BORDER);

        g.drawString(font, font.plainSubstrByWidth(header, popupW - 12), clampedX + 6, clampedY + 5, GOLD, true);
        g.drawString(font, font.plainSubstrByWidth(lineCoords, popupW - 12), clampedX + 6, clampedY + popupHeaderH + 5, CREAM, false);
        g.drawString(font, font.plainSubstrByWidth(lineSub, popupW - 12), clampedX + 6, clampedY + popupHeaderH + 17, MUTED, false);

        teleportBtnX = clampedX + 6;
        teleportBtnY = clampedY + popupH - 21;
        teleportBtnW = popupW - 12;
        teleportBtnH = 16;
        boolean hovTp = mx >= teleportBtnX && mx < teleportBtnX + teleportBtnW
                && my >= teleportBtnY && my < teleportBtnY + teleportBtnH;
        g.fill(teleportBtnX, teleportBtnY, teleportBtnX + teleportBtnW, teleportBtnY + teleportBtnH,
                hovTp ? 0xFF2D4A3E : 0xFF21362E);
        g.renderOutline(teleportBtnX, teleportBtnY, teleportBtnW, teleportBtnH, hovTp ? 0xFF63E2B7 : GOLD);
        g.drawCenteredString(font, tpLabel, teleportBtnX + teleportBtnW / 2, teleportBtnY + 4, 0xFF8FF0CA);
    }

    private void renderFlagPopup(GuiGraphics g, int mx, int my, ClientMapState state) {
        if (selectedFlagId == null) return;
        dev.zeorez.historicalnations.war.WarModels.MapFlagOverlay flag = null;
        for (var f : state.activeFlags()) {
            if (f.flagId().equals(selectedFlagId)) {
                flag = f;
                break;
            }
        }
        if (flag == null) {
            selectedFlagId = null;
            return;
        }

        NationSummary attNat = state.nation(flag.attackingNationId());
        NationSummary defNat = state.nation(flag.defendingNationId());
        TownSummary targetTown = state.town(flag.targetTownId());

        String header = tr("flag_popup_header").getString().toUpperCase(Locale.ROOT);
        String lineAtt = Component.translatable("hn.map.flag_attacker", attNat != null ? attNat.name() : "?").getString();
        String lineDef = Component.translatable("hn.map.flag_defender",
                defNat != null ? defNat.name() : tr("independent_header").getString()).getString();
        String lineTown = Component.translatable("hn.map.flag_town", targetTown != null ? targetTown.name() : "?").getString();
        String lineChunk = Component.translatable("hn.map.flag_chunk", flag.chunkX(), flag.chunkZ()).getString();
        String lineProg = Component.translatable("hn.map.flag_progress", flag.progressPct(), flag.runState().name()).getString();
        String mmss = String.format(Locale.ROOT, "%02d:%02d", flag.remainingSeconds() / 60, flag.remainingSeconds() % 60);
        String lineRem = Component.translatable("hn.map.flag_remaining", mmss).getString();

        int maxLine = Math.max(font.width(header), Math.max(font.width(lineAtt),
                Math.max(font.width(lineDef), Math.max(font.width(lineTown), font.width(lineProg)))));
        flagPopupW = Math.min(250, Math.max(176, maxLine + 16));
        flagPopupH = 102;

        int cx = Math.max(8, Math.min(width - flagPopupW - 8, flagPopupX));
        int cy = Math.max(TOP_BAR_H + 6, Math.min(height - BOTTOM_BAR_H - flagPopupH - 6, flagPopupY));
        flagPopupX = cx;
        flagPopupY = cy;

        g.fill(cx + 3, cy + 3, cx + flagPopupW + 3, cy + flagPopupH + 3, 0x88000000);
        g.fill(cx, cy, cx + flagPopupW, cy + flagPopupH, CARD_BG);
        g.fill(cx + 1, cy + 1, cx + flagPopupW - 1, cy + 18, 0xFF471E1E);
        g.fill(cx + 1, cy + 18, cx + flagPopupW - 1, cy + 19, 0xFFFF5E5E);
        g.renderOutline(cx, cy, flagPopupW, flagPopupH, BORDER);

        g.drawString(font, font.plainSubstrByWidth(header, flagPopupW - 12), cx + 6, cy + 5, GOLD, true);
        int ty = cy + 23;
        g.drawString(font, font.plainSubstrByWidth(lineAtt, flagPopupW - 12), cx + 6, ty, 0xFFFF8F80, false);
        g.drawString(font, font.plainSubstrByWidth(lineDef, flagPopupW - 12), cx + 6, ty + 12, CREAM, false);
        g.drawString(font, font.plainSubstrByWidth(lineTown, flagPopupW - 12), cx + 6, ty + 24, CREAM, false);
        g.drawString(font, font.plainSubstrByWidth(lineChunk, flagPopupW - 12), cx + 6, ty + 36, MUTED, false);
        g.drawString(font, font.plainSubstrByWidth(lineProg, flagPopupW - 12), cx + 6, ty + 48, GOLD, false);
        g.drawString(font, font.plainSubstrByWidth(lineRem, flagPopupW - 12), cx + 6, ty + 60, 0xFF8FF0CA, false);
    }

    private boolean isOpPlayer() {
        return minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    private void triggerOpTeleport(int targetX, int targetZ) {
        if (!isOpPlayer()) return;
        MapNetwork.CHANNEL.sendToServer(new MapNetwork.MapTeleportPacket(targetX, targetZ));
        popupTownId = null;
        coordPopupOpen = false;
        onClose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 1a. Check Unclaimed Coordinate OP Teleport Popup clicks
        if (coordPopupOpen && popupTownId == null && button == 0
                && mx >= popupX && mx < popupX + popupW
                && my >= popupY && my < popupY + popupH) {
            if (isOpPlayer() && teleportBtnW > 0
                    && mx >= teleportBtnX && mx < teleportBtnX + teleportBtnW
                    && my >= teleportBtnY && my < teleportBtnY + teleportBtnH) {
                triggerOpTeleport(popupCoordX, popupCoordZ);
            }
            return true;
        }

        // 1b. Check Town Info Popup clicks first so it stays crisp and interactive
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
                if (peaceBtnY > 0 && popupWarId != null
                        && mx >= viewTownBtnX && mx < viewTownBtnX + viewTownBtnW
                        && my >= peaceBtnY && my < peaceBtnY + 16) {
                    PeaceTreatyScreen.requestOpen(popupWarId, this);
                    return true;
                }
                if (evalCityBtnY > 0 && minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(4)
                        && mx >= viewTownBtnX && mx < viewTownBtnX + viewTownBtnW
                        && my >= evalCityBtnY && my < evalCityBtnY + 16) {
                    CityEvaluationScreen.openTown(town.id(), this);
                    return true;
                }
                if (isOpPlayer() && teleportBtnW > 0
                        && mx >= teleportBtnX && mx < teleportBtnX + teleportBtnW
                        && my >= teleportBtnY && my < teleportBtnY + teleportBtnH) {
                    triggerOpTeleport(popupCoordX, popupCoordZ);
                    return true;
                }
                if (town.nationId() != null && my >= popupY && my < popupY + popupHeaderH) {
                    PoliticalScreen.openDossier(View.NATION, town.nationId(), this);
                    return true;
                }
            }
            return true;
        }

        if (button == 0 && selectedFlagId != null
                && mx >= flagPopupX && mx < flagPopupX + flagPopupW
                && my >= flagPopupY && my < flagPopupY + flagPopupH) {
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

        // 4. Right-click: open Town Info Popup on claimed chunk, or OP Coordinate Teleport Popup on any coordinate
        if (button == 1) {
            int wx = (int) Math.floor(screenToWorldX(mx));
            int wz = (int) Math.floor(screenToWorldZ(my));
            MapCalibration cal = ClientMapState.INSTANCE.calibration();
            if (cal.enforceWorldBounds()) {
                wx = Math.max(cal.westBlockX(), Math.min(cal.eastBlockX(), wx));
                wz = Math.max(cal.northBlockZ(), Math.min(cal.southBlockZ(), wz));
            }
            popupCoordX = wx;
            popupCoordZ = wz;
            TownSummary clicked = ClientMapState.INSTANCE.townAtBlock(wx, wz);
            if (clicked != null && ClientMapState.INSTANCE.settings().enableTownInfoPopup()) {
                popupTownId = clicked.id();
                coordPopupOpen = false;
                popupX = (int) mx + 10;
                popupY = (int) my - 10;
            } else if (isOpPlayer()) {
                popupTownId = null;
                coordPopupOpen = true;
                popupX = (int) mx + 10;
                popupY = (int) my - 10;
            } else {
                popupTownId = null;
                coordPopupOpen = false;
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
            clampCamera();
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            if (!dragMoved) {
                // Click without dragging: check Active War Flag icon first, then Nation label, otherwise dismiss popups
                for (FlagHitBox fbox : flagHitBoxes) {
                    if (mx >= fbox.x1() && mx <= fbox.x2() && my >= fbox.y1() && my <= fbox.y2()) {
                        selectedFlagId = fbox.flagId();
                        popupTownId = null;
                        coordPopupOpen = false;
                        flagPopupX = (int) mx + 10;
                        flagPopupY = (int) my - 10;
                        return true;
                    }
                }
                for (LabelHitBox box : nationLabelBoxes) {
                    if (mx >= box.x1() && mx <= box.x2() && my >= box.y1() && my <= box.y2()) {
                        PoliticalScreen.openDossier(View.NATION, box.nationId(), this);
                        return true;
                    }
                }
                popupTownId = null;
                coordPopupOpen = false;
                selectedFlagId = null;
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
            if (popupTownId != null || coordPopupOpen || selectedFlagId != null) {
                popupTownId = null;
                coordPopupOpen = false;
                selectedFlagId = null;
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
