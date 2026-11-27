package dev.zeorez.historicalnations.client;

import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.map.MapModels.FocusKind;
import dev.zeorez.historicalnations.war.WarModels.WarSide;
import dev.zeorez.historicalnations.war.WarModels.WarState;
import dev.zeorez.historicalnations.war.WarNetwork;
import dev.zeorez.historicalnations.war.WarNetwork.PeaceActionPacket;
import dev.zeorez.historicalnations.war.WarNetwork.PeaceOp;
import dev.zeorez.historicalnations.war.WarNetwork.PeaceStatePacket;
import dev.zeorez.historicalnations.war.WarNetwork.TownOption;
import dev.zeorez.historicalnations.war.WarNetwork.TreatyChunkEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 1930-1950 Diplomatic Peace Treaty Negotiation & Territorial Annexation Screen (Phase 10).
 *
 * Enforces bilateral version-locked approvals: any term adjustment increments the server treaty version
 * and clears opposing approval so neither side can sign altered terms unknowingly.
 */
public final class PeaceTreatyScreen extends Screen {
    private static final int GOLD = 0xFFE5C579;
    private static final int CREAM = 0xFFEDE6D5;
    private static final int MUTED = 0xFFA7B2B2;
    private static final int BORDER = 0xFF71654C;
    private static final int GREEN = 0xFF8FF0CA;
    private static final int RED = 0xFFFF9E8E;
    private static final int ROW_H = 20;

    private static Screen pendingReturnScreen;
    private final Screen returnScreen;
    private PeaceStatePacket state;
    private int left, top, panelW, panelH, bottom;
    private int listTop, visibleRows, scrollOffset;

    public PeaceTreatyScreen(Screen returnScreen, PeaceStatePacket initial) {
        super(Component.translatable("hn.war.peace_title"));
        this.returnScreen = returnScreen;
        this.state = initial;
    }

    public static void requestOpen(UUID warId, Screen returnTo) {
        pendingReturnScreen = returnTo;
        WarNetwork.CHANNEL.sendToServer(new PeaceActionPacket(
                warId, 0L, PeaceOp.OPEN_OR_REFRESH, null, 0, 0, null, 0));
    }

