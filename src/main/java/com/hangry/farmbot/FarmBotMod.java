package com.hangry.farmbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class FarmBotMod implements ClientModInitializer {

    // ── State ────────────────────────────────────────────────────────────────
    public static boolean botActive = false;
    public static boolean showGui   = true;

    // Direction: true = right (strafe right / D), false = left (strafe left / A)
    private static boolean goingRight = true;

    // Timing
    private static long rowStartTime   = 0;
    private static long rowDurationMs  = 625; // ms to cross one row (adjustable in GUI)
    private static int  clickTickTimer = 0;
    private static final int CLICK_EVERY_TICKS = 2; // click every 2 ticks (~10 clicks/sec)

    // Fall detection
    private static double lastY        = 0;
    private static long   endRowTime   = 0;
    private static boolean waitingFall = false;
    private static int     jumpCooldown = 0;

    // Stats
    private static int  rowCount    = 0;
    private static long startTime   = 0;
    private static int  clickCount  = 0;

    // GUI drag
    private static int guiX = 10, guiY = 10;
    private static boolean dragging = false;
    private static int dragOffX, dragOffY;

    // GUI sliders
    public static float rowDurationSlider = 0.625f; // seconds
    public static float clickSpeedSlider  = 0.10f;  // seconds between clicks

    // Keybind: G to toggle GUI, H to toggle bot
    private static KeyBinding toggleGuiKey;
    private static KeyBinding toggleBotKey;

    @Override
    public void onInitializeClient() {
        toggleGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "FarmBot"
        ));
        toggleBotKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "FarmBot"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudLayerRegistrationCallback.EVENT.register(layeredDraw ->
            layeredDraw.attachAbove(IdentifiedLayer.MISC_OVERLAYS,
                Identifier.of("farmbot", "hud"),
                (context, tickDelta) -> renderHud(context, tickDelta.getTickDelta(true))
            )
        );
    }

    // ── Tick ─────────────────────────────────────────────────────────────────
    private void onTick(MinecraftClient client) {
        if (client.player == null) return;

        // Keybinds
        while (toggleGuiKey.wasPressed()) showGui = !showGui;
        while (toggleBotKey.wasPressed()) {
            botActive = !botActive;
            if (botActive) {
                startBot(client);
            } else {
                stopBot(client);
            }
        }

        if (!botActive || client.player == null) return;

        // ── Movement ──────────────────────────────────────────────────────────
        long now = System.currentTimeMillis();
        long rowDur = (long)(rowDurationSlider * 1000);

        if (!waitingFall) {
            // Hold direction
            if (goingRight) {
                client.player.input.movementSideways = -1.0f; // strafe right
            } else {
                client.player.input.movementSideways = 1.0f;  // strafe left
            }

            // ── Auto click (harvest) ───────────────────────────────────────
            clickTickTimer++;
            int clickEvery = Math.max(1, (int)(clickSpeedSlider / 0.05f));
            if (clickTickTimer >= clickEvery) {
                clickTickTimer = 0;
                doClick(client);
                clickCount++;
            }

            // ── End of row? ────────────────────────────────────────────────
            if (now - rowStartTime >= rowDur) {
                // Stop movement, start fall detection
                client.player.input.movementSideways = 0f;
                waitingFall = true;
                endRowTime = now;
                lastY = client.player.getY();
            }

        } else {
            // ── Fall detection phase ───────────────────────────────────────
            client.player.input.movementSideways = 0f;
            long waitedMs = now - endRowTime;

            if (waitedMs > 400) {
                double currentY = client.player.getY();
                double yDiff = lastY - currentY;

                if (yDiff > 0.5) {
                    // Fell down to next floor — just flip direction
                    flipDirection();
                } else {
                    // No fall — on elevator block, jump up
                    if (jumpCooldown <= 0) {
                        client.player.jump();
                        jumpCooldown = 15; // 15 ticks cooldown
                        flipDirection();
                    }
                }
                waitingFall = false;
                rowStartTime = now;
                rowCount++;
            }
        }

        if (jumpCooldown > 0) jumpCooldown--;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private void startBot(MinecraftClient client) {
        goingRight   = true;
        rowCount     = 0;
        clickCount   = 0;
        startTime    = System.currentTimeMillis();
        rowStartTime = startTime;
        waitingFall  = false;
        jumpCooldown = 0;
        if (client.player != null) lastY = client.player.getY();
        if (client.player != null)
            client.player.sendMessage(Text.literal("§a[FarmBot] §fStarted! Press §eH§f to stop."), true);
    }

    private void stopBot(MinecraftClient client) {
        if (client.player != null) {
            client.player.input.movementSideways = 0f;
            client.player.sendMessage(Text.literal("§c[FarmBot] §fStopped."), true);
        }
    }

    private void flipDirection() {
        goingRight = !goingRight;
    }

    private void doClick(MinecraftClient client) {
        if (client.crosshairTarget != null &&
            client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            client.interactionManager.attackBlock(
                ((BlockHitResult) client.crosshairTarget).getBlockPos(),
                ((BlockHitResult) client.crosshairTarget).getSide()
            );
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    // ── HUD / GUI ─────────────────────────────────────────────────────────────
    private void renderHud(DrawContext context, float tickDelta) {
        if (!showGui) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int w = 200, h = 200;
        int x = guiX, y = guiY;

        // Background
        context.fill(x, y, x + w, y + h, 0xCC0a0a1a);
        context.fill(x, y, x + w, y + 2, 0xFF00ff88); // top accent

        // Title bar
        context.drawText(client.textRenderer,
            Text.literal("§a🌾 FarmBot §7| §fpress §eH§f to toggle"),
            x + 6, y + 6, 0xFFFFFF, false);

        // Status
        String status = botActive ? "§a● RUNNING" : "§c● STOPPED";
        context.drawText(client.textRenderer, Text.literal(status), x + 6, y + 20, 0xFFFFFF, false);

        // Stats
        if (botActive) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            long mins = elapsed / 60, secs = elapsed % 60;
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Time: §f%02d:%02d", mins, secs)),
                x + 6, y + 33, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Rows: §f" + rowCount),
                x + 6, y + 43, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Clicks: §f" + clickCount),
                x + 6, y + 53, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Direction: §f" + (goingRight ? "→ Right" : "← Left")),
                x + 6, y + 63, 0xFFFFFF, false);
        }

        // Divider
        context.fill(x + 4, y + 76, x + w - 4, y + 77, 0xFF333355);

        // Row duration slider
        context.drawText(client.textRenderer,
            Text.literal(String.format("§7Row Duration: §e%.2fs", rowDurationSlider)),
            x + 6, y + 82, 0xFFFFFF, false);
        drawSlider(context, x + 6, y + 93, w - 12, rowDurationSlider, 0.3f, 2.0f, 0xFF00ff88);

        // Click speed slider
        context.drawText(client.textRenderer,
            Text.literal(String.format("§7Click Speed: §e%.2fs", clickSpeedSlider)),
            x + 6, y + 110, 0xFFFFFF, false);
        drawSlider(context, x + 6, y + 121, w - 12, clickSpeedSlider, 0.05f, 0.3f, 0xFF00aaff);

        // Divider
        context.fill(x + 4, y + 135, x + w - 4, y + 136, 0xFF333355);

        // Keybind hints
        context.drawText(client.textRenderer,
            Text.literal("§7[G] Toggle GUI  §7[H] Start/Stop"),
            x + 6, y + 141, 0xAAAAAA, false);

        // Direction indicator
        context.drawText(client.textRenderer,
            Text.literal("§7Farm Direction:"),
            x + 6, y + 155, 0xFFFFFF, false);
        String arrow = goingRight && botActive ? "§a[←] [→ ACTIVE]" : "§a[← ACTIVE] [→]";
        if (!botActive) arrow = "§7[←] [→]";
        context.drawText(client.textRenderer, Text.literal(arrow), x + 6, y + 165, 0xFFFFFF, false);

        // Border
        context.drawBorder(x, y, w, h, 0xFF00ff44);
    }

    private void drawSlider(DrawContext ctx, int x, int y, int width, float value, float min, float max, int color) {
        float pct = (value - min) / (max - min);
        int filled = (int)(pct * width);
        ctx.fill(x, y, x + width, y + 6, 0xFF222233);
        ctx.fill(x, y, x + filled, y + 6, color);
        ctx.fill(x + filled - 1, y - 1, x + filled + 1, y + 7, 0xFFFFFFFF);
    }
}
