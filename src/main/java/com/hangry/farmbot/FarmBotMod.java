package com.hangry.farmbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
    public static float rowDuration = 0.625f;
    public static float clickSpeed = 0.10f;

    private static boolean goingRight = true;
    private static long rowStartTime = 0;
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
    private static KeyBinding openConfigKey;

    @Override
    public void onInitializeClient() {
        toggleGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "FarmBot"
        ));
        toggleBotKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "FarmBot"
        ));
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "FarmBot"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register(this::renderHud);
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null) return;
        while (toggleGuiKey.wasPressed()) showGui = !showGui;
        while (openConfigKey.wasPressed()) {
            client.setScreen(new FarmConfigScreen(client.currentScreen));
        }
        while (toggleBotKey.wasPressed()) {
            botActive = !botActive;
            if (botActive) startBot(client);
            else stopBot(client);
        }
        if (!botActive) return;
        long now = System.currentTimeMillis();
        long rowDur = (long)(rowDuration * 1000);
        if (!waitingFall) {
            client.player.input.movementSideways = goingRight ? -1.0f : 1.0f;
            clickTickTimer++;
            int clickEvery = Math.max(1, (int)(clickSpeed / 0.05f));
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
            if (System.currentTimeMillis() - endRowTime > 400) {
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
                rowStartTime = System.currentTimeMillis();
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
        lastY = client.player.getY();
        client.player.sendMessage(Text.literal("§a[FarmBot] Started! §eH§a=stop §eJ§a=config"), true);
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
        int x = 10, y = 10, w = 220, h = 175;
        context.fill(x, y, x + w, y + h, 0xCC0a0a1a);
        context.fill(x, y, x + w, y + 2, 0xFF00ff88);
        context.drawText(client.textRenderer,
            Text.literal("§a🌾 FarmBot"), x + 6, y + 6, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal("§7H§f=start/stop §7G§f=hud §7J§f=config"),
            x + 6, y + 18, 0xAAAAAA, false);
        context.fill(x + 4, y + 30, x + w - 4, y + 31, 0xFF333355);
        String status = botActive ? "§a● RUNNING" : "§c● STOPPED";
        context.drawText(client.textRenderer, Text.literal(status), x + 6, y + 35, 0xFFFFFF, false);
        if (botActive) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Time: §f%02d:%02d", elapsed/60, elapsed%60)),
                x + 6, y + 48, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Rows: §f" + rowCount), x + 6, y + 58, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Clicks: §f" + clickCount), x + 6, y + 68, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Dir: §f" + (goingRight ? "→ Right" : "← Left")),
                x + 6, y + 78, 0xFFFFFF, false);
        }
        context.fill(x + 4, y + 91, x + w - 4, y + 92, 0xFF333355);
        context.drawText(client.textRenderer,
            Text.literal(String.format("§7Row Duration: §e%.3fs", rowDuration)),
            x + 6, y + 97, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal(String.format("§7Click Speed:  §e%.2fs", clickSpeed)),
            x + 6, y + 108, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal("§7Press §eJ §7to open config"), x + 6, y + 120, 0xAAAAAA, false);
        context.drawBorder(x, y, w, h, 0xFF00ff44);
    }

    // ── Config Screen ─────────────────────────────────────────────────────────
    public static class FarmConfigScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget rowField;
        private TextFieldWidget clickField;

        public FarmConfigScreen(Screen parent) {
            super(Text.literal("FarmBot Config"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2;
            int cy = height / 2;

            // Row duration field
            rowField = new TextFieldWidget(textRenderer, cx - 60, cy - 40, 120, 20,
                Text.literal("Row Duration"));
            rowField.setText(String.valueOf(rowDuration));
            rowField.setMaxLength(10);
            addDrawableChild(rowField);

            // Click speed field
            clickField = new TextFieldWidget(textRenderer, cx - 60, cy, 120, 20,
                Text.literal("Click Speed"));
            clickField.setText(String.valueOf(clickSpeed));
            clickField.setMaxLength(10);
            addDrawableChild(clickField);

            // Save button
            addDrawableChild(ButtonWidget.builder(Text.literal("✔ Save & Close"), btn -> {
                try { rowDuration = Float.parseFloat(rowField.getText()); } catch (Exception ignored) {}
                try { clickSpeed = Float.parseFloat(clickField.getText()); } catch (Exception ignored) {}
                close();
            }).dimensions(cx - 60, cy + 30, 120, 20).build());

            // Cancel button
            addDrawableChild(ButtonWidget.builder(Text.literal("✖ Cancel"), btn -> close())
                .dimensions(cx - 60, cy + 55, 120, 20).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            renderBackground(context, mouseX, mouseY, delta);
            int cx = width / 2;
            int cy = height / 2;

            // Panel background
            context.fill(cx - 90, cy - 65, cx + 90, cy + 80, 0xDD0a0a1a);
            context.fill(cx - 90, cy - 65, cx + 90, cy - 63, 0xFF00ff88);
            context.drawBorder(cx - 90, cy - 65, 180, 145, 0xFF00ff44);

            context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§a🌾 FarmBot Config"), cx, cy - 58, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer,
                Text.literal("§7Row Duration §8(seconds):"), cx - 60, cy - 52, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer,
                Text.literal("§7Click Speed §8(seconds):"), cx - 60, cy - 12, 0xFFFFFF);

            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void close() {
            client.setScreen(parent);
        }
    }
}