    public static void handlePacket(PeaceStatePacket pkt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof PeaceTreatyScreen existing && existing.state.warId().equals(pkt.warId())) {
            existing.state = pkt;
            existing.rebuildWidgets();
            return;
        }
        Screen ret = pendingReturnScreen;
        pendingReturnScreen = null;
        mc.setScreen(new PeaceTreatyScreen(ret, pkt));
    }

    @Override
    protected void init() {
        panelW = Math.min(760, width - 16);
        panelH = Math.min(390, height - 16);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        bottom = top + panelH;

        boolean neg = state.canNegotiate() && state.warState() != WarState.CONCLUDED;

        // Top header buttons: [War Map] and [Back]
        addStyledBtn(left + panelW - 48, top + 4, 42, 16, Component.translatable("hn.panel.back"), false, this::onClose);
        addStyledBtn(left + panelW - 134, top + 4, 82, 16, Component.translatable("hn.war.open_war_map"), false, () ->
                PoliticalMapScreen.openInMode(TerritoryConfig.MapMode.WAR, this));

        // Quick Diplomacy Controls row
        int ctrlY = top + 52;
        int x = left + 8;
        var releaseBtn = addStyledBtn(x, ctrlY, 128, 16, Component.translatable("hn.war.release_all"), false, () ->
                sendOp(PeaceOp.RELEASE_ALL, null, 0, 0, null, 0));
        releaseBtn.active = neg && !state.occupiedEntries().isEmpty();
        x += 132;

        var annexAllBtn = addStyledBtn(x, ctrlY, 136, 16, Component.translatable("hn.war.annex_all"), false, () ->
                sendOp(PeaceOp.ANNEX_ALL, null, 0, 0, null, 0));
        annexAllBtn.active = neg && !state.occupiedEntries().isEmpty();

        // Truce duration (- / + 12h) on the right of control bar
        var truceMinus = addStyledBtn(left + panelW - 56, ctrlY, 22, 16, Component.literal("−"), false, () ->
                sendOp(PeaceOp.SET_TRUCE_HOURS, null, 0, 0, null, Math.max(0, state.effectiveTruceHours() - 12)));
        truceMinus.active = neg;
        var trucePlus = addStyledBtn(left + panelW - 30, ctrlY, 22, 16, Component.literal("+"), false, () ->
                sendOp(PeaceOp.SET_TRUCE_HOURS, null, 0, 0, null, Math.min(720, state.effectiveTruceHours() + 12)));
        trucePlus.active = neg;

        listTop = top + 74;
        int footerTop = bottom - 44;
        visibleRows = Math.max(1, (footerTop - listTop) / ROW_H);
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, state.occupiedEntries().size() - visibleRows)));

        // Per-row Annex / Return toggle buttons
        List<TreatyChunkEntry> entries = state.occupiedEntries();
        for (int slot = 0; slot < visibleRows && scrollOffset + slot < entries.size(); slot++) {
            TreatyChunkEntry entry = entries.get(scrollOffset + slot);
            int ry = listTop + slot * ROW_H;
            int btnW = Math.min(200, Math.max(128, panelW / 3));
            int btnX = left + panelW - btnW - 8;
            boolean annexed = entry.recipientTownId() != null;
            Component label = annexed
                    ? Component.translatable("hn.war.term_annex", entry.recipientTownName())
                    : Component.translatable("hn.war.term_return", entry.originalTownName());
            var termBtn = addStyledBtn(btnX, ry + 2, btnW, 16, label, annexed, () -> cycleChunkTerm(entry));
            termBtn.active = neg && !entry.candidateTowns().isEmpty();
        }

        // Scroll buttons if needed
        if (entries.size() > visibleRows) {
            addStyledBtn(left + 8, bottom - 22, 26, 16, Component.literal("▲"), false, () -> {
                scrollOffset = Math.max(0, scrollOffset - 1);
                rebuildWidgets();
            });
            addStyledBtn(left + 36, bottom - 22, 26, 16, Component.literal("▼"), false, () -> {
                scrollOffset = Math.min(Math.max(0, entries.size() - visibleRows), scrollOffset + 1);
                rebuildWidgets();
            });
        }

        // Bottom Ratification / Sign Button
        if (neg && state.viewerSide() != null) {
            boolean myApproved = state.viewerSide() == WarSide.ATTACKER
                    ? state.attackerApproved() : state.defenderApproved();
            Component signLabel = myApproved
                    ? Component.translatable("hn.war.revoke_approval")
                    : Component.translatable("hn.war.approve_treaty", state.version());
            int signW = 220;
            addStyledBtn(left + panelW - signW - 8, bottom - 22, signW, 16, signLabel, myApproved, () ->
                    sendOp(PeaceOp.SET_APPROVAL, null, 0, 0, null, myApproved ? 0 : 1));
        }
    }

    private void cycleChunkTerm(TreatyChunkEntry entry) {
        List<TownOption> opts = entry.candidateTowns();
        if (opts.isEmpty()) return;
        UUID nextTown = null;
        if (entry.recipientTownId() == null) {
            nextTown = opts.get(0).townId();
        } else {
            for (int i = 0; i < opts.size(); i++) {
                if (opts.get(i).townId().equals(entry.recipientTownId())) {
                    nextTown = (i + 1 < opts.size()) ? opts.get(i + 1).townId() : null;
                    break;
                }
            }
        }
        sendOp(PeaceOp.SET_CHUNK_ANNEX, entry.dimension(), entry.chunkX(), entry.chunkZ(), nextTown, 0);
    }

    private void sendOp(PeaceOp op, net.minecraft.resources.ResourceLocation dim, int cx, int cz, UUID townId, int intArg) {
        WarNetwork.CHANNEL.sendToServer(new PeaceActionPacket(
                state.warId(), state.version(), op, dim, cx, cz, townId, intArg));
    }

    private Button addStyledBtn(int x, int y, int w, int h, Component label, boolean highlighted, Runnable action) {
        Button b = new Button(x, y, w, h, label, btn -> action.run(), s -> s.get()) {
            @Override
            protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
                int bx = getX(), by = getY();
                int bg = !active ? 0xFF222829 : isHoveredOrFocused() ? 0xFF435150 : highlighted ? 0xFF364538 : 0xFF2A3336;
                g.fill(bx, by, bx + width, by + height, bg);
                g.renderOutline(bx, by, width, height, !active ? 0xFF4A5052 : highlighted ? GREEN : (isHoveredOrFocused() ? GOLD : BORDER));
                String txt = font.plainSubstrByWidth(getMessage().getString(), Math.max(0, width - 8));
                g.drawString(font, txt, bx + (width - font.width(txt)) / 2, by + 4,
                        !active ? MUTED : highlighted ? GREEN : GOLD, false);
            }
        };
        b.setTooltip(Tooltip.create(label));
        return addRenderableWidget(b);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g);
        g.fill(left, top, left + panelW, bottom, 0xF41B2225);
        g.renderOutline(left, top, panelW, panelH, BORDER);
        g.fill(left + 1, top + 1, left + panelW - 1, top + 3, GOLD);
        g.fillGradient(left + 2, top + 3, left + panelW - 2, top + 24, 0xFF393B31, 0xFF252C2E);

        String header = Component.translatable("hn.war.peace_header", state.warTitle(), state.version()).getString();
        g.drawString(font, font.plainSubstrByWidth(header, panelW - 150), left + 10, top + 8, GOLD, false);

        // Bilateral Approval & Belligerent Summary Strip
        g.fill(left + 6, top + 27, left + panelW - 6, top + 48, 0xFF232C2F);
        g.renderOutline(left + 6, top + 27, panelW - 12, 21, 0xFF485254);

        String attBadge = (state.attackerApproved() ? "✓ " : "○ ") + state.attackerName();
        String defBadge = (state.defenderApproved() ? "✓ " : "○ ") + state.defenderName();
        g.drawString(font, attBadge, left + 12, top + 34, state.attackerApproved() ? GREEN : CREAM, false);
        g.drawCenteredString(font, state.warState().name(), left + panelW / 2, top + 34, GOLD);
        g.drawString(font, defBadge, left + panelW - font.width(defBadge) - 12, top + 34,
                state.defenderApproved() ? GREEN : CREAM, false);

        // Truce label next to +/- buttons
        String truceTxt = Component.translatable("hn.war.truce_hours", state.effectiveTruceHours()).getString();
        g.drawString(font, truceTxt, left + panelW - 62 - font.width(truceTxt), top + 56, MUTED, false);

        // Occupied Chunks List
        List<TreatyChunkEntry> entries = state.occupiedEntries();
        if (entries.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("hn.war.no_occupied_chunks"), left + panelW / 2, listTop + 18, MUTED);
        } else {
            int btnColW = Math.min(200, Math.max(128, panelW / 3)) + 16;
            for (int slot = 0; slot < visibleRows && scrollOffset + slot < entries.size(); slot++) {
                TreatyChunkEntry e = entries.get(scrollOffset + slot);
                int ry = listTop + slot * ROW_H;
                g.fill(left + 8, ry, left + panelW - 8, ry + ROW_H - 1, slot % 2 == 0 ? 0xFF263033 : 0xFF212A2D);
                g.fill(left + 8, ry, left + 10, ry + ROW_H - 1, e.recipientTownId() != null ? GOLD : 0xFF567378);
                String rowText = String.format(Locale.ROOT, "[%d, %d]  %s (%s)  →  %s",
                        e.chunkX(), e.chunkZ(), e.originalTownName(), e.originalNationName(), e.occupyingNationName());
                g.drawString(font, font.plainSubstrByWidth(rowText, panelW - btnColW - 24), left + 15, ry + 6, CREAM, false);
            }
        }

        // Footer separator & feedback
        g.fill(left + 6, bottom - 26, left + panelW - 6, bottom - 25, 0xFF4A5350);
        if (!state.feedback().getString().isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(state.feedback().getString(), panelW - 20),
                    left + 10, bottom - 39, state.ok() ? GREEN : RED, false);
        } else if (state.warState() == WarState.CONCLUDED) {
            g.drawString(font, Component.translatable("hn.war.treaty_ratified_banner"), left + 10, bottom - 39, GREEN, false);
        }

        int annexCount = (int) entries.stream().filter(e -> e.recipientTownId() != null).count();
        String countsTxt = Component.translatable("hn.war.treaty_summary_counts",
                annexCount, entries.size() - annexCount).getString();
        g.drawString(font, countsTxt, left + (entries.size() > visibleRows ? 70 : 10), bottom - 18, MUTED, false);

        super.render(g, mx, my, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (delta != 0 && state.occupiedEntries().size() > visibleRows) {
            scrollOffset = Math.max(0, Math.min(state.occupiedEntries().size() - visibleRows,
                    scrollOffset + (delta < 0 ? 1 : -1)));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
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
}
