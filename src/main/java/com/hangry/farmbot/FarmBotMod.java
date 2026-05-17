package com.hangry.farmbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

public class FarmBotMod implements ClientModInitializer {

    public static boolean botActive = false;
    public static boolean showGui = true;
    public static float clickSpeed = 0.10f;

    private static boolean goingRight = true;
    private static int clickTickTimer = 0;
    private static int rowCount = 0;
    private static long startTime = 0;
    private static int clickCount = 0;

    // Position tracking
    private static double lastX = 0;
    private static double lastZ = 0;
    private static int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 8; // ticks before considering stuck

    // Fall/jump detection
    private static double lastY = 0;
    private static int jumpCooldown = 0;
    private static int postFlipPauseTicks = 0; // brief pause after flipping

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
        HudRenderCallback.EVENT.register(this::renderHud);
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null) return;

        while (toggleGuiKey.wasPressed()) showGui = !showGui;
        while (toggleBotKey.wasPressed()) {
            botActive = !botActive;
            if (botActive) startBot(client);
            else stopBot(client);
        }

        if (!botActive) return;

        // Post-flip pause (let player settle after direction change)
        if (postFlipPauseTicks > 0) {
            postFlipPauseTicks--;
            client.player.input.movementSideways = 0f;
            return;
        }

        double currentX = client.player.getX();
        double currentZ = client.player.getZ();
        double currentY = client.player.getY();

        // Check if stuck (not moving sideways)
        double deltaX = Math.abs(currentX - lastX);
        double deltaZ = Math.abs(currentZ - lastZ);
        double movement = deltaX + deltaZ;

        if (movement < 0.01) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        // Stuck = hit barrier → flip direction
        if (stuckTicks >= STUCK_THRESHOLD) {
            stuckTicks = 0;

            // Check if fell down
            double yDiff = lastY - currentY;
            if (yDiff > 0.5) {
                // Fell to next floor, just flip
                flipDirection();
            } else {
                // No fall, try jumping (elevator block)
                if (jumpCooldown <= 0) {
                    client.player.jump();
                    jumpCooldown = 20;
                }
                flipDirection();
            }

            rowCount++;
            postFlipPauseTicks = 5; // 5 tick pause after flip
        }

        // Move in current direction
        client.player.input.movementSideways = goingRight ? -1.0f : 1.0f;

        // Auto click
        clickTickTimer++;
        int clickEvery = Math.max(1, (int)(clickSpeed / 0.05f));
        if (clickTickTimer >= clickEvery) {
            clickTickTimer = 0;
            doClick(client);
            clickCount++;
        }

        // Update last position
        lastX = currentX;
        lastZ = currentZ;
        lastY = currentY;

        if (jumpCooldown > 0) jumpCooldown--;
    }

    private void startBot(MinecraftClient client) {
        goingRight = true;
        rowCount = 0;
        clickCount = 0;
        startTime = System.currentTimeMillis();
        stuckTicks = 0;
        jumpCooldown = 0;
        postFlipPauseTicks = 0;
        lastX = client.player.getX();
        lastZ = client.player.getZ();
        lastY = client.player.getY();
        client.player.sendMessage(
            Text.literal("§a[FarmBot] Started! §eH§a=stop §eG§a=hud"), true);
    }

    private void stopBot(MinecraftClient client) {
        client.player.input.movementSideways = 0f;
        client.player.sendMessage(Text.literal("§c[FarmBot] Stopped."), true);
    }

    private void flipDirection() { goingRight = !goingRight; }

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

    private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        if (!showGui) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int x = 10, y = 10, w = 220, h = 165;
        context.fill(x, y, x + w, y + h, 0xCC0a0a1a);
        context.fill(x, y, x + w, y + 2, 0xFF00ff88);

        context.drawText(client.textRenderer,
            Text.literal("§a🌾 FarmBot"), x + 6, y + 6, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal("§7H§f=start/stop  §7G§f=hud"),
            x + 6, y + 18, 0xAAAAAA, false);

        context.fill(x + 4, y + 30, x + w - 4, y + 31, 0xFF333355);

        String status = botActive ? "§a● RUNNING" : "§c● STOPPED";
        context.drawText(client.textRenderer,
            Text.literal(status), x + 6, y + 36, 0xFFFFFF, false);

        if (botActive) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Time:   §f%02d:%02d", elapsed/60, elapsed%60)),
                x + 6, y + 49, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Rows:   §f" + rowCount),
                x + 6, y + 59, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Clicks: §f" + clickCount),
                x + 6, y + 69, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Dir:    §f" + (goingRight ? "→ Right" : "← Left")),
                x + 6, y + 79, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Stuck:  §f%d§7/§f%d ticks",
                    stuckTicks, STUCK_THRESHOLD)),
                x + 6, y + 89, 0xFFFFFF, false);

            // Position
            context.fill(x + 4, y + 101, x + w - 4, y + 102, 0xFF333355);
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7X: §f%.1f  §7Z: §f%.1f",
                    client.player.getX(), client.player.getZ())),
                x + 6, y + 106, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Y: §f%.1f", client.player.getY())),
                x + 6, y + 116, 0xFFFFFF, false);
        }

        context.drawBorder(x, y, w, h, 0xFF00ff44);
    }
}
