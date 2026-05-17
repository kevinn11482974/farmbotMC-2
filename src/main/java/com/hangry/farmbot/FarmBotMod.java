package com.hangry.farmbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

public class FarmBotMod implements ClientModInitializer {

    public static boolean botActive = false;
    public static boolean showGui = true;

    private static boolean goingRight = true;
    private static long rowStartTime = 0;
    private static float rowDurationSlider = 0.625f;
    private static float clickSpeedSlider = 0.10f;
    private static int clickTickTimer = 0;
    private static double lastY = 0;
    private static long endRowTime = 0;
    private static boolean waitingFall = false;
    private static int jumpCooldown = 0;
    private static int rowCount = 0;
    private static long startTime = 0;
    private static int clickCount = 0;

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

        long now = System.currentTimeMillis();
        long rowDur = (long)(rowDurationSlider * 1000);

        if (!waitingFall) {
            client.player.input.movementSideways = goingRight ? -1.0f : 1.0f;

            clickTickTimer++;
            int clickEvery = Math.max(1, (int)(clickSpeedSlider / 0.05f));
            if (clickTickTimer >= clickEvery) {
                clickTickTimer = 0;
                doClick(client);
                clickCount++;
            }

            if (now - rowStartTime >= rowDur) {
                client.player.input.movementSideways = 0f;
                waitingFall = true;
                endRowTime = now;
                lastY = client.player.getY();
            }
        } else {
            client.player.input.movementSideways = 0f;
            long waitedMs = now - endRowTime;

            if (waitedMs > 400) {
                double currentY = client.player.getY();
                if (lastY - currentY > 0.5) {
                    flipDirection();
                } else {
                    if (jumpCooldown <= 0) {
                        client.player.jump();
                        jumpCooldown = 15;
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

    private void startBot(MinecraftClient client) {
        goingRight = true;
        rowCount = 0;
        clickCount = 0;
        startTime = System.currentTimeMillis();
        rowStartTime = startTime;
        waitingFall = false;
        jumpCooldown = 0;
        if (client.player != null) lastY = client.player.getY();
        client.player.sendMessage(Text.literal("§a[FarmBot] Started! Press §eH§a to stop."), true);
    }

    private void stopBot(MinecraftClient client) {
        if (client.player != null) {
            client.player.input.movementSideways = 0f;
            client.player.sendMessage(Text.literal("§c[FarmBot] Stopped."), true);
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

    private void renderHud(DrawContext context, float tickDelta) {
        if (!showGui) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int x = 10, y = 10, w = 200, h = 160;
        context.fill(x, y, x + w, y + h, 0xCC0a0a1a);
        context.fill(x, y, x + w, y + 2, 0xFF00ff88);

        context.drawText(client.textRenderer,
            Text.literal("§a🌾 FarmBot §7| §eH§f=toggle §eG§f=gui"),
            x + 6, y + 6, 0xFFFFFF, false);

        String status = botActive ? "§a● RUNNING" : "§c● STOPPED";
        context.drawText(client.textRenderer, Text.literal(status), x + 6, y + 20, 0xFFFFFF, false);

        if (botActive) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Time: §f%02d:%02d", elapsed/60, elapsed%60)),
                x + 6, y + 33, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Rows: §f" + rowCount), x + 6, y + 43, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Clicks: §f" + clickCount), x + 6, y + 53, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Dir: §f" + (goingRight ? "→ Right" : "← Left")),
                x + 6, y + 63, 0xFFFFFF, false);
        }

        context.fill(x + 4, y + 76, x + w - 4, y + 77, 0xFF333355);
        context.drawText(client.textRenderer,
            Text.literal(String.format("§7Row Duration: §e%.2fs", rowDurationSlider)),
            x + 6, y + 82, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal(String.format("§7Click Speed: §e%.2fs", clickSpeedSlider)),
            x + 6, y + 95, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal("§7(Adjust in mod config)"),
            x + 6, y + 108, 0x888888, false);

        context.drawBorder(x, y, w, h, 0xFF00ff44);
    }
}
